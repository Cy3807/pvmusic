package cy.pvmusic;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Plasmo Voice 的设置界面是它自己的一套控件，外面包了一层 MC 的 ScreenWrapper（渲染和点击都不走原版控件），
 * 所以不能往里加原版按钮。这里用 Fabric 的界面事件自己画两个按钮、自己判断点击；
 * 并把它写死的“插件/Addons”标签文字（语言键 gui.plasmovoice.addons）改成“音乐”。
 */
public final class PvScreenTools {
    private static final String ADDONS_KEY = "gui.plasmovoice.addons";

    static void register() {
        // 标签文字: 语言表是资源包加载出来的（用户的汉化包优先级更高，我们的资源包盖不过），所以直接改内存里的语言表
        ScreenEvents.BEFORE_INIT.register((mc, screen, w, h) -> { if (isPvScreen(screen)) patchLanguage(); });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!isPvScreen(screen)) return;
            Button[] buttons = {
                    new Button("播放文件音乐", 8, h - 28, 100, () -> mc.setScreen(new PlaylistScreen(screen))),
                    new Button(() -> { Config c = Config.get(); return c.app.isBlank() ? "全部音频" : (c.appLabel == null || c.appLabel.isBlank() ? c.app : c.appLabel); }, 112, h - 28, 100, () -> { AppSelectScreen picker = new AppSelectScreen(screen); mc.setScreen(picker); picker.refresh(); }),
            };
            ScreenEvents.afterRender(screen).register((s, g, mx, my, delta) -> { for (Button b : buttons) b.render(g, mx, my); });
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                for (Button b : buttons) if (b.hit(event.x(), event.y())) { b.action.run(); return false; }
                return true;
            });
        });
    }

    /** 是不是 Plasmo Voice 的设置界面 */
    static boolean isPvScreen(Screen screen) {
        if (screen == null || !screen.getClass().getName().equals("su.plo.lib.mod.client.gui.screen.ScreenWrapper")) return false;
        try {
            Object inner = screen.getClass().getMethod("getScreen").invoke(screen);
            return inner.getClass().getName().startsWith("su.plo.voice.client.gui.settings.VoiceSettingsScreen");
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void patchLanguage() {
        try {
            Language lang = Language.getInstance();
            for (Field f : lang.getClass().getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType()) || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Map<String, String> m = (Map<String, String>) f.get(lang);
                if (m == null || "音乐".equals(m.get(ADDONS_KEY))) return;
                Map<String, String> copy = new HashMap<>(m);
                copy.put(ADDONS_KEY, "音乐");
                f.set(lang, copy);
                return;
            }
        } catch (Throwable t) {
            PvMusicMod.LOGGER.warn("无法修改“插件”标签文字", t);
        }
    }

    private static final class Button {
        final java.util.function.Supplier<String> label;
        final int x, y, w;
        final Runnable action;

        Button(String label, int x, int y, int w, Runnable action) {
            this(() -> label, x, y, w, action);
        }

        Button(java.util.function.Supplier<String> label, int x, int y, int w, Runnable action) {
            this.label = label; this.x = x; this.y = y; this.w = w; this.action = action;
        }

        boolean hit(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + 20; }

        void render(GuiGraphics g, int mx, int my) {
            boolean hover = hit(mx, my);
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    net.minecraft.resources.Identifier.withDefaultNamespace(hover ? "widget/button_highlighted" : "widget/button"), x, y, w, 20);
            var font = Minecraft.getInstance().font;
            String text = label.get();
            if (font.width(text) > w - 8) text = font.plainSubstrByWidth(text, w - 16) + "…";
            g.drawString(font, text, x + (w - font.width(text)) / 2, y + 6, 0xFFFFFFFF);
        }
    }
}
