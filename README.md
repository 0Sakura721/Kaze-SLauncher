# Kaze SLauncher

> 一个在 Android 上运行 Minecraft Java 版服务器的启动器，基于 Proot + Ubuntu 24.04，无需 Termux、无需 Root。

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.20-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-1.7-green)
![minSdk](https://img.shields.io/badge/minSdk-27-orange)
![License](https://img.shields.io/badge/license-LGPLv3-blue)
[![Issues](https://img.shields.io/github/issues/0Sakura721/Kaze-SLauncher?color=red)](https://github.com/0Sakura721/Kaze-SLauncher/issues)
[![PRs](https://img.shields.io/github/issues-pr/0Sakura721/Kaze-SLauncher?color=green)](https://github.com/0Sakura721/Kaze-SLauncher/pulls)

## ✨ 功能特性

- 🚀 **零依赖运行**：内置 Proot + Ubuntu 24.04 rootfs，无需安装 Termux 或 Root
- 📱 **Material You 界面**：Jetpack Compose 打造的现代化 UI，支持动态配色
- 🔧 **多核心支持**：Vanilla / Forge / Fabric / Quilt / NeoForge / Paper / Spigot
- 📦 **模组与插件管理**：内置 Modrinth / CurseForge 搜索与一键安装
- 💾 **备份与恢复**：完整服务器目录备份，支持导出到本地
- 📊 **性能监控**：实时 CPU / 内存 / 玩家数 / TPS 监控
- 🔔 **前台服务**：服务器运行在前台服务中，自带常驻通知
- 🛡️ **安全设计**：沙箱化运行环境，细粒度权限控制

## 📸 截图

（待补充）

## 🚀 快速开始

1. **下载 APK**：从 [Releases](https://github.com/0Sakura721/Kaze-SLauncher/releases) 下载最新版
2. **安装运行**：安装后打开，首次启动会自动部署运行环境
3. **选择核心**：在「核心管理」中下载并安装你想要的服务端核心
4. **启动服务器**：回到主页点击启动，享受 Minecraft 服务器之旅

## 🛠️ 构建

### 环境要求

- Android Studio Iguana+
- JDK 17
- Android SDK 34
- NDK（用于 proot 编译）

### 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease
```

### Release 签名

在 `local.properties` 中配置：

```properties
storeFile=/path/to/keystore.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

## 📁 项目结构

```
app/src/main/java/com/mcserver/launcher/
├── server/          # 服务器核心逻辑
│   ├── ServerManager.kt         # 服务器生命周期管理
│   ├── TermuxManager.kt         # Proot / Ubuntu 环境管理
│   ├── JreManager.kt            # JRE 管理
│   ├── PluginManager.kt         # 插件管理
│   ├── BackupManager.kt         # 备份管理
│   ├── WorldManager.kt          # 世界管理
│   └── ...
├── ui/screens/      # Compose 界面
├── data/            # 数据层（配置、偏好设置）
└── MainActivity.kt
```

## 🤝 贡献

欢迎提交 [Issue](https://github.com/0Sakura721/Kaze-SLauncher/issues) 和 [Pull Request](https://github.com/0Sakura721/Kaze-SLauncher/pulls)！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 💰 支持开发

如果这个项目对你有帮助，可以请我喝杯奶茶 ☕

| 支付宝 | 微信 |
|:------:|:----:|
| ![支付宝](docs/images/alipay.png) | ![微信](docs/images/wechat.png) |


## 📄 许可证

本项目采用 [GNU LGPLv3](LICENSE) 许可证。

## ⚠️ 免责声明

- 本项目仅供学习与个人使用，请遵守 Mojang EULA
- 使用本软件造成的任何损失由使用者自行承担
- Minecraft 是 Mojang Studios 的注册商标，本项目与 Mojang 无关
