# Kaze SLauncher

> 在 Android 手机上部署和管理 Minecraft Java 版服务端。内置 PRoot 与 Ubuntu 24.04 运行环境，无需 Root 或单独安装 Termux。

[![CI](https://github.com/0Sakura721/Kaze-SLauncher/actions/workflows/ci.yml/badge.svg)](https://github.com/0Sakura721/Kaze-SLauncher/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-6750A4?logo=jetpackcompose)
![minSdk](https://img.shields.io/badge/minSdk-27-orange)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

**当前版本：** `v0.5.0-beta.1`（预发布） · [下载 APK](https://github.com/0Sakura721/Kaze-SLauncher/releases) · [更新日志](CHANGELOG.md)

> 主分支当前不将液态玻璃主题接入构建。需要完整液态玻璃实现，请查看 [`liquidglassver` 分支说明](LIQUIDGLASS_BRANCH.md)。
>
> 开发者开始修改前，建议先阅读[维护指南](docs/MAINTENANCE.md)，了解项目中的构建、测试和发布注意事项。

## 目录

- [项目简介](#项目简介)
- [功能概览](#功能概览)
- [快速开始](#快速开始)
- [安装包选择与限制](#安装包选择与限制)
- [开发与构建](#开发与构建)
- [项目结构](#项目结构)
- [文档索引](#文档索引)
- [许可证与致谢](#许可证与致谢)
- [赞助支持](#赞助支持)
- [免责声明](#免责声明)

## 项目简介

Kaze SLauncher 是一款面向 Android 的 Minecraft Java 版服务端管理应用。它将 Linux 运行环境、Java 安装、服务端下载、实例管理和控制台操作整合在一个应用中，适合希望使用手机部署或维护 Minecraft 服务端的用户。

- **无需 Root：** 通过 PRoot 在 Android 上运行 Ubuntu 24.04 用户空间环境。
- **无需单独安装 Termux：** PRoot 与基础 rootfs 随 APK 提供。
- **多实例管理：** 为不同服务端实例分别管理配置、端口、运行状态和备份。
- **可视化操作：** 通过 Compose 界面完成创建、启停、控制台管理和日志排查。

首次部署环境、安装 Java 或下载服务端时需要网络连接；已准备好的实例在满足运行条件时可离线启动。

## 功能概览

### 服务端管理

- 支持 Vanilla、Paper、Purpur、Spigot、Fabric、Forge、NeoForge，以及导入自定义服务端 JAR。
- 创建实例时配置游戏版本、核心构建或加载器版本、内存、端口、MOTD 和服务器属性。
- 支持多实例管理、置顶排序、启动、优雅停止与安全重启。
- 支持实例备份、恢复、导入和导出，并可配置停服自动备份。
- 可通过 Modrinth 搜索、安装和管理插件或模组。

### 运行环境

- APK 内置 PRoot 与 Ubuntu 24.04 基础环境，首次部署时初始化运行环境。
- 根据 Minecraft 版本推断合适的 Java 版本，并允许在创建流程中调整。
- 提供 EULA 确认流程；用户须先同意 Minecraft EULA 才能运行服务端。
- 服务端运行期间使用前台服务和唤醒锁降低后台中断风险。实际保活效果仍受 Android 厂商的后台管理策略影响。

### 控制台与诊断

- 实时查看服务端输出，支持日志着色、自动跟随、关键词搜索和级别过滤。
- 发送服务端命令，并管理快捷命令。
- 解析玩家列表与进出服事件，提供常用玩家管理命令入口。
- 支持日志复制、导出及启动问题排查。
- 提供 AI 辅助诊断与文件操作能力；涉及写入或命令执行的操作按应用内确认流程执行。使用联网 AI 服务时，请留意发送的数据与服务商的隐私政策。

### 界面与更新

- 基于 Kotlin、Jetpack Compose 与 Material 3 Expressive 的 Android 界面。
- 支持浅色/深色外观、动态取色及相关外观设置。
- 提供应用内更新检查与下载校验流程。

## 快速开始

1. 打开 [GitHub Releases](https://github.com/0Sakura721/Kaze-SLauncher/releases)，下载适合设备架构的 APK。
2. 安装并打开应用，按提示部署 Linux 运行环境。
3. 进入「服务端」页面，选择「新建」。
4. 选择服务端核心与版本，设置内存、端口及其他参数，并确认已阅读且同意 Minecraft EULA。
5. 等待核心下载和创建完成，然后启动实例。
6. 在「控制台」查看启动输出；若启动失败，请先查看启动日志和[使用说明中的常见问题](docs/使用说明.md#常见问题)。

首次部署环境、安装 Java、下载服务端核心或获取更新时需要网络连接。

## 安装包选择与限制

| 架构 | 适用设备 | 说明 |
|---|---|---|
| `arm64-v8a` | 大多数现代 Android 手机和平板 | 推荐优先选择 |
| `armeabi-v7a` | 部分 32 位 ARM 设备 | 标记为 experimental，真机验证较少 |

当前不再发布 `universal` 包。请以 Releases 页面实际提供的文件为准。

### 已知限制

- **建议使用真实 ARM 设备运行服务端。** 部分 x86_64 模拟器无法正确执行 PRoot，可能出现崩溃；模拟器中的界面可用性不代表服务端运行环境可用。
- **Forge / NeoForge 安装流程仍需更多真实设备验证。** 若安装失败，请保存控制台输出以便排查。
- 首次部署、安装 Java、下载核心、插件或模组需要网络；具体可用性也取决于对应上游服务。
- Android 厂商的省电策略可能影响后台运行。请按系统提示设置通知和电池优化权限。

## 开发与构建

### 环境要求

- JDK 17 或更高版本
- Android SDK（项目当前使用 `compileSdk 35`）
- 可访问项目所需依赖仓库的网络环境

### 构建 APK

在仓库根目录执行：

```bash
# ARM64 Debug APK
./gradlew assembleArm64Debug

# ARM64 Release APK
./gradlew assembleArm64Release

# ARMHF Release APK（实验性）
./gradlew assembleArmhfRelease
```

构建产物位于 `app/build/outputs/apk/` 下对应 flavor 的目录中。具体任务名称与产物文件名以当前 Gradle 配置为准。

Release 包需要正式签名凭据。项目不会在缺少 Release 签名配置时自动回退到 Debug 密钥；签名配置与发布检查请参阅[发布签名手册](docs/RELEASE-SIGNING.md)。

### 运行测试与生成界面截图

```bash
# ARM64 Debug 单元测试
./gradlew testArm64DebugUnitTest

# 生成 Robolectric / Roborazzi 界面截图
./gradlew recordRoborazziArm64Debug
```

测试与截图任务是否成功，以 Gradle 输出和 CI 结果为准。截图通常生成在构建目录中；具体位置请查看任务输出及相关维护文档。

## 项目结构

```text
app/src/main/java/com/kaze/newage/
├── ui/          # Compose 界面、主题、组件和页面
├── core/        # 环境、Java、服务端、下载、控制台等核心逻辑
├── data/        # 数据模型、实例存储与偏好设置
└── util/        # 下载、解压和存储等通用工具

app/src/test/    # 单元测试与界面截图测试
docs/            # 使用、维护、发布及设计文档
.github/workflows/ # CI、构建与发布工作流
```

## 文档索引

| 文档 | 内容 |
|---|---|
| [使用说明](docs/使用说明.md) | 安装、创建实例、启停、控制台、备份、设置与常见问题 |
| [维护指南](docs/MAINTENANCE.md) | 项目维护、构建测试注意事项和已知工程陷阱 |
| [项目计划](docs/PLAN.md) | 项目计划与待办事项 |
| [发布签名手册](docs/RELEASE-SIGNING.md) | Release 签名、密钥管理和发版前检查 |
| [液态玻璃分支说明](LIQUIDGLASS_BRANCH.md) | 主分支与 `liquidglassver` 分支的区别 |
| [第三方声明](THIRD_PARTY_NOTICES.md) | 第三方项目、依赖、许可证及来源说明 |
| [界面设计稿说明](docs/m3e/README.md) | M3E Canvas 设计文件、生成脚本与截图说明 |
| [更新日志](CHANGELOG.md) | 按版本记录的功能变化与修复 |

## 许可证与致谢

本项目以 **GPL-3.0** 许可证发布，详见 [LICENSE](LICENSE)。第三方代码、依赖和运行时组件的来源与许可信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

项目在架构、界面或运行环境实现方面参考或复用了多个开源项目，包括：

- [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher)
- [ZalithLauncher 2](https://github.com/ZalithLauncher/ZalithLauncher2)
- [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)
- [BiliPai](https://github.com/jay3-yy/BiliPai)
- [Miuix](https://github.com/Miuix-Kotlin-Multiplatform/Miuix)
- [M3E Canvas](https://github.com/lnkiai/m3e-canvas)
- [oonid/pr](https://github.com/oonid/pr)

具体采用内容和对应许可证以第三方声明及各上游项目为准。

## 赞助支持

如果 Kaze SLauncher 对你有帮助，欢迎请作者喝杯奶茶。

| 支付宝 | 微信 |
|:---:|:---:|
| ![支付宝赞助二维码](docs/images/alipay.png) | ![微信赞助二维码](docs/images/wechat.png) |

## 免责声明

- 使用 Minecraft 服务端前，请阅读并遵守 [Minecraft EULA](https://aka.ms/MinecraftEULA) 及相关条款。
- 本项目与 Mojang Studios 或 Microsoft 无隶属关系，也不代表其认可或背书。
- Minecraft 及相关标识归其各自权利人所有。
- 请在遵守当地法律、上游软件许可证和服务条款的前提下使用本项目。使用者应自行备份重要世界数据，并承担使用本软件的相应风险。
