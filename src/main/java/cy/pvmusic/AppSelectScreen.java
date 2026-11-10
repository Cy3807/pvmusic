package cy.pvmusic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** 每次打开时异步枚举当前软件，选择后按进程名或 bundle ID 采集。 */
public final class AppSelectScreen extends Screen {
    private final Screen parent;
    private List<String[]> apps = List.of();
    private boolean loading;
    private int page;
    private int rows = 1;
    public AppSelectScreen(Screen parent) { super(Component.literal("选择音频软件")); this.parent = parent; }
    @Override protected void init() {
        int w = Math.min(300, width - 30), x = (width - w) / 2;
        rows = Math.max(1, Math.min(8, (height - 130) / 22));
        int pages = Math.max(1, (apps.size() + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));
        addRenderableWidget(Button.builder(Component.literal("全部（排除游戏）"), b -> choose("all", "全部音频"))
                .bounds(x, 36, w, 20).build());
        for (int i = 0; i < rows; i++) {
            int n = page * rows + i;
            if (n >= apps.size()) break;
            String[] a = apps.get(n);
            String id = a[0].isBlank() ? a[1] : a[0];
            String label = a[1].isBlank() ? id : a[1];
            String text = font.width(label) <= w - 12 ? label : font.plainSubstrByWidth(label, w - 24) + "…";
            addRenderableWidget(Button.builder(Component.literal(text), b -> choose(id, label))
                    .tooltip(Tooltip.create(Component.literal(label + "  (" + id + ")")))
                    .bounds(x, 60 + i * 22, w, 20).build());
        }
        int y = height - 49;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(x, y, 35, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> refresh()).bounds(width / 2 - 35, y, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(x + w - 35, y, 35, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(width / 2 - 50, height - 25, 100, 20).build());
    }
    public void refresh() {
        if (loading) return;
        loading = true;
        PvMusicMod.async(() -> {
            List<String[]> found = NativeTap.listApps();
            List<String[]> unique = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String[] a : found) if ((!a[0].isBlank() || !a[1].isBlank()) && seen.add(a[0].isBlank() ? a[1] : a[0])) unique.add(a);
            Minecraft.getInstance().execute(() -> { apps = unique; loading = false; page = 0; if (Minecraft.getInstance().screen == this) rebuildWidgets(); });
        });
    }
    private void choose(String id, String label) { PvMusicMod.setApp(id, label); onClose(); }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        g.drawCenteredString(font, "选择音频软件", width / 2, 8, 0xFFFFFFFF);
        g.drawCenteredString(font, loading ? "正在查找…" : (apps.isEmpty() ? "未找到软件，请先播放声音后刷新" : "当前：" + (Config.get().app.isBlank() ? "全部" : Config.get().app)), width / 2, 21, 0xFFAAAAAA);
    }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
}
