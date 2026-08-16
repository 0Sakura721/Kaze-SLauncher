# THIRD PARTY NOTICES

Kaze SLauncher（GPL-3.0）参考/复用了以下开源项目的体系与代码，遵循各自许可证：

## 架构与代码来源

| 项目 | 仓库 | 许可证 | 用途 |
|---|---|---|---|
| Fold Craft Launcher (FCL) | github.com/FCL-Team/FoldCraftLauncher | GPL-3.0 | 分层架构思路、JRE 管理、下载源（piston-meta / BMCLAPI 等）；实例卡片「图标 + 名称/摘要 + 选中高亮 + 删除」模式参考 VersionListItem / ProfileListAdapter；新建服务端流程参考 VersionInstallPage（类型筛选 + 实时搜索 + 版本列表） |
| ZalithLauncher 2 | github.com/ZalithLauncher/ZalithLauncher2 | GPL-3.0 | Compose 重写路线、**UI 卡片体系**（BackgroundCard / CardTitleLayout / CheckChip 改编自其 ui/components 与 ui/theme 色板 Palette；服务端实例卡片改编自 ui/screens/content/elements/VersionsManageElements.kt 的 VersionItemLayout：图标 + 跑马灯 + 信息标签 + 右侧动作 + ⋮ 菜单 + 入场缩放，GPL-3.0） |
| PojavLauncher | github.com/PojavLauncherTeam/PojavLauncher | GPL-3.0 | 服务器原生运行思路、JRE 生态 |
| BiliPai | github.com/jay3-yy/BiliPai | GPL-3.0 | **主题设置体系与安卓原生液态玻璃**（Haze 背景模糊 + 折射 RuntimeShader 改编自其 LiquidGlassTuning / FullBarLiquidGlassModifier，参数照搬 BALANCED 档） |
| **Miuix**（LiquidGlassNavigationBar） | github.com/Miuix-Kotlin-Multiplatform/Miuix | Apache-2.0 | 浮动胶囊底栏（IosLiquidGlassNavigationBar 三层结构：背景玻璃链 + 内容层 + 图标层） |
| **Kyant0/AndroidLiquidGlass** | github.com/Kyant0/AndroidLiquidGlass | Apache-2.0 | 圆角矩形折射透镜 RuntimeShader（BiliPai 现役 lens 同款） |
| Kaze SLauncher v2（本项目旧版，作者自有） | github.com/0Sakura721/Kaze-SLauncher | LGPL-3.0 | proot 环境部署、tar 解压、下载源、服务端生命周期、eula 处理（LGPL-3.0 → GPL-3.0 兼容） |
| proot（内置运行时已替换，见下） | github.com/termux/proot | GPL-2.0+ | 原内置 proot 运行时（assets/bundled）——已由 oonid/pr 修补版替代 |
| **oonid/pr（修补版 proot + loader）** | github.com/oonid/pr | **proot fork：GPL-2.0-or-later**（其余组件 MIT） | **现役内置 proot 运行时**：`app/src/main/jniLibs/arm64-v8a/libproot.so` + `libproot-loader.so`（预编译二进制直接采用）。解决 targetSdk≥29 应用在 Android 12+ 上的 W^X（禁止 exec app_data_file）、zygote seccomp（18+ 系统调用拦截，SIGSYS 用户态模拟）与 PROOT_LOADER（nativeLibraryDir）机制；基于 proot v5.4.0 + termux-proot 补丁 |
| proot-distro | github.com/termux/proot-distro | GPL-3.0 | rootfs 部署方案参考 |

## 运行时与分发物

| 项目 | 来源 | 许可证 |
|---|---|---|
| Ubuntu 24.04 base rootfs | cdimage.ubuntu.com/ubuntu-base | Ubuntu 各组件许可证（GPL / BSD 等） |
| OpenJDK（rootfs 内 apt 按需安装，可选下载） | Ubuntu 软件源 | GPLv2 + Classpath Exception |
| Minecraft 服务端 jar（Vanilla） | Mojang piston-meta（运行时下载） | Mojang EULA 约束 |
| Minecraft 服务端 jar（Paper/Purpur/Spigot） | 各自官方 API（运行时下载） | MIT（Paper 补丁）+ LGPL-3.0（CraftBukkit 底子）等 |
| 插件 / 模组 | Modrinth v2 API + CDN（运行时下载） | 各项目自身许可证 |

## 依赖库（AndroidX / Kotlin 生态）

Jetpack Compose / AndroidX（Apache-2.0）、Kotlin & kotlinx（Apache-2.0）、Material Design Icons（Apache-2.0）、Navigation Compose（Apache-2.0）、**Haze**（github.com/chrisbanes/haze，Apache-2.0，安卓原生背景模糊）、**materialkolor**（github.com/jordond/materialkolor，Apache-2.0，动态取色/自定义种子色/取色风格）。

## 许可义务摘要（本项目合规清单）

- [x] 本软件整体以 **GPL-3.0** 发布（见 LICENSE，全文随发行提供）。
- [x] 使用 GPL-3.0 组件（FCL / Zalith / Pojav / proot-distro）的衍生作品须整体 GPL-3.0 开源 —— 本项目开源。
- [x] 改编自 ZalithLauncher2 / FCL 的源文件保留来源注释（`// 改编自 ZalithLauncher2 … GPL-3.0`）。
- [x] 内置 proot 二进制按 GPL-2.0+ 提供其源码获取方式：https://github.com/termux/proot；现役修补版（GPL-2.0-or-later）源码获取方式：https://github.com/oonid/pr（`src/proot/`，二进制位于 `android/app/src/main/jniLibs/arm64-v8a/`，本项目 `app/src/main/jniLibs/arm64-v8a/` 下两份二进制与其一致，未修改）。
- [x] 应用内「设置 → 关于与许可证」页展示许可证摘要与 EULA 声明。
- [x] 运行时下载的服务端 jar 不随 APK 再分发，仅提供下载入口与来源标注。
- [x] 使用本项目即表示你同意 Minecraft EULA（https://aka.ms/MinecraftEULA），eula.txt 由应用在用户启动流程中自动接受（等同于用户确认）。
