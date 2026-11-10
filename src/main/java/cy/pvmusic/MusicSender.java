package cy.pvmusic;

import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.api.client.audio.capture.ClientActivation;
import su.plo.voice.api.client.connection.ServerInfo;
import su.plo.voice.api.audio.codec.AudioEncoder;
import su.plo.voice.api.encryption.Encryption;
import su.plo.voice.proto.data.audio.codec.opus.OpusEncoderInfo;
import su.plo.voice.proto.data.audio.codec.opus.OpusMode;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

import java.util.Optional;

/**
 * 电脑音频的独立发送通路: 麦克风没在发送时，音乐自己编码、自己发包（走“近距离”激活），
 * 不经过按键说话/语音激活，所以有声音就一直发。序号用单独的、很大的计数器，
 * 和 Plasmo Voice 自己的麦克风序号错开。
 */
public final class MusicSender {
    private static final int END_AFTER_SILENT_FRAMES = 10; // 200ms

    private final PlasmoVoiceClient voice;
    private AudioEncoder mono, stereo;
    private ServerInfo encoderOwner;
    private long fallbackSeq = 1L << 32;
    private java.util.Map<java.util.UUID, Long> seqMap;
    private boolean seqTried;
    private PlayerAudioPacket lastPacket;
    private int resendsRemaining;
    private boolean open;
    private int silent;
    private int openDistance;

    public MusicSender(PlasmoVoiceClient voice) {
        this.voice = voice;
    }

    public boolean isOpen() { return open; }

    /** music=立体声交错的一帧；null 表示这一帧没有声音 */
    public synchronized void send(short[] music) {
        if (music == null) {
            if (open && ++silent >= END_AFTER_SILENT_FRAMES) end();
            return;
        }
        silent = 0;
        try {
            Optional<ServerInfo> info = voice.getServerInfo();
            Optional<ClientActivation> act = voice.getActivationManager().getActivationById(VoiceActivation.PROXIMITY_ID);
            if (info.isEmpty() || act.isEmpty() || voice.getAudioCapture().isServerMuted()) return;
            if (!voice.getUdpClientManager().isConnected()) return;

            ClientActivation a = act.get();
            boolean useStereo = false; // 只发单声道: Plasmo Voice 只对单声道做丢包补偿
            AudioEncoder enc = encoder(info.get(), useStereo);
            short[] pcm = useStereo ? music : downmix(music);
            byte[] data = enc.encode(pcm);
            Encryption crypt = voice.getAudioCapture().getEncryption().orElse(null);
            if (crypt != null) data = crypt.encrypt(data);

            int dist = a.getDistance();
            byte[] out = data;
            PlayerAudioPacket pkt = new PlayerAudioPacket(nextSeq(), out, a.getId(), (short) dist, useStereo);
            voice.getUdpClientManager().getClient().ifPresent(c -> c.sendPacket(pkt));
            lastPacket = pkt;
            resendsRemaining = 1;
            open = true;
            openDistance = dist;
        } catch (Exception e) {
            PvMusicMod.LOGGER.warn("发送音乐失败", e);
        }
    }

    /** 结束当前这段音乐流（麦克风接管、或音乐停了） */
    public synchronized void end() {
        if (!open) return;
        open = false;
        lastPacket = null;
        silent = 0;
        int dist = openDistance;
        long s = nextSeq();
        if (mono != null) mono.reset();
        if (stereo != null) stereo.reset();
        voice.getServerConnection().ifPresent(c ->
                c.sendPacket(new PlayerAudioEndPacket(s, VoiceActivation.PROXIMITY_ID, (short) dist)));
    }

    /**
     * 和 Plasmo Voice 自己的麦克风流共用同一个序号计数器（反射读它的 activationSequenceNumbers）。
     * 两路在同一个“玩家+激活”流里发包，序号必须连续递增，否则接收端会当成丢包/乱序去补偿，听起来就是间隔出现的电音和卡顿。
     * 都在语音采集线程里调用，不会并发。读不到就退回独立计数器。
     */
    @SuppressWarnings("unchecked")
    private long nextSeq() {
        if (!seqTried) {
            seqTried = true;
            try {
                Object cap = voice.getAudioCapture();
                java.lang.reflect.Field f = cap.getClass().getDeclaredField("activationSequenceNumbers");
                f.setAccessible(true);
                seqMap = (java.util.Map<java.util.UUID, Long>) f.get(cap);
            } catch (Throwable t) {
                PvMusicMod.LOGGER.warn("读不到 Plasmo Voice 的序号计数器，音乐流用独立序号", t);
            }
        }
        if (seqMap != null) {
            synchronized (seqMap) {
                long v = seqMap.getOrDefault(VoiceActivation.PROXIMITY_ID, 0L) + 1;
                seqMap.put(VoiceActivation.PROXIMITY_ID, v);
                return v;
            }
        }
        return ++fallbackSeq;
    }

    /** 把上一个包再发一次（抗丢包）；接收端按序号去重，重复的会被丢弃 */
    public synchronized void resend() {
        PlayerAudioPacket p = lastPacket;
        if (p == null || !open || resendsRemaining <= 0) return;
        resendsRemaining--;
        voice.getUdpClientManager().getClient().ifPresent(c -> c.sendPacket(p));
    }

    /** 麦克风流接管了（音乐并进它的帧里）: 不发结束包，流还在继续 */
    public synchronized void handover() {
        open = false;
        lastPacket = null;
        silent = 0;
    }

    public synchronized void close() {
        end();
        if (mono != null) mono.close();
        if (stereo != null) stereo.close();
        mono = null;
        stereo = null;
        encoderOwner = null;
    }

    private AudioEncoder encoder(ServerInfo info, boolean st) {
        if (encoderOwner != info) {
            if (mono != null) mono.close();
            if (stereo != null) stereo.close();
            mono = null;
            stereo = null;
            encoderOwner = info;
        }
        // 服务器给语音配的是 VOIP 模式（听起来像电音），音乐用自己的“音频”模式编码器；接收端解码不受影响
        ServerInfo.VoiceInfo vi = info.getVoiceInfo();
        int rate = vi.getCaptureInfo().getSampleRate();
        int mtu = vi.getCaptureInfo().getMtuSize();
        OpusEncoderInfo ci = new OpusEncoderInfo(OpusMode.AUDIO, Config.get().musicBitrate);
        if (st) {
            if (stereo == null) stereo = voice.getCodecManager().createEncoder(ci, rate, true, mtu);
            return stereo;
        }
        if (mono == null) mono = voice.getCodecManager().createEncoder(ci, rate, false, mtu);
        return mono;
    }

    private static short[] downmix(short[] s) {
        short[] m = new short[s.length / 2];
        for (int i = 0; i < m.length; i++) m[i] = (short) ((s[i * 2] + s[i * 2 + 1]) / 2);
        return m;
    }
}
