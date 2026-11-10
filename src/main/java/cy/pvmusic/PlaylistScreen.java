package cy.pvmusic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 音乐播放列表: 左边是音乐文件夹里的文件（点一下加入/移出列表），右边是列表（点选一首，可上移/下移/移除），下面是播放控制。 */
public final class PlaylistScreen extends Screen {
    private int ROWS = 8;
    private final Screen parent;
    private int filePage, listPage;
    private int selected = -1;
    private final java.util.concurrent.ExecutorService coverWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Plasmo Voice Music Cover Art"); t.setDaemon(true); return t;
    });
    private final java.util.Map<String, net.minecraft.resources.Identifier> covers = new java.util.HashMap<>();
    private final java.util.Set<String> requestedCovers = new java.util.HashSet<>();
    private volatile boolean closed;
    private static final java.util.concurrent.atomic.AtomicLong coverIds = new java.util.concurrent.atomic.AtomicLong();

    public PlaylistScreen(Screen parent) {
        super(Component.literal("播放文件音乐"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ROWS = Math.max(1, (height - 150) / 22);
        List<String> files = FilePlayer.files();
        lastFiles = files;
        List<String> list = Config.get().playlist;
        if (java.nio.file.Files.isDirectory(FilePlayer.dir()) && java.nio.file.Files.isReadable(FilePlayer.dir())) {
            String selectedName = selected >= 0 && selected < list.size() ? list.get(selected) : null;
            if (list.removeIf(name -> java.nio.file.Files.notExists(FilePlayer.dir().resolve(name)))) {
                selected = selectedName == null ? -1 : list.indexOf(selectedName);
                Config.save();
            }
        }
        int colW = Math.min(160, (width - 60) / 2);
        int lx = width / 2 - colW - 10, rx = width / 2 + 10;
        int top = 40, rowH = 22;
        addRenderableWidget(Button.builder(Component.literal("打开文件夹"), b -> {
            try { net.minecraft.util.Util.getPlatform().openPath(FilePlayer.dir()); }
            catch (Throwable e) { PvMusicMod.LOGGER.warn("打开音乐文件夹失败", e); }
        }).bounds(lx, 8, 76, 20).build());

        // 左: 文件
        int filePages = Math.max(1, (files.size() + ROWS - 1) / ROWS);
        filePage = Math.min(filePage, filePages - 1);
        for (int i = 0; i < ROWS; i++) {
            int idx = filePage * ROWS + i;
            if (idx >= files.size()) break;
            String f = files.get(idx);
            boolean in = list.contains(f);
            addRenderableWidget(Button.builder(Component.literal(shorten(f, colW - 48)).withStyle(in ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GRAY), b -> {
                if (list.contains(f)) { list.remove(f); selected = -1; } else list.add(f);
                Config.save();
                rebuildWidgets();
            }).tooltip(Tooltip.create(Component.literal(f))).bounds(lx, top + i * rowH, colW, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { filePage = Math.max(0, filePage - 1); rebuildWidgets(); })
                .bounds(lx, height - 104, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { filePage = Math.min(filePages - 1, filePage + 1); rebuildWidgets(); })
                .bounds(lx + colW - 30, height - 104, 30, 20).build());

        // 右: 列表
        int listPages = Math.max(1, (list.size() + ROWS - 1) / ROWS);
        listPage = Math.min(listPage, listPages - 1);
        String current = PvMusicMod.MIXER.player().listMode() ? PvMusicMod.MIXER.player().name() : "";
        for (int i = 0; i < ROWS; i++) {
            int idx = listPage * ROWS + i;
            if (idx >= list.size()) break;
            String f = list.get(idx);
            addRenderableWidget(Button.builder(Component.literal(shorten(f, colW - 48)).withStyle(idx == selected ? net.minecraft.ChatFormatting.WHITE : (f.equals(current) ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GRAY)), b -> { selected = idx; rebuildWidgets(); })
                    .tooltip(Tooltip.create(Component.literal(f))).bounds(rx, top + i * rowH, colW, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { listPage = Math.max(0, listPage - 1); rebuildWidgets(); })
                .bounds(rx, height - 104, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { listPage = Math.min(listPages - 1, listPage + 1); rebuildWidgets(); })
                .bounds(rx + colW - 30, height - 104, 30, 20).build());

        int cy = height - 104;
        int iconWidth = 25, middle = rx + colW / 2;
        addRenderableWidget(Button.builder(Component.literal("↑"), b -> move(-1)).tooltip(Tooltip.create(Component.literal("上移"))).bounds(middle - 39, cy, iconWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("↓"), b -> move(1)).tooltip(Tooltip.create(Component.literal("下移"))).bounds(middle - 12, cy, iconWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
            if (selected >= 0 && selected < list.size()) { list.remove(selected); selected = -1; Config.save(); rebuildWidgets(); }
        }).tooltip(Tooltip.create(Component.literal("移除"))).bounds(middle + 15, cy, iconWidth, 20).build());

        int volumeY = height - 78;
        addRenderableWidget(new VolumeSlider(lx, volumeY, colW - 24, false));
        addRenderableWidget(Button.builder(Component.literal("↺"), b -> { Config.get().fileVolume = 100; Config.save(); rebuildWidgets(); }).bounds(lx + colW - 22, volumeY, 22, 20).build());
        addRenderableWidget(new VolumeSlider(rx, volumeY, colW - 24, true));
        addRenderableWidget(Button.builder(Component.literal("↺"), b -> { Config.get().localVolume = 100; Config.save(); rebuildWidgets(); }).bounds(rx + colW - 22, volumeY, 22, 20).build());

        // 下: 播放控制
        int by = height - 52, cw = 34;
        int x0 = width / 2 - 100;
        addRenderableWidget(Button.builder(Component.literal("|◀"), b -> {
            int index = Config.get().playlist.indexOf(PvMusicMod.MIXER.player().name());
            if (index < 0) index = selected;
            PvMusicMod.playListAt(Math.max(0, index - 1));
        }).tooltip(Tooltip.create(Component.literal("上一首"))).bounds(x0, by, cw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
            if (selected >= 0 && selected < Config.get().playlist.size()) {
                int index = selected; selected = -1; PvMusicMod.playListAt(index); rebuildWidgets();
            }
        }).tooltip(Tooltip.create(Component.literal("播放选中歌曲"))).bounds(x0 + 38, by, cw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("■"), b -> {
            int currentIndex = Config.get().playlist.indexOf(PvMusicMod.MIXER.player().name());
            if (currentIndex >= 0) { selected = currentIndex; listPage = selected / ROWS; }
            PvMusicMod.async(PvMusicMod::stopFile); rebuildWidgets();
        })
                .tooltip(Tooltip.create(Component.literal("停止"))).bounds(x0 + 76, by, cw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▶|"), b -> {
            int index = Config.get().playlist.indexOf(PvMusicMod.MIXER.player().name());
            if (index < 0) index = selected;
            int next = PlaybackOrder.next(Config.get().mode(), Config.get().playlist.size(), index);
            if (next >= 0) PvMusicMod.playListAt(next);
        }).tooltip(Tooltip.create(Component.literal("下一首"))).bounds(x0 + 114, by, cw, 20).build());
        String mode = Config.get().mode();
        String label = "loop".equals(mode) ? "循环" : "shuffle".equals(mode) ? "随机" : "顺序";
        addRenderableWidget(Button.builder(Component.literal(label), b -> {
            Config c = Config.get();
            c.setMode("sequential".equals(c.mode()) ? "loop" : "loop".equals(c.mode()) ? "shuffle" : "sequential");
            Config.save(); rebuildWidgets();
        }).tooltip(Tooltip.create(Component.literal("顺序：播完停止；循环：列表循环；随机：随机切歌")))
                .bounds(x0 + 152, by, 65, 20).build());
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose()).bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    private String shorten(String value, int maxWidth) {
        return font.width(value) <= maxWidth ? value : font.plainSubstrByWidth(value, maxWidth - font.width("…")) + "…";
    }

    private static final class VolumeSlider extends AbstractSliderButton {
        private final boolean local;
        VolumeSlider(int x, int y, int width, boolean local) {
            super(x, y, width, 20, Component.empty(), (local ? Config.get().localVolume : Config.get().fileVolume) / 500.0);
            this.local = local;
            updateMessage();
        }
        @Override protected void updateMessage() {
            setMessage(Component.literal((local ? "自己：" : "别人：") + Math.round(value * 500) + "%"));
        }
        @Override protected void applyValue() {
            int v = (int) Math.round(value * 500);
            if (local) Config.get().localVolume = v; else Config.get().fileVolume = v;
            Config.save();
        }
    }

    private void move(int d) {
        List<String> list = Config.get().playlist;
        int j = selected + d;
        if (selected < 0 || selected >= list.size() || j < 0 || j >= list.size()) return;
        String t = list.get(selected);
        list.set(selected, list.get(j));
        list.set(j, t);
        selected = j;
        listPage = j / ROWS;
        Config.save();
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        int colW = Math.min(160, (width - 60) / 2);
        int lx = width / 2 - colW - 10, rx = width / 2 + 10;
        List<String> playlist = Config.get().playlist;
        for (int i = 0; i < ROWS; i++) {
            int fi = filePage * ROWS + i, li = listPage * ROWS + i;
            if (fi < lastFiles.size()) drawCover(g, lastFiles.get(fi), lx + 3, 42 + i * 22);
            if (li < playlist.size()) drawCover(g, playlist.get(li), rx + 3, 42 + i * 22);
        }
        g.drawCenteredString(font, Component.literal(Chat.parse("&f播放文件音乐")), width / 2, 12, 0xFFFFFFFF);
        g.drawString(font, Component.literal(Chat.parse("&7音乐文件夹（点一下加入/移出）")), width / 2 - colW - 10, 28, 0xFFAAAAAA);
        g.drawString(font, Component.literal(Chat.parse("&7播放列表（选中后点 ▶）")), width / 2 + 10, 28, 0xFFAAAAAA);
        if (lastFiles.isEmpty()) {
            g.drawString(font, Component.literal(Chat.parse("&7没有可播放的文件，点击“打开文件夹”添加")), width / 2 - colW - 10, 44, 0xFF888888);
        }
    }

    @Override
    public void tick() {
        String playing = PvMusicMod.MIXER.player().name();
        if (!playing.equals(lastPlaying)) {
            if (playing.isEmpty() && selected < 0 && !lastPlaying.isEmpty()) {
                int previous = Config.get().playlist.indexOf(lastPlaying);
                if (previous >= 0) { selected = previous; listPage = selected / ROWS; }
            }
            lastPlaying = playing;
            rebuildWidgets();
        }
        // 文件夹里的文件有增减时刷新左栏
        if (Minecraft.getInstance().level != null && (Minecraft.getInstance().gui.getGuiTicks() % 40 == 0)) {
            List<String> now = FilePlayer.files();
            if (!now.equals(lastFiles)) { lastFiles = now; rebuildWidgets(); }
        }
    }

    private List<String> lastFiles = List.of();
    private String lastPlaying = "";

    private void drawCover(GuiGraphics g, String name, int x, int y) {
        net.minecraft.resources.Identifier id = covers.get(name);
        if (id != null) {
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, 16, 16, 16, 16);
            return;
        }
        g.fill(x, y, x + 16, y + 16, 0xFF444444);
        g.drawCenteredString(font, "♪", x + 8, y + 4, 0xFFAAAAAA);
        if (closed || !requestedCovers.add(name) || requestedCovers.size() > 256) return;
        coverWorker.execute(() -> {
            java.awt.image.BufferedImage thumb = null;
            try { thumb = CoverArtReader.read(FilePlayer.dir().resolve(name)); }
            catch (Exception ignored) { }
            if (thumb == null) return;
            java.awt.image.BufferedImage result = thumb;
            Minecraft.getInstance().execute(() -> {
                try {
                    if (closed || Minecraft.getInstance().screen != this) return;
                    var pixels = new com.mojang.blaze3d.platform.NativeImage(64, 64, false);
                    for (int y0 = 0; y0 < 64; y0++) for (int x0 = 0; x0 < 64; x0++) pixels.setPixel(x0, y0, result.getRGB(x0, y0));
                    var texture = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "Plasmo Voice Music cover", pixels);
                    var textureId = net.minecraft.resources.Identifier.fromNamespaceAndPath("pvmusic", "cover/" + coverIds.incrementAndGet());
                    Minecraft.getInstance().getTextureManager().register(textureId, texture);
                    covers.put(name, textureId);
                } finally { result.flush(); }
            });
        });
    }

    @Override
    public void removed() {
        closed = true;
        coverWorker.shutdownNow();
        for (var id : covers.values()) Minecraft.getInstance().getTextureManager().release(id);
        covers.clear();
        super.removed();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
