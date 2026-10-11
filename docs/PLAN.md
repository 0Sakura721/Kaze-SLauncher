# 项目计划与架构决策（历史记录）

> **文档状态：历史记录，不是当前路线图。** 本文主要记录 2026-08-14 的初始目标与决策；项目后来增加了多核心支持、AI 助手、备份、更新与截图测试等功能。当前功能以 [README](../README.md) 和[用户使用说明](使用说明.md)为准，版本变更以 [CHANGELOG](../CHANGELOG.md) 为准。

## 1. 初始目标

在 Android 手机上运行 Minecraft Java 版服务端，让旧手机也能作为轻量服务端设备使用。初始设计的核心链路是：

```text
部署自包含 Linux 运行环境
  → 按 Minecraft 版本安装 Java
  → 下载或导入服务端核心
  → 用户明确同意 EULA
  → 首次启动并处理 eula.txt
  → 启动服务端、查看实时控制台并输入命令
```

初始目标强调：不要求 Root、不要求安装 Termux、运行环境随 APK 提供，并尽量减少用户手动配置。

## 2. 初始技术决策（2026-08-14）

| 项目 | 当时的决策 | 说明 |
|---|---|---|
| 运行环境 | proot + Ubuntu 24.04 rootfs | 将 Linux 用户态环境封装在应用中，避免要求用户另装 Termux |
| 技术栈 | Kotlin 2.1、Jetpack Compose、Gradle Wrapper | 使用 Android 原生 UI 与仓库锁定的构建工具链 |
| 最低系统版本 | minSdk 27 | Android 8.1 及以上 |
| 包名 | `com.kaze.newage` | 初始设计中希望与旧版共存 |
| 许可证 | GPL-3.0 | 结合采用和改编的第三方组件及其许可证确定 |
| 首发范围 | Vanilla、Paper、自定义 JAR | 当时的 MVP 范围；不是当前支持核心的完整清单 |
| 初始界面 | 双主题、状态球、EULA 三步状态 | 后来经过 Material 3 Expressive 界面重构，部分设计已被替代 |

## 3. 当前架构入口

以下目录用于帮助维护者定位代码。目录与功能会随开发演进，实际以仓库当前文件为准。

```text
app/src/main/java/com/kaze/newage/
├── ui/       # Compose 页面、导航、组件与主题
├── core/     # 环境部署、Java、服务端生命周期、下载、更新与 AI
├── data/     # 实例存储、设置与数据模型
└── util/     # 下载、解压、存储等通用工具

app/src/test/              # 单元测试与界面截图测试
.github/workflows/         # CI、APK 构建与发版流程
```

## 4. 设计演进与当前主线说明

- **界面体系：** 初始的状态球设计后来演进为 Material 3 Expressive；当前界面和组件以 `ui/` 下的实际实现为准。
- **液态玻璃：** 相关代码保留在仓库中，但已从主线构建中移除。需要可运行的液态玻璃实现时，请参阅 [LIQUIDGLASS_BRANCH.md](../LIQUIDGLASS_BRANCH.md)；不要仅凭本历史计划判断主线是否启用该主题。
- **功能范围：** 当前功能比初始 MVP 更广，包括多种服务端核心、备份与恢复、插件/模组、控制台增强、应用内更新、AI 助手和离线界面截图测试等。具体细节请查阅 README 与用户手册。
- **测试与发布：** 维护者应以当前 GitHub Actions workflow 的结果为准，并在真机验证无法由 JVM 单测覆盖的权限、后台保活与服务端安装流程。操作细节见 [维护笔记](MAINTENANCE.md)。

## 5. 历史设计参考

初始计划中的部分设计与技术来源仍有追溯价值，但不应作为当前实现状态的依据：

- **Java 与 Minecraft 版本对应：** [itzg/docker-minecraft-server 文档](https://docker-minecraft-server.readthedocs.io) 是版本推断逻辑的参考来源之一；当前行为以 `ServerInstance.kt` 中的实现与注释为准。
- **Material 3 Expressive（M3E）：** 2026 年界面重构采用动态取色、较大圆角、Expressive 动效与波浪进度指示器等设计方向。生成过程与设计资产保存在 [`docs/m3e/`](m3e/)，这些记录用于追溯设计来源，不代表每项效果都仍在主线启用。

## 6. 初始里程碑（历史状态）

以下是初始开发计划中的里程碑，表示它们在初始阶段完成，不代表项目当前只有这些功能，也不是当前待办列表。

- [x] M0：仓库初始化与许可证文件
- [x] M1：工程骨架与首次构建
- [x] M2：proot、rootfs 与 Java 环境层
- [x] M3：服务端下载/导入、EULA 与启停流程
- [x] M4：实时控制台与命令输入
- [x] M5：初始界面主题和组件
- [x] M6：设置、许可证页面与打包

## 7. 相关文档

- [项目总览与构建](../README.md)
- [用户使用说明与常见问题](使用说明.md)
- [维护笔记与 CI 排查](MAINTENANCE.md)
- [发布签名与密钥管理](RELEASE-SIGNING.md)
- [变更日志](../CHANGELOG.md)
- [第三方组件声明](../THIRD_PARTY_NOTICES.md)
