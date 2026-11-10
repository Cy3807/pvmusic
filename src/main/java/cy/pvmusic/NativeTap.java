package cy.pvmusic;

import net.fabricmc.loader.api.FabricLoader;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * macOS 原生系统音频采集: 把 jar 里自带的 pvm-audiotap 释放到 config/pvmusic/ 并运行，
 * 它用 Core Audio 进程 tap 抓系统声音，并排除本游戏进程（所以游戏自己的声音不会被抓进去）。
 */
public final class NativeTap implements AutoCloseable {
    public static final String NAME = "系统音频";

    private Process process;
    private DataInputStream in;
    private int rate;

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    public static boolean supported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (windows()) return true; // Windows: PowerShell + C# 的 WASAPI 采集（进程回环不可用时自动退回整机回环）
        if (!os.contains("mac")) return false;
        try {
            String[] v = System.getProperty("os.version", "0").split("\\.");
            int major = Integer.parseInt(v[0]);
            int minor = v.length > 1 ? Integer.parseInt(v[1]) : 0;
            return major > 14 || (major == 14 && minor >= 2);
        } catch (Exception e) {
            return false;
        }
    }

    /** 启动并读完头部；失败抛异常，消息可直接显示 */
    private static Path extract() throws Exception {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("pvmusic");
        Files.createDirectories(dir);
        boolean win = windows();
        Path exe = dir.resolve(win ? "pvm-audiotap.ps1" : "pvm-audiotap");
        try (InputStream res = NativeTap.class.getResourceAsStream(win ? "/native/win/pvm-audiotap.ps1" : "/native/mac/pvm-audiotap")) {
            if (res == null) throw new IllegalStateException("模组里没有原生采集程序");
            byte[] bytes = res.readAllBytes();
            if (!Files.exists(exe) || Files.size(exe) != bytes.length || !java.util.Arrays.equals(Files.readAllBytes(exe), bytes)) {
                Path tmp = dir.resolve(exe.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                tmp.toFile().setExecutable(true, false);
                Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        exe.toFile().setExecutable(true, false);
        return exe;
    }

    /** 启动命令: macOS 直接运行；Windows 用 PowerShell 运行脚本 */
    private static java.util.List<String> command(Path exe) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        if (windows()) {
            cmd.addAll(java.util.List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-MTA",
                    "-ExecutionPolicy", "Bypass", "-File", exe.toString()));
        } else {
            cmd.add(exe.toString());
        }
        return cmd;
    }

    /** 启动并读完头部；失败抛异常，消息可直接显示。app: 只抓这些软件(逗号分隔)，空=全部（排除游戏） */
    public static NativeTap open(String app) throws Exception {
        Path exe = extract();
        NativeTap t = new NativeTap();
        java.util.List<String> cmd = command(exe);
        boolean win = windows();
        if (app != null && !app.isBlank()) {
            if (win) { cmd.add("-Include"); cmd.add(app.trim()); }
            else for (String a : app.split(",")) if (!a.isBlank()) { cmd.add("--include"); cmd.add(a.trim()); }
        }
        if (win) { cmd.add("-ExcludePid"); cmd.add(String.valueOf(ProcessHandle.current().pid())); }
        else cmd.add(String.valueOf(ProcessHandle.current().pid()));
        Path log = exe.resolveSibling("audiotap.log");
        t.process = new ProcessBuilder(cmd).redirectError(win ? ProcessBuilder.Redirect.to(log.toFile()) : ProcessBuilder.Redirect.DISCARD).start();
        t.in = new DataInputStream(t.process.getInputStream());
        byte[] head = new byte[12];
        try {
            t.in.readFully(head);
        } catch (Exception e) {
            Process failedProcess = t.process;
            t.close();
            boolean exited = failedProcess.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
            if (exited && failedProcess.exitValue() == 3) throw new IllegalStateException("没找到匹配的软件（它需要正在运行并出过声）");
            String why = "";
            if (win) {
                try {
                    String txt = Files.readString(log).trim();
                    if (!txt.isEmpty()) why = " " + txt.substring(Math.max(0, txt.length() - 160)).replace('\n', ' ');
                } catch (Exception ignored) { }
            }
            throw new IllegalStateException(win ? "原生采集启动失败 (config/pvmusic/audiotap.log)" + why : "原生采集启动失败（需要 macOS 14.2+，并允许录制系统音频）");
        }
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (head[0] != 'H' || head[1] != 'P' || head[2] != 'A' || head[3] != '1') {
            t.close();
            throw new IllegalStateException("原生采集程序输出异常");
        }
        t.rate = b.getInt(4);
        return t;
    }

    public int rate() { return rate; }

    /** 列出系统里的音频进程: [包名, 名字, 是否正在出声] */
    public static java.util.List<String[]> listApps() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            Path exe = extract();
            java.util.List<String> lc = command(exe);
            lc.add(windows() ? "-List" : "--list");
            Process p = new ProcessBuilder(lc).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String text = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String[] f = line.split("\t", -1);
                if (f.length >= 3) out.add(f);
            }
        } catch (Exception e) {
            PvMusicMod.LOGGER.warn("列出音频软件失败", e);
        }
        return out;
    }


    /** 读满 buf（立体声 s16le） */
    public void readFully(byte[] buf) throws java.io.IOException {
        in.readFully(buf);
    }

    @Override
    public void close() {
        if (process != null) process.destroy();
        process = null;
    }
}
