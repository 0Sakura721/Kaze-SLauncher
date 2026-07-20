# Kaze-SLauncher

在 Android 设备上原生运行 Minecraft Java 版服务器的启动器。
**无需 Termux、无需 root** — 内置完整的 Linux 运行环境（proot + Ubuntu 24.04）。

## 功能特性

- **JAR 文件运行** — 选择并启动 Minecraft 服务器 JAR（Paper、Spigot、Vanilla、Forge 等）
- **核心下载** — 内置 Paper / Purpur / Fabric / Forge / NeoForge / Vanilla 等核心的版本列表与下载
- **Java 运行时管理** — 自动下载并安装 Java 8/11/17/21（apt 安装），国内镜像源加速
- **内置 Linux 环境** — 首次启动自动部署 proot + Ubuntu 24.04 base rootfs，无需额外安装 Termux
- **实时控制台** — 终端风格控制台，支持搜索/过滤、日志级别高亮、快速命令面板、复制日志、日志导出
- **在线玩家** — 自动解析日志，实时显示在线人数与玩家名
- **性能监控** — 实时 CPU（系统+进程级）、内存、TPS/MSPT（Spark/Paper/Purpur 多源检测）、磁盘 I/O、线程数、运行时间
- **启动健康检查** — 9 项启动前诊断 + 推荐配置建议
- **自动重启** — 服务器崩溃后可选自动重启，支持最大重启次数与冷却限制
- **服务器状态持久化** — 应用重启后保留状态信息，累计运行时间与重启统计
- **崩溃检测** — 进程退出后状态正确复位，不再卡在「运行中」
- **三种主题** — 明亮（白）、暗色（黑）、AMOLED 纯黑（省电）
- **前台服务** — 服务器后台运行，通知栏状态显示
- **服务器配置** — 内存分配、JVM 参数、端口等全面可配，端口写入 `server.properties` 生效
- **EULA 自动接受** — 首次启动自动写入 `eula.txt`
- **后台命令** — 前台服务可靠转发命令到服务器
- **优雅停止** — 先发 `stop` 命令保存存档，再按 PID 精确结束进程
- **崩溃重启保护** — 崩溃后自动重启，支持「最大重启次数」与「重启冷却」限制
- **游戏设置注入** — MOTD、游戏模式、难度、最大玩家、PVP、正版验证、白名单等，启动前写入 `server.properties`
- **备份与恢复** — 完整备份服务器目录（JAR/配置/world/插件）到带时间戳目录，支持手动/自动备份、恢复与删除
- **插件管理** — 扫描 plugins/ 目录，解析 plugin.yml / paper-plugin.yml / fabric.mod.json 元数据，支持启用/禁用/删除
- **玩家管理** — OP 列表、白名单、封禁列表管理
- **文件管理** — 浏览服务器文件、查看世界文件夹、查看崩溃报告、编辑配置文件
- **资源包管理** — 管理 resourcepacks/ 目录，配置强制资源包、下载 URL、SHA1 哈希
- **启动诊断** — 自动识别端口占用、JAR 损坏、Java 版本不符、内存不足等

## 运行原理（v0.10.0 起）

```
┌─────────────────────────────────────────┐
│            Kaze SLauncher (APK)        │
│  ┌───────────────────────────────────┐  │
│  │  assets/bundled/ (内置在 APK)      │  │
│  │  ├── proot-aarch64 / proot-armhf   │  │
│  │  └── ubuntu-base-24.04-*.tar.gz    │  │
│  └──────────────┬────────────────────┘  │
│                 │ 首次启动自动解压        │
│                 ▼                        │
│  ┌───────────────────────────────────┐  │
│  │  Linux 工作目录 (filesDir/linux/)  │  │
│  │  ├── proot (Android 本地进程)      │  │
│  │  └── rootfs/ (Ubuntu 24.04)       │  │
│  └──────────────┬────────────────────┘  │
│                 │ proot -0 -r rootfs     │
│                 ▼                        │
│  ┌───────────────────────────────────┐  │
│  │  Ubuntu 24.04 容器                 │  │
│  │  ├── apt install openjdk-*-jdk    │  │
│  │  └── java -jar server.jar         │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

**无需 Termux** — 应用自带 proot（进程模拟器）+ Ubuntu 24.04 base rootfs，完全在应用沙箱内运行。

## 兼容性

| 项目 | 支持 |
|------|------|
| 最低 Android | 8.0 (API 26) |
| 目标 Android | 16 (API 36) |
| CPU 架构 | `arm64-v8a`、`armeabi-v7a` |
| Java 版本 | 8 / 11 / 17 / 21（apt 安装，全版本可选） |

## 快速开始

1. 安装 APK（首次安装约需 200 MB 可用空间）
2. 打开应用 → 自动进入「环境部署」页面
3. 应用自动下载并部署：proot → Ubuntu 24.04 rootfs → Java
4. 在「配置」页选择或下载 Minecraft 服务器 JAR
5. 回到首页点击「启动服务器」

> 内置版 APK（首次启动零下载）：运行 `./gradlew :app:downloadBundledAssets :app:assembleDebug` 构建

## 构建

```bash
# 标准构建（不内置资源，运行时自动下载）
./gradlew :app:assembleDebug

# 完整内置版构建（首次启动零下载）
./gradlew :app:downloadBundledAssets :app:assembleDebug
```

或在 Android Studio 中打开项目直接构建。

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **架构**: ViewModel + StateFlow + DataStore
- **运行环境**: proot + Ubuntu 24.04（内置，无需 Termux）
- **包管理器**: apt（Ubuntu 源 + 国内镜像加速）
- **JRE**: openjdk-8/11/17/21（Ubuntu apt 包）
- **下载加速**: 35+ 镜像源自动测速选优

## License

MIT

## 鸣谢

本项目的开发在以下方面受惠于开源社区与工具链：

### 设计借鉴
- **Pterodactyl** — 进程分组管理、崩溃自动重启与冷却策略、资源监控面板、server.properties 变量注入
- **MCSManager** — 完整目录备份/恢复思路、启动前健康诊断、资源包管理
- **PufferPanel** — EULA 自动接受等首次启动处理
- **Operit** — proot + Ubuntu 24.04 容器化运行方案（APK 内置 Linux 环境）

### AI 开发协作
- **Operit** — 全栈 AI 开发平台，提供代码生成、GitHub 操作、终端执行、包管理等能力
- **Karpathy Guidelines** — 编码最佳实践参考（避免过度工程化、精准修改、定义可验证成功标准）
- **Vibe Coding Workflow**（tradecatlabs/vibe-coding-cn）— 编程工作流方法论（先找现成方案，不重复造轮子）
- **Cross-Platform Link Jumping** — 跨平台链接跳转与内容消费方法论

### 运行时组件
- **proot-me / Termux** — proot ARM 二进制来源（Android 进程模拟器）
- **Ubuntu** — 24.04 base rootfs（glibc Linux 运行环境）
- **Eclipse Temurin / Ubuntu apt** — OpenJDK 构建与分发
