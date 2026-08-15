# Kaze SLauncher

> 在 Android 上运行 **Minecraft Java 服务端**的启动器 —— 自包含（proot + Ubuntu 24.04），无需 Root、无需 Termux，一键完成「环境 → Java → 服务端 → EULA → 实时控制台」全链路。

![Kotlin](https://img.shields.io/badge/Kotlin-2.1-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-green)
![minSdk](https://img.shields.io/badge/minSdk-27-orange)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

## ✨ 特性

- 🎨 **两套主题**：简洁面板（默认，清爽卡片）/ 液态玻璃（通透玻璃卡片 + 顶部镜面高光 + 柔光斑，**跟随系统深浅色**）；签名元素「状态球」实时呈现服务状态
- 🐧 **自包含 Linux 环境**：proot + Ubuntu 24.04 rootfs 随 APK 内置（arm64 + armhf），首次部署全程离线
- ☕ **Java 自动安装**：按 MC 版本推断（1.8–1.16.5→8 / 1.18–1.20.4→17 / ≥1.20.5→21），apt 自动安装
- ⚖️ **EULA 全自动**：首次启动生成 eula.txt → 自动改写 `eula=true` → 重启，全程可视化三步指示
- 🖥️ **实时控制台**：日志逐行输出、按级别着色、自动滚动、命令输入（stop / op / say…）
- 📦 **服务端管理**：Vanilla / Paper 在线下载 + 自定义 jar 导入，多实例独立目录与内存配置

## 📱 快速开始

1. 安装 APK，打开应用
2. 主页 → 「部署」环境（rootfs 已内置，无需下载）
3. 「服务端」→ ＋ 新建：选择 Vanilla 或 Paper 与 MC 版本，下载并创建
4. 点击「启动」→ 自动接受 EULA → 服务端运行 → 「控制台」实时查看日志

## 🛠️ 构建

```bash
# 需要：JDK 17+、Android SDK（compileSdk 35）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 📁 结构

```
app/src/main/java/com/kaze/newage/
├── ui/            # Compose 界面（theme/ 双主题设计系统、components/ 卡片与状态球、screens/ 四页）
├── core/          # env(proot) / java / server(生命周期+EULA) / download(下载源) / console(日志流)
├── data/          # 实例存储、设置（主题/背景）
└── util/          # 下载、tar 解压
```

## 📄 许可证

**GPL-3.0**（见 [LICENSE](LICENSE)）。架构与 UI 体系参考 Fold Craft Launcher、ZalithLauncher2（均 GPL-3.0）；环境方案参考 proot / proot-distro。第三方组件清单与许可义务见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## ⚠️ 免责声明

- 下载或运行 Minecraft 服务端即表示你同意 [Minecraft EULA](https://aka.ms/MinecraftEULA)
- 本项目仅供学习与个人使用，使用造成的任何损失由使用者自行承担
- Minecraft 是 Mojang Studios 的注册商标，本项目与 Mojang 无关
