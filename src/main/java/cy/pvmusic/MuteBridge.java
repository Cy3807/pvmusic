package cy.pvmusic;

import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;

/**
 * Plasmo Voice 的“静音麦克风”会把 microphoneMuted=true 告诉服务器，服务器就直接丢掉这个玩家的所有语音包（包括音乐）。
 * 所以音乐在播放、而玩家又静音了麦克风时，我们向服务器改报“麦克风没静音”；
 * 客户端自己的静音仍然有效（Plasmo Voice 采集线程在静音时根本不发麦克风），所以别人听不到你的声音，只听得到音乐。
 * 音乐停了就把真实状态再报回去。代价：静音期间别人看到的是“未静音”图标。
 */
public final class MuteBridge {
    private final PlasmoVoiceClient voice;
    private boolean spoofed;
    private boolean lastSentMuted;
    private boolean lastSentValid;

    public MuteBridge(PlasmoVoiceClient voice) {
        this.voice = voice;
        try {
            // 在 Plasmo Voice 自己的监听器之后执行（它先发真实状态，我们再覆盖）
            voice.getConfig().getVoice().getMicrophoneDisabled().addChangeListener(v -> { lastSentValid = false; check(true); });
            voice.getConfig().getVoice().getDisabled().addChangeListener(v -> { lastSentValid = false; check(true); });
        } catch (Throwable t) {
            PvMusicMod.LOGGER.warn("无法监听静音状态", t);
        }
    }

    public boolean micMuted() {
        try { return voice.getConfig().getVoice().getMicrophoneDisabled().value(); } catch (Throwable t) { return false; }
    }

    private boolean musicWanted(AudioMixer mixer) {
        return (Config.get().enabled && mixer.capture().running()) || mixer.player().playing();
    }

    /** 状态需要变化时才发包。force=PV 刚发过真实状态，需要重新覆盖 */
    public synchronized void check(boolean force) {
        try {
            AudioMixer mixer = PvMusicMod.MIXER;
            boolean muted = micMuted();
            boolean wantSpoof = muted && musicWanted(mixer);
            if (wantSpoof && (!spoofed || force || !lastSentValid)) {
                send(false);
                spoofed = true;
            } else if (!wantSpoof && spoofed) {
                send(muted);
                spoofed = false;
            }
        } catch (Throwable t) {
            PvMusicMod.LOGGER.warn("静音桥接出错", t);
        }
    }

    private void send(boolean muted) {
        boolean disabled = voice.getConfig().getVoice().getDisabled().value();
        voice.getServerConnection().ifPresent(c -> c.sendPacket(new PlayerStatePacket(disabled, muted)));
        lastSentMuted = muted;
        lastSentValid = true;
    }
}
