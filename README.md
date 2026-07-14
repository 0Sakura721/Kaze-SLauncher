# Minecraft Server Launcher (MCServer Launcher)

在 Android 设备上运行 Minecraft Java 版服务器的原生启动器。

## 功能特性

-   **JAR 文件运行** — 选择并启动 Minecraft 服务器 JAR（Paper、Spigot、Vanilla、Forge 等）
-   **核心下载** — 内置 Paper / Purpur / Fabric / Forge / NeoForge / Vanilla 等核心的版本列表与下载
-   **Java 运行时管理** — 自动检测架构并下载适配的 JRE（ARM64 v7a / v8a），支持国内镜像加速
-   **实时控制台** — 终端风格控制台，支持搜索/过滤、日志级别高亮、快速命令面板、复制日志、**日志导出**
-   **在线玩家** — 自动解析日志，实时显示在线人数与玩家名
-   **性能监控** — 实时 CPU（系统+进程级）、内存、TPS/MSPT（Spark/Paper/Purpur 多源检测）、磁盘 I/O、线程数、运行时间（仿 Pterodactyl 资源面板）
-   **启动健康检查** — 9 项启动前诊断（Termux 环境、JAR 完整性、端口占用、磁盘空间、内存、EULA、Java 兼容性、目录权限、系统资源评估）+ 推荐配置建议（仿 MCSManager 诊断面板）
-   **自动重启** — 服务器崩溃后可选自动重启（配置页开关），支持最大重启次数与冷却限制
-   **服务器状态持久化** — 应用重启后保留状态信息，累计运行时间与重启统计（仿 Pterodactyl）
-   **崩溃检测** — 进程退出后状态正确复位，不再卡在「运行中」
-   **三种主题** — 明亮（白）、暗色（黑）、AMOLED 纯黑（省电）
-   **前台服务** — 服务器后台运行，通知栏状态显示
-   **服务器配置** — 内存分配、JVM 参数、端口等全面可配，端口写入 `server.properties` 生效
-   **EULA 自动接受** — 首次启动自动写入 `eula.txt`（eula=true），避免服务器因未接受协议而直接退出
-   **后台命令** — 应用切到后台 / 锁屏后，控制台命令仍可通过前台服务可靠转发到服务器
-   **优雅停止** — 停止时先发送 `stop` 命令让服务器保存存档，再按 PID 精确结束进程（兼容任意 JAR 名，如 Paper / Spigot / Forge，不再写死 `server.jar`）；Java 直接以命名管道为 stdin，结束即收 EOF 自然退出，无残留喂数据进程
-   **崩溃重启保护** — 崩溃后自动重启，支持「最大重启次数」与「重启冷却」限制，避免崩溃循环刷屏（仿 Pterodactyl restart policy）
-   **游戏设置注入** — 在配置页直接设置 MOTD、游戏模式、难度、最大玩家、PVP、正版验证、白名单、出生保护、视距等，启动前写入 `server.properties`（仿 Pterodactyl 变量注入）
-   **备份与恢复** — 完整备份整个服务器目录（JAR / 配置 / world / 插件）到带时间戳目录，支持手动备份、停止时自动备份、恢复与删除（仿 MCSManager）
-   **插件管理** — 扫描 plugins/ 目录，解析 plugin.yml / paper-plugin.yml / fabric.mod.json 元数据，支持启用/禁用/删除
-   **玩家管理** — OP 列表、白名单、封禁列表管理，支持添加/移除操作
-   **文件管理** — 浏览服务器文件、查看世界文件夹、查看崩溃报告、编辑配置文件
-   **资源包管理** — 管理 resourcepacks/ 目录，配置强制资源包、下载 URL、SHA1 哈希（仿 MCSManager）
-   **启动诊断** — 自动识别端口占用、JAR 损坏、Java 版本不符、内存不足等常见启动失败原因并给出提示

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
3. 在「配置」页选择 Minecraft 服务器 JAR 文件，按需调整内存 / 端口 / 游戏设置 / 自动重启 / 停止时备份
4. 回到首页点击「启动服务器」
5. 在「控制台」查看日志、向服务器输入命令
6. 在「备份」页手动创建备份、查看/恢复/删除历史备份

## 鸣谢与借鉴

本项目在设计与实现上参考了成熟的开源游戏服务器面板：

-   **Pterodactyl** — 进程分组管理、崩溃自动重启与冷却策略、资源监控面板、server.properties 变量注入
-   **MCSManager** — 完整目录备份 / 恢复思路、启动前健康诊断、资源包管理、下载管理
-   **PufferPanel** — EULA 自动接受等首次启动处理

## License

MIT
