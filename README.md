# Minecraft Server Launcher (MCServer Launcher)

在 Android 设备上运行 Minecraft Java 版服务器的原生启动器。

## 功能特性

-   **JAR 文件运行** — 选择并启动 Minecraft 服务器 JAR（Paper、Spigot、Vanilla 等）
-   **Java 运行时管理** — 自动检测架构并下载适配的 JRE（ARM64 v7a / v8a）
-   **实时控制台** — 终端风格控制台，支持命令输入（`stop` / `op` / `gamemode` 等）
-   **在线玩家** — 自动解析日志，实时显示在线人数与玩家名
-   **自动重启** — 服务器崩溃后可选自动重启（配置页开关）
-   **崩溃检测** — 进程退出后状态正确复位，不再卡在「运行中」
-   **三种主题** — 明亮（白）、暗色（黑）、AMOLED 纯黑（省电）
-   **前台服务** — 服务器后台运行，通知栏状态显示
-   **服务器配置** — 内存分配、JVM 参数、端口等全面可配，端口写入 `server.properties` 生效

## 重要前提：需要 Termux

本应用借助 **Termux** 提供的 Linux 运行环境来启动 Java 进程（绕过 Android SELinux 限制），因此必须额外安装 Termux：

1. 从 **F-Droid** 安装 Termux：<https://f-droid.org/packages/com.termux/>
2. 在 Termux 中执行 `pkg update -y && pkg install openjdk-21 -y` 安装 Java
3. 应用内首页的「Termux 环境」卡片会显示状态：未安装 / Java 未安装 / 已就绪

> 也可直接在应用内点击「安装 Java」按钮，由应用调用 Termux 完成安装。

## 兼容性

| 项目 | 支持 |
|------|------|
| 最低 Android | 8.0 (API 26) |
| 目标 Android | 16 (API 36) |
| CPU 架构 | `arm64-v8a`、`armeabi-v7a` |
| Java 版本 | JRE 21 (Adoptium / Termux openjdk-21) |

## 构建

```bash
./gradlew assembleDebug
```

或在 Android Studio 中打开项目直接构建。

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **架构**: ViewModel + StateFlow + DataStore
- **运行环境**: Termux RunCommandService + 命名管道（控制台命令）
- **JRE**: Eclipse Temurin (Adoptium) ARM 构建 / Termux openjdk-21

## 使用方式

1. 安装应用后，先从 F-Droid 安装 Termux 并在其中装好 Java（详见上方「重要前提」）
2. 在「设置」页确认 Java 运行时与 Termux 环境状态为「已就绪」
3. 在「配置」页选择 Minecraft 服务器 JAR 文件，按需调整内存 / 端口 / 自动重启
4. 回到首页点击「启动服务器」
5. 在「控制台」查看日志、向服务器输入命令

## License

MIT
