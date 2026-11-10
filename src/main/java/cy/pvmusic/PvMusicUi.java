package cy.pvmusic;

import su.plo.config.entry.BooleanConfigEntry;
import su.plo.config.entry.IntConfigEntry;
import su.plo.slib.api.chat.component.McTextComponent;
import su.plo.voice.api.client.config.addon.AddonConfig;

/** Plasmo Voice 音乐设置。文件播放设置位于播放列表。 */
public final class PvMusicUi {
    private static BooleanConfigEntry on, duck, redundancy;
    private static IntConfigEntry volume, duckPct;
    private static boolean syncing;
    private static McTextComponent t(String s) { return McTextComponent.literal(s); }

    static void init(AddonConfig ac) {
        Config c = Config.get();
        // 注册固定默认值；之后恢复 Plasmo Voice Music 自己持久化的用户值。
        on = ac.addToggle("capture_on", t("播放电脑音频"), t("把电脑正在播放的声音发给其他玩家"), false);
        volume = ac.addIntSlider("system_volume", t("电脑音频音量"), t("别人听到的电脑音频音量"), "%", 100, 0, 500);
        duck = ac.addToggle("duck_on", t("说话时压低音乐"), t("你说话时自动把音乐压低"), true);
        duckPct = ac.addIntSlider("duck_percent", t("压低到"), t("说话时音乐的音量"), "%", 30, 0, 100);
        redundancy = ac.addToggle("adaptive_redundancy", t("抗丢包"), t("补发一次；发送线程延迟时自动跳过补发，可能增加网络负担"), false);
        syncing = true;
        redundancy.set(c.adaptiveRedundancy);
        c.enabled = false;
        on.set(false); volume.set(c.volume); duck.set(c.duck); duckPct.set(c.duckPercent);
        syncing = false;
        restoreOldBuffer();
        on.addChangeListener(v -> { if (!syncing && v != Config.get().enabled) PvMusicMod.setEnabled(v); });
        redundancy.addChangeListener(v -> change(() -> Config.get().adaptiveRedundancy = v));
        volume.addChangeListener(v -> change(() -> Config.get().volume = v));
        duck.addChangeListener(v -> change(() -> Config.get().duck = v));
        duckPct.addChangeListener(v -> change(() -> Config.get().duckPercent = v));
    }

    private static void restoreOldBuffer() {
        Config c = Config.get();
        if (c.savedJitter < 0) return;
        try {
            var adv = PvMusicAddonHolder.voice().getConfig().getAdvanced();
            adv.getJitterPacketDelay().set(c.savedJitter);
            adv.getAdaptiveJitterBuffer().set(c.savedAdaptive);
            adv.getAlPlaybackBuffers().set(c.savedAlBuffers);
        } catch (Throwable t) { PvMusicMod.LOGGER.warn("恢复接收缓冲失败", t); }
        c.savedJitter = -1;
        Config.save();
    }
    private static void change(Runnable r) { if (!syncing) { r.run(); Config.save(); } }
    static void sync() {
        if (on == null) return;
        Config c = Config.get();
        syncing = true;
        try { on.set(c.enabled); redundancy.set(c.adaptiveRedundancy); volume.set(c.volume); duck.set(c.duck); duckPct.set(c.duckPercent); }
        finally { syncing = false; }
    }
}
