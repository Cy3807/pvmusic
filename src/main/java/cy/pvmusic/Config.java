package cy.pvmusic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** 本机配置 config/pvmusic.json */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pvmusic.json");
    private static volatile Config instance;

    static {
        // 改名前 (Headset) 的配置文件和音乐文件夹: 第一次启动时搬过来，用户的音乐不丢
        try {
            Path dir = FabricLoader.getInstance().getConfigDir();
            if (!java.nio.file.Files.exists(FILE) && java.nio.file.Files.exists(dir.resolve("headset.json")))
                java.nio.file.Files.move(dir.resolve("headset.json"), FILE);
            if (!java.nio.file.Files.exists(dir.resolve("pvmusic")) && java.nio.file.Files.isDirectory(dir.resolve("headset")))
                java.nio.file.Files.move(dir.resolve("headset"), dir.resolve("pvmusic"));
        } catch (Exception ignored) { }
    }

    /** 是否把电脑音频发出去。不保存: 每次启动都是关的，手动打开才发（采集程序启动后一直挂着，只是不发） */
    public transient volatile boolean enabled = false;
    /** 采集设备名（子串匹配，不分大小写）；空 = 自动找回环设备 */
    public String device = "";
    /** 电脑音频音量 % */
    public int volume = 100;
    /** 说话时自动压低电脑音频 */
    public boolean duck = true;
    /** 压低后的音量 % */
    public int duckPercent = 30;
    /** 每个音乐包重复发一次（对方丢包时用另一份补上，重复的会被丢弃） */
    public boolean redundant = true;
    /** 仅在发送线程及时的情况下补发一次；默认关闭。 */
    public boolean adaptiveRedundancy = false;
    /** macOS: 只抓这些软件(逗号分隔，包名或软件名片段)，空 = 全部（排除游戏） */
    public String app = "";
    public String appLabel = "";
    /** 文件播放音量 %（别人听到的） */
    public int fileVolume = 100;
    /** 文件本地音量 %（自己听到的） */
    public int localVolume = 100;
    /** 播放列表（文件名，按顺序） */
    public java.util.List<String> playlist = new java.util.ArrayList<>();
    /** 文件循环播放 */
    public boolean loop = false; // 旧配置迁移与 /pvm loop 命令兼容
    /** null 表示尚未从旧版 loop 设置迁移。 */
    public volatile String playbackMode;

    public String mode() {
        String mode = playbackMode;
        if ("loop".equals(mode) || "shuffle".equals(mode) || "sequential".equals(mode)) return mode;
        return loop ? "loop" : "sequential";
    }
    public void setMode(String mode) {
        playbackMode = mode;
        loop = "loop".equals(mode);
    }
    /** 音乐发送的 Opus 码率（独立通路用“音频”模式，不受服务器语音模式影响） */
    public int musicBitrate = 96000;
    /** 旧版“加大接收缓冲”改过 Plasmo Voice 抖动缓冲的原值；启动时若有就恢复一次并清掉 */
    public int savedJitter = -1;
    public boolean savedAdaptive = false;
    public int savedAlBuffers = 5;
    /** 判定“正在说话”的麦克风音量（16 位采样的 RMS） */
    public int speakThreshold = 500;

    public static Config get() {
        Config current = instance;
        if (current != null) return current;
        synchronized (Config.class) {
            if (instance == null) {
                try {
                    if (Files.exists(FILE)) instance = GSON.fromJson(Files.readString(FILE), Config.class);
                } catch (Exception e) {
                    PvMusicMod.LOGGER.warn("读取配置失败，使用默认值", e);
                }
                if (instance == null) instance = new Config();
            }
            return instance;
        }
    }

    public static synchronized void save() {
        try {
            Files.writeString(FILE, GSON.toJson(get()));
        } catch (Exception e) {
            PvMusicMod.LOGGER.warn("保存配置失败", e);
        }
    }
}
