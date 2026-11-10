package cy.pvmusic;

import su.plo.voice.api.client.PlasmoVoiceClient;

/** 让 HUD 等不在附加模组类里的代码能拿到 Plasmo Voice 客户端 */
final class PvMusicAddonHolder {
    private static volatile PlasmoVoiceClient voice;
    static void set(PlasmoVoiceClient v) { voice = v; }
    static PlasmoVoiceClient voice() { return voice; }
    private PvMusicAddonHolder() {}
}
