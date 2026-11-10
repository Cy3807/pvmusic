package cy.pvmusic;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 读取“电脑正在播放的声音”：
 * - macOS 14.2+: 自带原生程序（见 NativeTap），可排除游戏或只抓指定软件；
 * - 其它: 系统的回环输入设备（Windows 立体声混音 / VB-Cable、Linux PulseAudio monitor、macOS BlackHole）。
 * 重采样成语音使用的采样率后放进 PcmRing，由语音采集线程按帧取走。
 */
public final class SystemAudioCapture {
    private static final String[] LOOPBACK_HINTS = {
            "系统音频", "blackhole", "loopback", "soundflower", "stereo mix", "立体声混音", "what u hear",
            "cable output", "vb-audio", "monitor of", "monitor"
    };
    private static final int[] RATES = {48000, 44100};

    private final PcmRing ring = new PcmRing();
    private final Resampler resampler = new Resampler();

    private volatile Thread thread;
    private volatile int targetRate = 48000;
    private volatile String openedName = "";
    private volatile String error = "";

    /** 最近一次枚举到的设备名（给 Tab 补全用，避免在主线程里枚举） */
    public static volatile List<String> cachedNames = List.of();

    public static List<Mixer.Info> inputDevices() {
        List<Mixer.Info> out = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(info);
                if (m.getTargetLineInfo(new Line.Info(TargetDataLine.class)).length > 0) out.add(info);
            } catch (Exception ignored) {
            }
        }
        cachedNames = out.stream().map(Mixer.Info::getName).toList();
        return out;
    }

    public static boolean looksLikeLoopback(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String h : LOOPBACK_HINTS) if (n.contains(h)) return true;
        return false;
    }

    public static Mixer.Info resolve(String wanted) {
        List<Mixer.Info> all = inputDevices();
        if (wanted != null && !wanted.isBlank()) {
            String w = wanted.toLowerCase(Locale.ROOT);
            for (Mixer.Info i : all) if (i.getName().toLowerCase(Locale.ROOT).contains(w)) return i;
            return null;
        }
        for (Mixer.Info i : all) if (looksLikeLoopback(i.getName())) return i;
        return null;
    }

    public boolean running() { return thread != null; }
    public String openedName() { return openedName; }
    public String error() { return error; }
    public int underruns() { return ring.underruns(); }

    public void setTargetRate(int rate) {
        if (rate > 0 && rate != targetRate) { targetRate = rate; ring.setRate(rate); }
    }

    public short[] read(int frames) { return ring.read(frames); }
    public void flush() { ring.clear(); }
    public String stats() { return ring.stats(); }

    /** wanted: 设备名；空或 "system" 在 macOS 上用原生采集。app: 只抓这些软件(逗号分隔)，空=全部 */
    public synchronized boolean start(String wanted, String app) {
        stop();
        error = "";
        ring.setRate(targetRate);
        boolean blank = wanted == null || wanted.isBlank();
        boolean wantNative = (blank || wanted.equalsIgnoreCase("system") || wanted.equals(NativeTap.NAME)) && NativeTap.supported();
        if (wantNative) {
            try {
                NativeTap tap = NativeTap.open(app);
                openedName = NativeTap.NAME + (app == null || app.isBlank() ? "" : " · " + app);
                ring.clear();
                Thread t = new Thread(() -> runNative(tap), "Plasmo Voice Music Audio Capture");
                t.setDaemon(true);
                thread = t;
                t.start();
                return true;
            } catch (Exception e) {
                PvMusicMod.LOGGER.warn("原生系统音频采集失败", e);
                if (!blank) { error = e.getMessage(); return false; }
            }
        }
        Mixer.Info info = resolve(wanted);
        if (info == null) {
            error = blank ? "没找到回环设备" : "没找到设备";
            return false;
        }
        TargetDataLine line = null;
        AudioFormat fmt = null;
        outer:
        for (int ch = 2; ch >= 1; ch--) {
            for (int rate : RATES) {
                AudioFormat f = new AudioFormat(rate, 16, ch, true, false);
                try {
                    TargetDataLine l = (TargetDataLine) AudioSystem.getMixer(info).getLine(new DataLine.Info(TargetDataLine.class, f));
                    l.open(f, rate * ch * 2 / 5);
                    line = l;
                    fmt = f;
                    break outer;
                } catch (Exception ignored) {
                }
            }
        }
        if (line == null) {
            error = "无法打开 " + info.getName();
            return false;
        }
        openedName = info.getName();
        ring.clear();
        final TargetDataLine l = line;
        final AudioFormat f = fmt;
        Thread t = new Thread(() -> {
            try {
                l.start();
                pump((int) f.getSampleRate(), f.getChannels(), b -> l.read(b, 0, b.length));
            } finally {
                try { l.stop(); l.close(); } catch (Exception ignored) { }
                if (thread == Thread.currentThread()) thread = null;
            }
        }, "Plasmo Voice Music Audio Capture");
        t.setDaemon(true);
        thread = t;
        t.start();
        return true;
    }

    public synchronized void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
        ring.clear();
    }

    private void runNative(NativeTap tap) {
        try {
            pump(tap.rate(), 2, b -> { tap.readFully(b); return b.length; });
        } finally {
            tap.close();
            if (thread == Thread.currentThread()) thread = null;
        }
    }

    private interface Reader { int read(byte[] b) throws Exception; }

    private void pump(int srcRate, int srcCh, Reader reader) {
        byte[] buf = new byte[srcRate / 50 * srcCh * 2]; // 20ms
        boolean heard = false, warned = false;
        long startedAt = System.currentTimeMillis();
        try {
            while (thread == Thread.currentThread()) {
                int n = reader.read(buf);
                if (n <= 0) continue;
                if (!heard) {
                    for (int i = 0; i < n; i++) if (buf[i] != 0) { heard = true; break; }
                    if (!heard && !warned && System.currentTimeMillis() - startedAt > 8000) {
                        warned = true;
                        Chat.say("&78 秒没有声音 &7(若正在播放，请在系统设置的隐私里允许录制系统音频)");
                    }
                }
                int frames = n / (2 * srcCh);
                short[] st = new short[frames * 2];
                for (int i = 0; i < frames; i++) {
                    int o = i * srcCh * 2;
                    short l = (short) ((buf[o] & 0xff) | (buf[o + 1] << 8));
                    short r = l;
                    if (srcCh == 2) r = (short) ((buf[o + 2] & 0xff) | (buf[o + 3] << 8));
                    st[i * 2] = l;
                    st[i * 2 + 1] = r;
                }
                short[] out = resampler.process(st, srcRate, targetRate);
                ring.push(out, 0, out.length / 2);
            }
        } catch (Exception e) {
            if (thread == Thread.currentThread()) {
                error = "采集中断: " + e.getMessage();
                PvMusicMod.LOGGER.warn("系统音频采集中断", e);
            }
        }
    }
}
