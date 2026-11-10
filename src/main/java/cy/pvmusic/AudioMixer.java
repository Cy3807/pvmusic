package cy.pvmusic;

/**
 * 把电脑音频叠加到麦克风帧上。
 *
 * 做法: 语音采集线程每取一帧麦克风数据，会先触发 AudioCaptureEvent。这里
 * 1) 记下纯麦克风帧，2) 把电脑音频叠加进同一个数组（让 Plasmo Voice 的语音激活把音乐也当作“有声音”，音乐才会被发出去），
 * 之后 Plasmo Voice 对帧做滤波(增益/降噪)时，{@link MixFilter} 会改用第 1 步记下的纯麦克风帧走完原来的滤波链，
 * 最后才把电脑音频加上去——所以降噪只作用于人声，不会把音乐当噪音吃掉。
 */
public final class AudioMixer {
    /** 一帧待混音的数据 */
    public record Frame(short[] mic, short[] music) { }

    private final SystemAudioCapture capture = new SystemAudioCapture();
    private final LocalMonitor monitor = new LocalMonitor();
    private final FilePlayer player = new FilePlayer(monitor);
    private volatile long lastMusicMs;
    private volatile long speakingMs;
    private volatile double micRms;
    private volatile Frame pending;
    private double duckGain = 1.0;

    public SystemAudioCapture capture() { return capture; }
    public FilePlayer player() { return player; }
    public LocalMonitor monitor() { return monitor; }

    /** 没连语音服务器时: 只读文件的帧让自己听到（不发送） */
    public void localOnly(int rate) {
        if (!player.playing()) { monitor.idleCheck(); return; }
        player.setTargetRate(rate);
        player.read(rate / 50); // 未连接时仍按节拍消耗发送缓冲
    }

    /** 最近 0.4 秒内有音乐被发出去（给 HUD 图标用） */
    public boolean sendingMusic() { return System.currentTimeMillis() - lastMusicMs < 400; }
    public void markSent() { lastMusicMs = System.currentTimeMillis(); }

    /** 麦克风流正在发送（音乐由语音采集线程并进去）；发送线程这时不要自己发 */
    public void markSpeaking(double rms) { speakingMs = System.currentTimeMillis(); micRms = rms; }
    public void markMicRms(double rms) { micRms = rms; }
    public boolean speakingRecently() { return System.currentTimeMillis() - speakingMs < 80; }
    public double micRms() { return micRms; }

    public Frame pending(int length) {
        Frame f = pending;
        return f != null && f.mic().length == length ? f : null;
    }

    /** 取一帧电脑音频(立体声交错，已乘音量/压低)；没开启、缓冲不够或静音时返回 null。speaking=这一帧麦克风会被发出去 */
    public short[] nextMusic(double micLevel, int frames, int sampleRate, boolean speaking) {
        pending = null;
        Config cfg = Config.get();
        boolean file = player.playing();
        boolean sys = !file && cfg.enabled && capture.running();
        if (!sys && !file) return null;
        capture.setTargetRate(sampleRate);
        player.setTargetRate(sampleRate);
        short[] a = sys ? capture.read(frames) : null;
        short[] f = file ? player.read(frames) : null;

        if (a == null && f == null) return null;

        double target = 1.0;
        if (speaking && cfg.duck && micLevel > cfg.speakThreshold) target = cfg.duckPercent / 100.0;
        duckGain += (target - duckGain) * (target < duckGain ? 0.5 : 0.05);
        double ga = cfg.volume / 100.0 * duckGain, gf = cfg.fileVolume / 100.0 * duckGain;

        short[] music = new short[frames * 2];
        boolean audible = false;
        for (int i = 0; i < music.length; i++) {
            int v = (int) Math.round((a != null ? a[i] * ga : 0) + (f != null ? f[i] * gf : 0));
            music[i] = clamp(v);
            if (v > 8 || v < -8) audible = true;
        }
        return audible ? music : null;
    }

    /**
     * 麦克风正在发送时: 只登记这一帧的音乐，不改 samples。
     * （以前把音乐混进 samples，语音激活会把音乐当成说话，说完话后麦克风图标一直亮、说话放大头的模组也会跟着动。）
     * 真正的叠加在 MixFilter 里、滤波之后做。
     */
    public void merge(short[] samples, int deviceChannels, short[] music) {
        pending = new Frame(samples, music);
    }

    public void clearPending() { pending = null; }

    /** 滤波后的人声帧叠加音乐。channels 是滤波结束时的声道数 */
    public short[] mixAfterFilters(short[] voice, int channels, Frame f) {
        short[] music = f.music();
        int frames = music.length / 2;
        if (channels == 1) {
            if (voice.length < frames) frames = voice.length;
            for (int i = 0; i < frames; i++) voice[i] = clamp(voice[i] + (music[i * 2] + music[i * 2 + 1]) / 2);
        } else {
            frames = Math.min(frames, voice.length / 2);
            for (int i = 0; i < frames; i++) {
                voice[i * 2] = clamp(voice[i * 2] + music[i * 2]);
                voice[i * 2 + 1] = clamp(voice[i * 2 + 1] + music[i * 2 + 1]);
            }
        }
        return voice;
    }

    public static double rms(short[] s) {
        if (s.length == 0) return 0;
        double sum = 0;
        for (short v : s) sum += (double) v * v;
        return Math.sqrt(sum / s.length);
    }

    private static short clamp(int v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }
}
