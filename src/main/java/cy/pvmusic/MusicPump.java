package cy.pvmusic;

import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.api.client.connection.ServerInfo;

import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/**
 * 音乐的独立发送线程: 自己按精确的 20ms 节拍取一帧、编码、发包，不依赖麦克风采集线程
 * （所以麦克风关了/没有输入设备/按键说话没按，音乐照常发；也不会受麦克风读取时间抖动的影响）。
 * 只有麦克风流正在发送时才让位给采集线程，由它把音乐并进同一个流。
 */
public final class MusicPump implements Runnable {
    private final PlasmoVoiceClient voice;
    private final AudioMixer mixer;
    private final MusicSender sender;
    private final MuteBridge bridge;
    private int tickCount;
    private long maxLate, lateCount;
    private volatile boolean running;
    private Thread thread;
    private long lastLog;

    public MusicPump(PlasmoVoiceClient voice, AudioMixer mixer, MusicSender sender, MuteBridge bridge) {
        this.bridge = bridge;
        this.voice = voice;
        this.mixer = mixer;
        this.sender = sender;
    }

    public void start() {
        running = true;
        thread = new Thread(this, "Plasmo Voice Music Music Pump");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        final long frame = 20_000_000L;
        final long minimumSpacing = 18_000_000L;
        long deadline = System.nanoTime() + frame;
        while (running) {
            if (!waitUntil(deadline)) break;
            long started = System.nanoTime();
            long late = Math.max(0, (started - deadline) / 1_000_000L);
            if (late > maxLate) maxLate = late;
            if (late > 8) lateCount++;
            try { tick(); }
            catch (Throwable t) { PvMusicMod.LOGGER.warn("音乐发送线程出错", t); }

            long finished = System.nanoTime();
            // 不追赶错过的节拍：保证本次发送后下一个新包至少间隔 18ms。
            long next = Math.max(deadline + frame, finished + minimumSpacing);
            if (Config.get().adaptiveRedundancy && late < 4) {
                long retryAt = finished + 9_000_000L;
                if (!waitUntil(retryAt)) break;
                long now = System.nanoTime();
                // 线程唤醒太晚或离下个新包太近时放弃补发。
                if (now - retryAt < 3_000_000L && next - now >= 6_000_000L) sender.resend();
            }
            deadline = next;
        }
    }

    private boolean waitUntil(long deadline) {
        while (running) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return true;
            LockSupport.parkNanos(remaining);
            if (Thread.interrupted()) return false;
        }
        return false;
    }

    private void tick() {
        if (++tickCount % 50 == 0) { // 每 1 秒
            bridge.check(false); // 核对静音状态（只在需要变化时才发包）
        }
        Config cfg = Config.get();
        boolean any = (cfg.enabled && mixer.capture().running()) || mixer.player().playing();
        Optional<ServerInfo> info = voice.getServerInfo();
        if (info.isEmpty()) {
            mixer.localOnly(48000); // 没连语音服务器: 文件仍然能自己听
            sender.send(null);
            return;
        }
        if (!any) {
            mixer.monitor().idleCheck();
            sender.send(null);
            return;
        }
        if (mixer.speakingRecently()) {
            sender.handover();
            return;
        }
        int rate = info.get().getVoiceInfo().getCaptureInfo().getSampleRate();
        short[] music = mixer.nextMusic(mixer.micRms(), rate / 50, rate, false);
        if (music != null) {
            mixer.markSent();
            long now = System.currentTimeMillis();
            if (now - lastLog > 10_000) {
                lastLog = now;
                PvMusicMod.LOGGER.info("音乐发送中(独立流): {} | 发送线程最大延迟 {}ms, 超过8ms {} 次{}", mixer.capture().stats(), maxLate, lateCount,
                        mixer.player().playing() ? " | 文件 " + mixer.player().name() : "");
                maxLate = 0; lateCount = 0;
            }
        }
        sender.send(music);
    }
}
