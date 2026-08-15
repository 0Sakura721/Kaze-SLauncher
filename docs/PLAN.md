# NEW AGE KAZE-SLAUNCHER — 项目计划（定稿 v1.0）

> 状态：**已定稿并实施**（2026-08-14）。M0–M6 全部完成，debug APK 构建通过。
> 目标：Android 上驱动 **Minecraft Java 服务端**的前端，自包含 Linux 环境 + Java 自动安装 + eula 全自动 + 实时控制台，**双主题设计系统**，GPL-3.0 合规。

## 0. 需求链路（用户拍板版）

```
[前端] 检测/部署 Linux 环境（自包含 proot + Ubuntu rootfs，无需 Termux）
   → 按 MC 版本自动安装 Java（8 / 17 / 21）
   → 下载/导入服务端（精简首发：Vanilla / Paper / 自定义 jar）
   → 首次启动：服务端生成 eula.txt 后自动退出
   → 前端自动改写 eula=false → true
   → 再启动 → 正常运行
   → 控制台实时日志 + 命令输入（stop / op / say…）
```

## 1. 已拍板决策（用户 2026-08-14 确认）

| 项 | 决策 |
|---|---|
| 运行环境 | **自包含 proot + Ubuntu 24.04 rootfs，沿用 v3**（不装 Termux） |
| 许可证 | **GPL-3.0**（借鉴 FCL / Zalith copyleft 体系的必然要求） |
| MVP 范围 | **精简首发**：Vanilla + Paper + 自定义导入 + eula 全自动 + 实时控制台 + 设置/许可证页（后端多核心能力保留、UI 不露出） |
| 设计 | **两主题**：默认「简洁面板」（PiliPlus 式清爽卡片面板，跟随系统深浅色）；另有「Aurora 极光」（深色主导，深空极光 + 毛玻璃） |
| 技术栈 | Kotlin 2.1 + Compose (M3, BOM 2024.12.01)、AGP 8.7.3、Gradle 8.11.1、minSdk 27 |
| 包名 | `com.kaze.newage`（与旧版共存），应用名 "Kaze SLauncher" |

## 2. 架构（已实施）

```
app/src/main/java/com/kaze/newage/
├── MainActivity.kt / NewAgeApp.kt    # 入口 + 手动 DI 容器
├── ui/
│   ├── theme/     # AppTheme(双主题枚举) + Theme(三套 ColorScheme) + Background(平面/极光)
│   ├── components/# AppBackground / BackgroundCard(主题化描边圆角) / CheckChip / StatusOrb(签名元素)
│   ├── screens/   # Home(Hero+EULA三步) / Server(实例列表) / Console(日志+命令) / Settings(主题选择+许可证)
│   └── AppRoot.kt / AppViewModel.kt / ServerStateUi.kt
├── core/          # env(ProotEnvironment) / java(RootfsJavaManager) / server(DefaultServerManager+EulaHandler)
│                  # download(CoreSources 七源) / console(ConsoleStream)
├── data/          # InstanceStore / ServerInstance / SettingsPrefs(theme_mode/force_dark)
└── util/          # Downloader / TarExtractor
```

- **后端 100% 继承 v3**（已验证：proot 部署、tar 解压、Java apt 安装、eula 三段式、stdin 直连注入命令、日志双通道）。
- **前端全新**：双主题设计系统 + 状态球签名元素 + eula 三步可视化。

## 3. 设计系统（签名元素：状态球 StatusOrb）

| 主题 | 背景 | 卡片 | 状态球 |
|---|---|---|---|
| CLEAR 简洁面板（默认） | 冷灰平面渐变，跟随系统深浅色 | 纯白/深灰 + 发丝描边、14dp、无阴影 | 扁平圆环 + 实心点，启动中脉动 |
| AURORA 极光 | 深空 + 星点 + 极光幕布（26s 正弦漂移，加法混合） | 白 6% 毛玻璃 + 白 10% 描边、20dp | 渐变球体 + 辉光，运行中呼吸 |

- 动效克制（每主题一个环境动画），尊重系统「动画时长缩放=0」（reducedMotion）。
- 控制台日志面板两主题均为深色终端（仅底色微调），按级别着色。
- 深浅色设置仅对 CLEAR 生效；AURORA 为深色主导主题。

## 4. 许可证合规清单（权重 0.8，全部完成）

- [x] LICENSE = GPL-3.0 全文（随发行）
- [x] THIRD_PARTY_NOTICES.md：FCL / Zalith / PojavLauncher / Kaze v2 / proot / proot-distro / Ubuntu rootfs / OpenJDK / Paper 等来源与许可
- [x] 改编自 Zalith 的组件/色板文件保留来源注释（GPL-3.0）
- [x] UI「设置 → 关于与许可证」页：GPL-3.0 + 第三方摘要 + Minecraft EULA 声明
- [x] 运行时下载的服务端 jar 不随 APK 再分发
- [x] 内置 proot（GPL-2.0+）注明源码获取方式

## 5. 里程碑（全部完成）

- M0 仓库初始化（LICENSE / NOTICES / README / .gitignore）✅
- M1 工程骨架 + 首次构建通过 ✅（基线 3m05s）
- M2 环境层（沿用 v3：proot + rootfs + Java apt）✅
- M3 服务端生命周期（下载/导入 → Java → eula 三段式 → 启停）✅
- M4 实时控制台（着色 / 自动滚动 / 命令输入 / 清空）✅
- M5 设计精修（双主题 + 状态球 + 图标）✅
- M6 设置/关于/许可证 + 打包 ✅（app-debug.apk）

## 6. 待办（后续）

- 真机测试：双主题切换、eula 三段式、控制台着色与命令、低内存设备表现
- 可选：AURORA 主题极光动画的帧率优化（低端机）、bionic JRE 直跑兜底
- 发布：GitHub 开源（GPL-3.0）+ release 签名

## 附：关键引用

- FCL：https://github.com/FCL-Team/FoldCraftLauncher （GPL-3.0）
- ZalithLauncher2：https://github.com/ZalithLauncher/ZalithLauncher2 （GPL-3.0）
- PojavLauncher：https://github.com/PojavLauncherTeam/PojavLauncher （GPL-3.0）
- proot：https://github.com/termux/proot （GPL-2.0+）
- Paper 许可：https://github.com/PaperMC/Paper/blob/master/LICENSE.md
- itzg（Java↔MC 对应）：https://docker-minecraft-server.readthedocs.io
