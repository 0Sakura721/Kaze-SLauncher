# Changelog — Kaze SLauncher

> 遵循 [Keep a Changelog](https://keepachangelog.com/) 格式。
> 仓库：github.com/0Sakura721/Kaze-SLauncher · GPL-3.0

---

## [Unreleased]

### Fixed
- **命令输入框 placeholder 闪烁/消失**：停止时输入框仍可输入（发送按钮禁用），残留文字顶掉 placeholder；运行/停止切换时 placeholder 文字不一致。修复：停止时 `enabled=false` 并清空 input 残留，placeholder 恒定显示「服务端运行后可输入命令」；运行时显示「输入命令（stop / op 玩家名 / say …）」。
- **命令输入框键盘遮挡**：键盘弹出时输入框距键盘上方 96dp（底栏占位未收起）。修复：`WindowInsets.isImeVisible` 监听键盘状态，键盘可见时自动去掉 96dp 底部 padding，输入框紧贴输入法上方。

### Added
- **控制台「复制日志」按钮**：右上角「保存日志」左侧新增复制按钮。复制内容改为控制台内存流（与原屏幕所见一致），不再读文件；空日志 Toast「暂无日志」。
- **保存日志同步修改**：「保存日志」导出逻辑改为导出控制台内存流内容（与复制、屏幕三者一致）。

---

## [0.1.0] — 2026-08-18

首发版本。

### Added
- **双主题系统**：`clear`（简洁面板）/ `glass`（液态玻璃）；跟随系统深浅色，Glass 双版明暗配色。
- **自包含 Linux 环境**：proot + Ubuntu 24.04 rootfs 内置 arm64，无需 Root、无需 Termux，首次部署全程离线。
- **Java 自动安装**：按 MC 版本推断（1.8–1.16.5→8 / 1.18–1.20.4→17 / ≥1.20.5→21 / 26.x→25），apt 按需安装/删除。
- **EULA 全自动**：首次启动生成 eula.txt → 自动改写 `eula=true` → 重启，全程可视化三步指示。
- **实时控制台**：日志逐行着色、自动滚动、命令输入（stop / op / say…）。
- **服务端管理**：Vanilla / Paper / Purpur / Spigot / Fabric / Forge / NeoForge / 自定义 jar 导入，多实例独立目录与内存配置。
- **附加组件**：插件（Paper）/ 模组（Fabric/Forge）支持，Modrinth CDN 下载。
- **实例详情**：server.properties 编辑器（含「空服自动暂停」chips）、备份/恢复、导入/导出（SAF）。
- **日志页**：运行日志（console-output.log）、服务器日志（latest.log）、崩溃报告三卡片。
- **玩家管理**：ConsoleParser 解析 list 响应 + join/leave 事件 + OP/白名单/踢出快捷命令。
- **设置页完整体系**：主题样式 / 主题模式 / 深色样式 / 颜色来源 / 取色风格（九种）/ 液态玻璃模式 / 背景图裁剪 / 图标与文字颜色（跟随主题/白/黑）。
- **前置守护 Service**：`ServerGuardService` 前台保活，服务端运行期间保活、全部停止后自动退出。
- **后台电池白名单**：首次启动服务端自动申请忽略电池优化，vivo 兜底引导。

### Changed
- **Paper/Purpur 预置原版 jar**：旧版 paperclip 下载目标为 `CWD/cache/<fileName>`（非 `versions/`），需 SHA256 匹配才跳过联网。修复 `ensureVanillaJar` 解析 jar 内 `META-INF/download-context`，预置到正确路径。
- **Paperclip 版本解析**：paper 构建内部版本号与 mcVersion 可能不一致（paper-1.18.2-217.jar 的 version.json id=1.18.1）。新增 `paperclipVanillaVersion()` 读 version.json id。
- **proot 回退**：APK 含 x86_64 lib 后模拟器安装只解压 x86_64 目录，arm64 lib 不落盘。新增 `extractNativeProot` 从 APK zip 提取 arm64-v8a。
- **下载健壮化**：镜像返回 HTML 假 200 被当成功；修复：`probeFastest` 嗅探 1KB 排除 HTML；`RootfsJavaManager` 改 TUNA 目录页解析包名；`Downloader.download` 加 gzip 魔数+大小校验。
- **rootfs 健康检查增强**：isReady 从仅查 dash 可读改为 dash+usr/bin/sh+usr/bin/apt-get 三者真实读取。
- **设置页精简**：删除「Linux 环境」标题行+部署按钮+存放到外部存储开关+部署状态（环境部署已自动化）；主题模式/深色样式/颜色来源 Row→FlowRow 防窄屏挤压。
- **背景图 UX**：选择背景图按钮常驻；遮罩默认 25（原 60 太低几乎不可见）；开关仅在已选背景图时显示。
- **图标文字颜色三档**：跟随主题/白色/黑色，通过 `LocalFgColorMode` 覆盖中性前景角色；动态色容器上的文字不变。
- **裁剪页 Android 15 适配**：CropImageActivity 强制 edge-to-edge 后状态栏显示图片色；修复：加 `windowOptOutEdgeToEdgeEnforcement` + 白状态栏。

### Fixed
- **M3 深色文字消失**：MaterialTheme 1.3.x 不提供 `LocalContentColor`（默认 Color.Black）→ 深色下所有默认文字渲染纯黑。修复：显式 `provide onBackground`。
- **液态玻璃浅色不可见**：`BlendMode.Screen` 叠加在近白底上数学上不可见；修复浅色改用 SourceOver 直叠饱和色块。
- **底栏 50% 透光**：M3 分支容器 alpha 0.95→0.5，滚动内容可透出。
- **真机 rootfs 部署失败**：内部存储写入不稳定导致 rootfs 丢失；用户改外部存储后可正常。
- **MuMu 安装后 nativeLibraryDir 无 libproot.so**：x86_64 首选设备仅解压 x86_64 目录；通过 `extractNativeProot` 回退解决。

### Notes
- **APK ABI 范围**：v0.1.0 起 `abiFilters += "arm64-v8a"`，仅支持 arm64（真机主流；MuMu x86_64 模拟器无法安装，需换 arm64 模拟器或真机）。
- **构建命令**：需设 `GRADLE_USER_HOME` 指向 v3 缓存目录 + `TMP/TEMP` 指向工作区 `.tmp`。

---

## 历史版本（2026-08-14 ~ 2026-08-16 早期迭代）

以下功能均已合并至 0.1.0：

- 初始架构：多开会话（`ConcurrentHashMap<String, RuntimeSlot>`）、`ConsoleStream`、`ServerProperties`、`ModrinthApi`。
- 设计系统：Zalith/FCL 版式移植（主页 LauncherScreen 风格、服务端 VersionsManageScreen 风格、控制台日志着色）。
- 背景图裁剪：集成 CanHub Android-Image-Cropper 4.6.0（View 版，Activity 模式）。
- 日志持久化：`slot.log` 同步写 `console-output.log`，8MB 轮转。
- ServerGuardService 前台保活重构。
- 配置/记忆体系：`AppViewModel`、`InstanceStore`、`SettingsPrefs`。

---

[Unreleased]: https://github.com/0Sakura721/Kaze-SLauncher/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/0Sakura721/Kaze-SLauncher/releases/tag/v0.1.0
