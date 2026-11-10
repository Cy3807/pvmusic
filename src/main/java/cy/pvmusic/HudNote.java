package cy.pvmusic;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import su.plo.voice.api.client.config.IconPosition;

/** 音乐在发送时，在 Plasmo Voice 的麦克风图标上方画一个音符图标（位置跟着它的图标位置设置走） */
public final class HudNote {
    static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("pvmusic", "note"), HudNote::render);
    }

    private static void render(GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !PvMusicMod.MIXER.sendingMusic()) return;

        IconPosition pos = IconPosition.BOTTOM_CENTER;
        try {
            var voice = PvMusicAddonHolder.voice();
            if (voice != null) {
                // 2.1.17 的公开接口里没有 getOverlay，用反射读（读不到就用默认位置）
                Object cfg = voice.getConfig();
                Object overlay = cfg.getClass().getMethod("getOverlay").invoke(cfg);
                Object entry = overlay.getClass().getMethod("getActivationIconPosition").invoke(overlay);
                Object val = entry.getClass().getMethod("value").invoke(entry);
                if (val instanceof IconPosition ip) pos = ip;
            }
        } catch (Throwable ignored) { }

        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        int x = pos.getX() == null ? w / 2 - 8 : pos.getX() < 0 ? w + pos.getX() - 16 : pos.getX();
        int y = pos.getY() == null ? h - 32 : pos.getY() < 0 ? h + pos.getY() - 16 : pos.getY();
        if (pos == IconPosition.BOTTOM_CENTER && mc.gameMode != null && mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.CREATIVE) y -= 10;
        y -= 15; // 麦克风图标正上方，水平对齐同一个 16x16 格子

        note(g, x + 1, y + 1, 0x99000000);
        note(g, x, y, 0xFFFFFFFF);
    }

    /** 16x16 格子里的单个 ♪（符头 + 符干 + 符尾） */
    private static void note(GuiGraphics g, int x, int y, int c) {
        g.fill(x + 5, y + 9, x + 9, y + 10, c);   // 符头（圆角）
        g.fill(x + 4, y + 10, x + 9, y + 13, c);
        g.fill(x + 5, y + 13, x + 8, y + 14, c);
        g.fill(x + 9, y + 1, x + 11, y + 12, c);  // 符干
        g.fill(x + 11, y + 1, x + 13, y + 3, c);  // 符尾
        g.fill(x + 12, y + 3, x + 14, y + 6, c);
        g.fill(x + 11, y + 6, x + 13, y + 8, c);
    }
}
