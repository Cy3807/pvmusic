# Plasmo Voice Music

Minecraft 1.21.11 / Fabric 客户端模组（Plasmo Voice 的附加模组）：把电脑正在播放的声音、或本地音乐文件，通过 Plasmo Voice 发给附近的玩家；可以和说话同时进行，且不受 Plasmo Voice 降噪影响。

## 功能

- 系统声音采集：macOS 14.2+ 用自带的 Core Audio 进程 tap（`native/mac/audiotap.swift`，排除游戏进程）；Windows 用 WASAPI 回环（`pvm-audiotap.ps1`）；也可选择任意回环输入设备。
- 播放文件：`config/pvmusic/music/` 下的 wav / aiff / au / ogg / mp3 / flac / m4a，支持播放列表、循环、封面显示。
- 说话时自动压低音乐、独立音乐通路（麦克风没在发送时不受按键说话/语音激活影响）、音量最高 500%。
- 设置在 Plasmo Voice 设置界面的「音乐」页；命令 `/pvm on|off|app|play|stop|list|next|loop|volume|duck`。

## 构建

需要 Java 21。

```bash
./gradlew build    # → build/libs/pvmusic-<版本>.jar
```

运行需要 Fabric Loader、Fabric API 和 Plasmo Voice（编译依赖 `su.plo.voice.api:client`，来自 repo.plasmoverse.com）。macOS 采集程序重新编译：`native/mac/build.sh`（需要 Xcode 命令行工具）。

## 许可证

本项目 MIT（见 `LICENSE`）。打包的第三方库及其许可证见 `src/main/resources/META-INF/pvmusic-licenses/`。本项目不隶属于 Plasmo Voice。
