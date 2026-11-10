package cy.pvmusic;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.plo.voice.api.client.PlasmoVoiceClient;

import java.util.List;

public final class PvMusicMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("pvmusic");
    public static final AudioMixer MIXER = new AudioMixer();

    private KeyMapping toggleKey;
    private static volatile long lastTick = System.nanoTime();
    private static volatile Thread mainThread;

    /** 诊断: 开启后 8 秒内，主线程超过 150ms 没 tick 就把它的调用栈写进日志（查"开启时卡一下"的原因） */
    private static void watchStalls() {
        Thread main = mainThread;
        if (main == null) return;
        Thread w = new Thread(() -> {
            long end = System.currentTimeMillis() + 8000;
            boolean logged = false;
            while (System.currentTimeMillis() < end) {
                long gapMs = (System.nanoTime() - lastTick) / 1_000_000;
                if (gapMs > 150 && !logged) {
                    StringBuilder sb = new StringBuilder("主线程卡顿 " + gapMs + "ms，调用栈:");
                    for (StackTraceElement e : main.getStackTrace()) sb.append("\n    at ").append(e);
                    LOGGER.warn(sb.toString());
                    logged = true;
                } else if (gapMs <= 150) logged = false;
                try { Thread.sleep(30); } catch (InterruptedException e) { return; }
            }
        }, "Plasmo Voice Music Stall Watchdog");
        w.setDaemon(true);
        w.start();
    }

    @Override
    public void onInitializeClient() {
        PlasmoVoiceClient.getAddonsLoader().load(new PvMusicAddon(MIXER));

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.pvmusic.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pvmusic", "main"))));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            lastTick = System.nanoTime();
            while (toggleKey.consumeClick()) setEnabled(!Config.get().enabled);
        });

        HudNote.register();
        PvScreenTools.register();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                ClientCommandManager.literal("pvm")
                        .executes(c -> { status(); return 1; })
                        .then(ClientCommandManager.literal("on").executes(c -> { setEnabled(true); return 1; }))
                        .then(ClientCommandManager.literal("off").executes(c -> { setEnabled(false); return 1; }))
                        .then(ClientCommandManager.literal("app")
                                .executes(c -> { async(PvMusicMod::doApps); return 1; })
                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                        .suggests((c, b) -> {
                                            b.suggest("all");
                                            for (String n : appNames) b.suggest(n);
                                            return b.buildFuture();
                                        })
                                        .executes(c -> { setApp(StringArgumentType.getString(c, "name")); return 1; })))
                        .then(ClientCommandManager.literal("play")
                                .then(ClientCommandManager.argument("file", StringArgumentType.greedyString())
                                        .suggests((c, b) -> {
                                            for (String n : FilePlayer.files()) b.suggest(n);
                                            return b.buildFuture();
                                        })
                                        .executes(c -> { play(StringArgumentType.getString(c, "file")); return 1; })))
                        .then(ClientCommandManager.literal("list").executes(c -> { playList(); return 1; }))
                        .then(ClientCommandManager.literal("next").executes(c -> { MIXER.player().next(); return 1; }))
                        .then(ClientCommandManager.literal("stop").executes(c -> { stopFile(); return 1; }))
                        .then(ClientCommandManager.literal("loop")
                                .then(ClientCommandManager.literal("on").executes(c -> { loop(true); return 1; }))
                                .then(ClientCommandManager.literal("off").executes(c -> { loop(false); return 1; })))
                        .then(ClientCommandManager.literal("volume")
                                .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 500))
                                        .executes(c -> {
                                            Config.get().volume = IntegerArgumentType.getInteger(c, "percent");
                                            Config.save();
                                            PvMusicUi.sync();
                                            Chat.say("&7音量 &f" + Config.get().volume + "%");
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("duck")
                                .then(ClientCommandManager.literal("on").executes(c -> { duck(true); return 1; }))
                                .then(ClientCommandManager.literal("off").executes(c -> { duck(false); return 1; }))
                                .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
                                        .executes(c -> {
                                            Config.get().duckPercent = IntegerArgumentType.getInteger(c, "percent");
                                            duck(true);
                                            return 1;
                                        })))
        ));

        async(() -> {
            SystemAudioCapture.inputDevices();
            if (NativeTap.supported()) doApps0();
            // 采集程序启动后一直挂着（不发声音），这样之后 /pvm on 不用再启动它，不会卡
            MIXER.capture().start(Config.get().device, Config.get().app);
        });
    }

    static void setEnabled(boolean on) {
        if (mainThread == null) mainThread = Thread.currentThread();
        if (on) watchStalls();
        async(() -> doSetEnabled(on));
    }

    /** 打开设备/启动原生程序/枚举设备都可能卡住，不能占着游戏主线程 */
    static void async(Runnable r) {
        Thread t = new Thread(r, "Plasmo Voice Music Command");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void doSetEnabled(boolean on) {
        Config cfg = Config.get();
        if (on) {
            MIXER.player().stop();
            if (!MIXER.capture().running() && !MIXER.capture().start(cfg.device, cfg.app)) {
                cfg.enabled = false;
                PvMusicUi.sync();
                Chat.say("&c" + MIXER.capture().error() + "");
                return;
            }
            MIXER.capture().flush(); // 丢掉挂着期间攒的声音
            cfg.enabled = true;
            String n = MIXER.capture().openedName();
            Chat.say("&a已开启 &f" + n);
            if (!NativeTap.supported() && !SystemAudioCapture.looksLikeLoopback(n)) Chat.say("&c这不像回环设备，可能是麦克风");
        } else {
            cfg.enabled = false;
            Chat.say("&7已关闭");
        }
        PvMusicUi.sync();
    }

    /** 设备或软件变了: 重新挂载采集（不改变开/关） */
    static synchronized void restartCapture() {
        Config cfg = Config.get();
        if (!MIXER.capture().start(cfg.device, cfg.app)) {
            cfg.enabled = false;
            PvMusicUi.sync();
            Chat.say("&c" + MIXER.capture().error());
            return;
        }
        Chat.say("&7采集 &f" + MIXER.capture().openedName());
    }

    static volatile List<String> appNames = List.of();

    /** 列出系统里的音频软件（正在出声的排前面，绿色） */
    static void doApps0() {
        List<String> names = new java.util.ArrayList<>();
        for (String[] a : NativeTap.listApps()) names.add(!a[1].isBlank() ? a[1] : a[0]);
        appNames = names.stream().distinct().toList();
    }

    static void doApps() {
        if (!NativeTap.supported()) { Chat.say("&c只支持 macOS 和 Windows"); return; }
        List<String[]> apps = NativeTap.listApps();
        List<String> names = new java.util.ArrayList<>();
        List<String> shown = new java.util.ArrayList<>();
        for (String[] a : apps) {
            String label = !a[1].isBlank() ? a[1] : a[0];
            names.add(label);
            if (a[2].trim().equals("1")) shown.add("&a" + label);
        }
        appNames = names.stream().distinct().toList();
        String cur = Config.get().app.isBlank() ? "全部" : Config.get().app;
        Chat.say("&7当前 &f" + cur);
        if (shown.isEmpty()) Chat.say("&7现在没有软件在出声，名字可 Tab 补全");
        else Chat.say(String.join("&7, ", shown.stream().distinct().limit(10).toList()));
    }

    static void setApp(String arg) { setApp(arg, arg); }

    static void setApp(String arg, String label) {
        String v = arg.trim();
        Config cfg = Config.get();
        cfg.app = v.equalsIgnoreCase("all") || v.equals("全部") ? "" : v;
        cfg.appLabel = cfg.app.isBlank() ? "" : label;
        // 保存配置、提示和重建采集都不能阻塞界面线程。
        async(() -> {
            Config.save();
            Chat.say("&7软件 &f" + (cfg.app.isBlank() ? "全部音频" : cfg.appLabel));
            restartCapture();
        });
    }

    static void play(String file) {
        async(() -> playLocked(file, false));
    }

    static void playList() {
        async(() -> playLocked(null, true));
    }

    static void playListAt(int index) {
        async(() -> playLockedAt(index));
    }

    private static synchronized void playLocked(String file, boolean list) {
        boolean started;
        String name = null;
        if (list) started = MIXER.player().playList(List.copyOf(Config.get().playlist));
        else { name = MIXER.player().play(file, Config.get().loop); started = name != null; }
        if (!started) {
            Chat.say(list ? "&c播放列表是空的" : "&c没有这个文件 &7(放在 &fconfig/pvmusic/music&7)");
            return;
        }
        Config.get().enabled = false;
        net.minecraft.client.Minecraft.getInstance().execute(PvMusicUi::sync);
        Chat.say(list ? "&a顺序播放" : "&a播放 &f" + name);
    }

    private static synchronized void playLockedAt(int index) {
        List<String> list = List.copyOf(Config.get().playlist);
        if (index < 0 || index >= list.size()) return;
        if (MIXER.player().playListFrom(list, index)) {
            Config.get().enabled = false;
            net.minecraft.client.Minecraft.getInstance().execute(PvMusicUi::sync);
        }
    }

    static void stopFile() {
        MIXER.player().stop();
        Chat.say("&7已停止播放");
    }

    static void loop(boolean on) {
        Config.get().setMode(on ? "loop" : "sequential");
        Config.save();
        PvMusicUi.sync();
        Chat.say("&7循环 " + (on ? "&a开" : "&c关"));
    }

    static void duck(boolean on) {
        Config.get().duck = on;
        Config.save();
        PvMusicUi.sync();
        Chat.say("&7说话时压低音乐 " + (on ? "&a开 &f" + Config.get().duckPercent + "%" : "&c关"));
    }

    static void status() {
        Config cfg = Config.get();
        SystemAudioCapture cap = MIXER.capture();
        Chat.say((cap.running() ? "&a运行中 &f" + cap.openedName() : "&c未运行")
                + " &7音量 &f" + cfg.volume + "%");
    }
}
