# Kaze SLauncher

> 在 Android 上运行 **Minecraft Java 服务端**的启动器 —— 自包含（proot + Ubuntu 24.04），无需 Root、无需 Termux，
> 一条链路走完「环境 → Java → 服务端 → EULA → 实时控制台」。

[![CI](https://github.com/0Sakura721/Kaze-SLauncher/actions/workflows/ci.yml/badge.svg)](https://github.com/0Sakura721/Kaze-SLauncher/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-green)
![minSdk](https://img.shields.io/badge/minSdk-27-orange)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

当前版本 **v0.3.2**（[下载](https://github.com/0Sakura721/Kaze-SLauncher/releases)） · 变更见 [CHANGELOG](CHANGELOG.md)
> 液态玻璃已从构建中移除（代码保留）；想要**可用的液态玻璃版本**请用分支 [`liquidglassver`](https://github.com/0Sakura721/Kaze-SLauncher/tree/liquidglassver)（说明见 [LIQUIDGLASS_BRANCH.md](LIQUIDGLASS_BRANCH.md)）。
>
> 动手改之前建议先看 [维护笔记](docs/MAINTENANCE.md) —— 记的是那些"只有真跑一次才会
> 暴露、而且往往以'静默成功'的样子出现"的坑（CI 流水线 / 测试基建 / 增量补丁格式 / 本机工具链）。

---

## ✨ 特性

### 服务端
- 📦 **八种核心**：原版 Vanilla、Paper、Purpur、Spigot、Fabric、Forge、NeoForge，以及**导入自己的 jar**
- 🧩 **安装时可选项**：核心构建（Paper build）/ 加载器（Fabric loader）版本、实例名、内存、
  端口、最大玩家数、正版验证、默认游戏模式、EULA 显式同意
- 🔎 **版本列表**：按类型分档（正式版 / 快照版 / 远古测试版 / 远古预览版）+ 实时搜索
- 🗂️ **多实例**：各自独立的目录、内存、端口与运行状态，可同时运行
- 💾 **备份 / 恢复 / 导入导出**：按实例隔离的备份目录，支持 SAF 导出到任意位置
- 🔌 **插件与模组**：从 Modrinth 搜索安装，可单独启停

### 运行环境
- 🐧 **自包含 Linux 环境**：proot + Ubuntu 24.04 rootfs 随 APK 内置，**不需要下载几百 MB 的 rootfs**
  （修补版 proot 绕过 targetSdk 29+ 的 W^X 与 zygote seccomp 限制，无需 Root；部署时会联网初始化 apt 源索引）
- ☕ **Java 自动安装**：按 MC 版本推断并从 apt 安装 —— 1.8–1.16.5→8 / 1.17–1.20.4→17 /
  ≥1.20.5→21 / 26.x→25；快照版按年份推断；导入的自定义核心按 jar 内 class 文件版本推断
- ⚖️ **EULA 处理**：安装时显式勾选同意；首启自动生成并改写 `eula.txt`，全程可视化三步指示
- 🛡️ **前台保活**：服务端运行期间常驻通知保活，全部停止后自动退出；首次启动自动申请忽略电池优化

### 界面
- 🎨 **Material 3 Expressive 界面**：版式由 [M3E Canvas](https://github.com/lnkiai/m3e-canvas) 设计并生成提示词
  （设计稿与画布分享链接见 [docs/m3e](docs/m3e/)）；卡片 20dp 圆角、相连按钮组、波浪形进度条、
  可点组件带涟漪与轻微缩小反馈，动效走官方 spatial/effects 两组弹簧
- 🔵 **形状变化加载指示器**：签名元素，就是官方那个会变形的加载指示器 —— 7 个形状每 650ms 变一次，
  同时承担服务状态：运行中常速变形、启动中加速、停止时定格成单个形状
- 🌗 **两套外观**：简洁面板（默认）/ 液态玻璃（玻璃卡片 + 镜面高光 + 柔光斑），跟随系统深浅色，
  支持 AMOLED 纯黑、自定义种子色与取色风格
- 🖥️ **实时控制台**：逐行着色、自动跟随、`\r` 进度行原地刷新、命令输入（stop / op / say…）、
  一键复制与导出日志
- 👥 **玩家管理**：解析 `list` 响应与 join/leave 事件，提供 OP / 白名单 / 踢出快捷命令
- 🔄 **应用内更新**：GitHub Releases + 多个国内加速镜像测速择优，下载后校验收包 SHA-256

## 📱 快速开始

1. 从 [Releases](https://github.com/0Sakura721/Kaze-SLauncher/releases) 下载对应架构的 APK 安装
2. 主页 →「部署」环境（rootfs 已内置，不需要下载）
3. 「服务端」→「新建」→ 选核心类型 → 选版本 → 配置 → 下载并创建
4. 点「启动」→ 首次会自动接受 EULA 并重启 → 「控制台」实时查看日志

**选哪个安装包**：64 位手机用 `arm64-v8a`；32 位老设备用 `armeabi-v7a`（标记 experimental，真机验证较少）。
不再发布 `universal` 包。

## 🛠️ 构建

```bash
# 需要 JDK 17+ 与 Android SDK（compileSdk 35）
./gradlew assembleArm64Debug        # 产物 app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
./gradlew assembleArmhfRelease      # arm64 / armhf / universal 三种 flavor，debug / release 各一套（发布只出前两个）
```

发布包需要签名凭据，从 `local.properties`（本地）或环境变量（CI）读取，**取不到时产出未签名包，
不会回退到 debug 密钥**。轮换步骤与发版检查清单见 [docs/RELEASE-SIGNING.md](docs/RELEASE-SIGNING.md)。

### 测试与截图

```bash
./gradlew testArm64DebugUnitTest          # 单元测试（纯 JVM，不需要设备）
./gradlew recordRoborazziArm64Debug       # 渲染界面截图 → app/build/screenshots/
```

截图测试用 Robolectric 实例化真实的 `Application`，因此 `AppViewModel` 等依赖都是真的，
整屏渲染不需要任何假实现 —— 改 UI 后可以在几十秒内离线看到结果，不必装到设备上。
CI 会跑这两步并把截图作为 artifact 上传。

## 📁 结构

```
app/src/main/java/com/kaze/newage/
├── ui/                 # Compose 界面
│   ├── theme/          #   双主题设计系统、动态取色、背景层
│   ├── components/     #   卡片、状态球、实例图标等
│   ├── screens/        #   主页 / 服务端 / 控制台 / 设置 / 新建向导 / 详情 / 日志 / 插件
│   ├── AppRoot.kt      #   NavHost、底部导航
│   └── AppViewModel.kt #   界面与 core 的接线
├── core/
│   ├── env/            #   proot 环境部署与执行
│   ├── java/           #   JDK 的 apt 安装与管理
│   ├── server/         #   实例生命周期、EULA、server.properties、备份、控制台流
│   ├── download/       #   各核心的版本 / 构建 / 下载地址解析
│   ├── console/        #   日志解析（玩家列表、聊天行）
│   ├── addons/         #   Modrinth 插件与模组
│   ├── update/         #   应用内自更新
│   └── service/        #   前台保活服务
├── data/               # 实例存储、设置偏好、数据模型
└── util/               # 下载器、解压、存储目录工具

app/src/test/           # 单元测试 + 界面截图测试
.github/workflows/      # CI
```

## ⚠️ 已知限制

- **x86_64 模拟器无法运行服务端**：应用依赖 proot，而模拟器普遍用 ARM 翻译层（如 MuMu 的 houdini）
  执行 `libproot.so` 会直接段错误。界面与下载功能可用，但部署环境与启动服务端需要**真实 ARM 设备**
- **Forge / NeoForge 的 `--installServer` 已实现但尚未真机验证**：失败时控制台会打印安装器的完整输出
- 首次部署环境与安装 Java 需要网络；核心 jar 从官方源下载（Spigot 走社区 CDN，无官方哈希）

## 📄 许可证与致谢

**GPL-3.0**（见 [LICENSE](LICENSE)）。完整第三方组件清单与许可义务见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

| 项目 | 许可证 | 借鉴内容 |
|---|---|---|
| [Fold Craft Launcher (FCL)](https://github.com/FCL-Team/FoldCraftLauncher) | GPL-3.0 | 分层架构思路、JRE 管理、下载源、版本安装流程 |
| [ZalithLauncher 2](https://github.com/ZalithLauncher/ZalithLauncher2) | GPL-3.0 | Compose 重写路线、UI 卡片体系 |
| [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) | GPL-3.0 | 服务器原生运行思路、JRE 生态 |
| [BiliPai](https://github.com/jay3-yy/BiliPai) | GPL-3.0 | 主题设置体系、安卓原生液态玻璃 |
| [Miuix](https://github.com/Miuix-Kotlin-Multiplatform/Miuix) | Apache-2.0 | 浮动胶囊底栏结构 |
| [oonid/pr](https://github.com/oonid/pr) | GPL-2.0-or-later | 修补版 proot + loader |
| [termux/proot](https://github.com/termux/proot) · [proot-distro](https://github.com/termux/proot-distro) | GPL-2.0+ / GPL-3.0 | proot 运行时与 rootfs 部署方案 |
| [Haze](https://github.com/chrisbanes/haze) · [materialkolor](https://github.com/jordond/materialkolor) | Apache-2.0 | 背景模糊 / 动态取色 |
| [Roborazzi](https://github.com/takahirom/roborazzi) · [Robolectric](https://robolectric.org/) | Apache-2.0 / MIT | 离线界面截图测试 |

## ☕ 赞助支持

如果这个项目对你有帮助，可以请我喝杯奶茶 ☕

| 支付宝 | 微信 |
|:------:|:----:|
| ![支付宝](docs/images/alipay.png) | ![微信](docs/images/wechat.png) |

## ⚠️ 免责声明

- 运行 Minecraft 服务端即表示你同意 [Minecraft EULA](https://aka.ms/MinecraftEULA)
- 本项目仅供学习与个人使用，使用造成的任何损失由使用者自行承担
- Minecraft 是 Mojang Studios 的注册商标，本项目与 Mojang 无关
