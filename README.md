# Kaze SLauncher

> 在 Android 手机上运行 **Minecraft Java 版服务端**的启动器。应用内置基于 proot 的 Ubuntu 24.04 运行环境，无需 Root，也不需要单独安装 Termux。

[![CI](https://github.com/0Sakura721/Kaze-SLauncher/actions/workflows/ci.yml/badge.svg)](https://github.com/0Sakura721/Kaze-SLauncher/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-green)
![minSdk](https://img.shields.io/badge/minSdk-27-orange)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

当前开发版本：**v0.5.0-beta.1**。请从 [GitHub Releases](https://github.com/0Sakura721/Kaze-SLauncher/releases) 获取安装包；版本变更见 [CHANGELOG.md](CHANGELOG.md)。

> **关于液态玻璃：** 液态玻璃主题已从主线构建中移除，但相关代码仍保留在仓库中。需要该主题时，请查看 [`liquidglassver` 分支说明](LIQUIDGLASS_BRANCH.md)，不要把该分支直接合并回主线。

## 文档导航

| 想了解 | 文档 |
|---|---|
| 安装、操作、备份、AI 数据说明与常见问题 | [用户使用说明](docs/使用说明.md) |
| 代码维护、测试、CI 与历史踩坑记录 | [维护笔记](docs/MAINTENANCE.md) |
| 发布签名、密钥管理与发版检查 | [发布签名手册](docs/RELEASE-SIGNING.md) |
| 初始设计决策与架构演变（历史记录） | [项目计划](docs/PLAN.md) |
| 第三方代码、素材与许可证清单 | [第三方声明](THIRD_PARTY_NOTICES.md) |
| 液态玻璃分支的用途与使用方式 | [分支说明](LIQUIDGLASS_BRANCH.md) |

## 快速开始

1. 从 [Releases](https://github.com/0Sakura721/Kaze-SLauncher/releases) 下载与设备架构相符的 APK 并安装。
2. 打开应用，在主页选择「部署」初始化 Linux 运行环境。首次部署需要联网，rootfs 已包含在 APK 中，无需再下载大型系统镜像。
3. 进入「服务端」→「新建」，选择核心与版本，设置内存、端口等参数，并明确勾选同意 Minecraft EULA。
4. 下载创建完成后启动服务端，在「控制台」查看实时日志或输入命令。首次启动会按应用流程处理 EULA 并重新启动服务端。

**如何选择 APK**

- **64 位 ARM 手机：** 选择 `arm64-v8a`。
- **32 位 ARM 设备：** 可尝试 `armeabi-v7a`，但该架构的真机验证较少，发布包会标记为 experimental。
- **Universal：** 当前发行不提供该包。

详细的页面操作与故障排查见 [用户使用说明](docs/使用说明.md)。

## 主要功能

### 服务端管理

- 支持 Vanilla、Paper、Purpur、Spigot、Fabric、Forge、NeoForge，以及导入自定义服务端 JAR。
- 多实例独立管理，各自拥有目录、端口、内存与运行状态。
- 可安装 Java、管理 EULA 和 `server.properties`，并配置 JVM 附加参数与 `nogui`。
- 支持插件/模组搜索安装、启停、实例备份与恢复，以及通过系统文件选择器导入/导出。
- 支持优雅停止、一键重启、实例置顶、连接地址复制和停服自动备份。

### 运行环境与控制台

- APK 内置 Ubuntu 24.04 rootfs 和修补版 proot；不需要 Root 或 Termux。
- 按 Minecraft 版本推断 Java 版本：1.8–1.16.5 使用 Java 8，1.17–1.20.4 使用 Java 17，1.20.5+ 及 24.x–25.x 使用 Java 21，26.x 起使用 Java 25。快照版会尝试按年份推断；自定义核心还会尝试按 JAR 的 class 文件版本推断。
- 控制台支持实时日志、命令输入、日志筛选与搜索、快捷命令、玩家管理以及 CPU/内存监控。
- 应用内更新支持完整 APK 与增量更新；下载后会验证 SHA-256，失败时可回退到完整包。

### 可选 AI 助手

- 可协助诊断日志、查阅网页、读取实例文件、生成文件修改建议和执行控制台命令。
- 文件写入需要确认；敏感文件读取有额外确认，命令执行权限可分级设置。
- **隐私提示：** 你配置的模型或搜索服务可能收到实例状态、近期控制台日志（其中可能包含玩家聊天）、经你确认读取的文件内容及搜索摘要。配置前请阅读 [AI 助手数据说明](docs/使用说明.md#12-ai-助手会发什么数据--要什么权限--key-存哪)。

## 开发与构建

### 环境要求

- JDK 17 或更高版本。
- Android SDK，包含 Android 35 平台与对应 Build Tools。
- 使用仓库内的 Gradle Wrapper，无需单独安装 Gradle。

### 常用命令

~~~bash
# arm64-v8a Debug APK
./gradlew :app:assembleArm64Debug

# armeabi-v7a Debug APK（experimental）
./gradlew :app:assembleArmhfDebug

# 单元测试
./gradlew :app:testArm64DebugUnitTest

# 生成 Robolectric / Roborazzi 界面截图
./gradlew :app:recordRoborazziArm64Debug

# Release APK（必须配置正式签名密钥）
./gradlew :app:assembleArm64Release :app:assembleArmhfRelease
~~~

APK 输出位于 `app/build/outputs/apk/` 下对应的架构与构建类型目录。Release 签名从本地 `local.properties` 或 CI 环境变量读取；**缺少签名凭据时不会回退使用 debug 密钥**。发布前请核验 APK 签名，具体步骤见 [发布签名手册](docs/RELEASE-SIGNING.md)。

### 代码结构

~~~text
app/src/main/java/com/kaze/newage/
├── ui/       # Jetpack Compose 页面、组件、主题与导航
├── core/     # 环境部署、Java 管理、服务端生命周期、下载、更新与 AI
├── data/     # 实例、设置与数据模型
└── util/     # 下载、解压、存储等工具

app/src/test/          # 单元测试与界面截图测试
.github/workflows/     # CI、APK 构建与 Release 流水线
~~~

CI 会运行单元测试和界面截图测试；Release 流水线还会检查正式 APK 的签名。涉及真机权限、后台保活、设备兼容性与部分服务端安装器的行为，仍需在真实 ARM 设备上验证。

## 已知限制

- **x86_64 模拟器不适合运行服务端。** proot 的运行依赖与常见模拟器的 ARM 翻译层不兼容；界面测试可用，但部署环境与实际服务端运行请优先使用真实 ARM 设备。
- Forge / NeoForge 的 `--installServer` 路径尚未在所有设备和版本上完成真机验证；失败时请查看安装器完整输出。
- 首次部署、安装 Java、下载服务端核心及附加内容需要网络。
- Spigot 使用社区 CDN，可能无法获得与其他核心相同的官方哈希校验信息。
- `armeabi-v7a` 包的设备覆盖验证少于 `arm64-v8a`。

## 许可证与致谢

项目整体采用 **GPL-3.0**，见 [LICENSE](LICENSE)。第三方代码、素材、二进制及运行时组件清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本项目参考或改编了 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher)、[ZalithLauncher 2](https://github.com/ZalithLauncher/ZalithLauncher2)、[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)、[Miuix](https://github.com/Miuix-Kotlin-Multiplatform/Miuix) 等项目。具体来源与许可证以第三方声明及对应上游许可证文本为准。

## ☕ 赞助支持

如果这个项目对你有帮助，可以请我喝杯奶茶 ☕

| 支付宝 | 微信 |
|:------:|:----:|
| ![支付宝](docs/images/alipay.png) | ![微信](docs/images/wechat.png) |

## 免责声明

- 使用 Minecraft 服务端前，请阅读并遵守 [Minecraft EULA](https://aka.ms/MinecraftEULA)。
- 本项目与 Mojang Studios 无隶属或背书关系；Minecraft 是其各自权利人拥有的商标。
- 请在重要操作前备份世界数据。因设备兼容性、系统后台限制、网络或误操作造成的数据损失，由使用者自行承担。
