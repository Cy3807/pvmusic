package cy.pvmusic;

import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.api.client.audio.capture.ClientActivation;
import su.plo.voice.api.client.audio.device.InputDevice;
import su.plo.voice.api.client.audio.filter.AudioFilter;
import su.plo.voice.api.client.event.audio.capture.AudioCaptureEvent;
import su.plo.voice.api.event.EventSubscribe;

import java.util.ArrayList;
import java.util.List;

/** Plasmo Voice 客户端附加模组 */
@Addon(id = "pv-addon-pvmusic", name = "音乐", version = "0.5.0", authors = {"cy3807"})
public final class PvMusicAddon implements AddonInitializer {
    private final AudioMixer mixer;
    @InjectPlasmoVoice
    private PlasmoVoiceClient voice;
    private MusicSender sender;
    private MusicPump pump;
    private MuteBridge bridge;
    private InputDevice wrapped;

    public PvMusicAddon(AudioMixer mixer) {
        this.mixer = mixer;
    }

    @Override
    public void onAddonInitialize() {
        PvMusicAddonHolder.set(voice);
        sender = new MusicSender(voice);
        bridge = new MuteBridge(voice);
        pump = new MusicPump(voice, mixer, sender, bridge);
        pump.start();
        PvMusicMod.LOGGER.info("Plasmo Voice addon loaded");
        try {
            PvMusicUi.init(voice.getAddonConfig(this));
        } catch (Throwable t) {
            PvMusicMod.LOGGER.warn("设置菜单加载失败", t);
        }
    }

    @Override
    public void onAddonShutdown() {
        mixer.capture().stop();
        if (pump != null) pump.stop();
        if (sender != null) sender.close();
    }

    @EventSubscribe
    public void onCapture(AudioCaptureEvent event) {
        InputDevice device = event.getDevice();
        if (device != wrapped) wrap(device);
        short[] samples = event.getSamples();
        int ch = device.getFormat().getChannels();
        int frames = samples.length / Math.max(1, ch);
        double rms = AudioMixer.rms(samples);
        mixer.markMicRms(rms);
        mixer.clearPending();
        if (!micGoingOut(samples)) return; // 麦克风没在发: 音乐由 MusicPump 自己发，和麦克风无关
        // 麦克风在发送: 音乐并进同一帧（和它走同一个流，序号连续）
        mixer.markSpeaking(rms);
        short[] music = mixer.nextMusic(rms, frames, (int) device.getFormat().getSampleRate(), true);
        if (music != null) {
            mixer.markSent();
            mixer.merge(samples, ch, music);
        }
    }

    /** 把设备上的滤波链换成 MixFilter（只做一次；设备重建后会再做） */
    private void wrap(InputDevice device) {
        List<AudioFilter> originals = new ArrayList<>(device.getFilters());
        for (AudioFilter f : originals) {
            if (f instanceof MixFilter) return; // 已经包过
        }
        for (AudioFilter f : originals) device.removeFilter(f);
        device.addFilter(new MixFilter(originals, mixer));
        wrapped = device;
        PvMusicMod.LOGGER.info("已接管 {} 个语音滤波器", originals.size());
    }

    /** 这一帧麦克风是否会被发出去: 上一帧已激活、按键说话键按着、或语音激活模式下音量够大 */
    private boolean micGoingOut(short[] samples) {
        if (bridge != null && bridge.micMuted()) return false; // 麦克风静音: PV 不会发麦克风，音乐由 MusicPump 发
        List<ClientActivation> all = new ArrayList<>(voice.getActivationManager().getActivations());
        voice.getActivationManager().getParentActivation().ifPresent(all::add);
        boolean loud = AudioMixer.rms(samples) > Config.get().speakThreshold;
        for (ClientActivation a : all) {
            if (a.isActive()) return true;
            if (a.isDisabled()) continue;
            if (a.getType() == ClientActivation.Type.PUSH_TO_TALK && a.getPttKey().isPressed()) return true;
            if (a.getType() == ClientActivation.Type.VOICE && loud) return true;
        }
        return false;
    }
}
