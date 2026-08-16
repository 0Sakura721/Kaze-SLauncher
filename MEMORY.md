# Memory — Kaze-SLauncher（新版，工作区目录：NEW AGE KAZE-SLAUNCHER）

> 跨会话记忆文件。规则：动手前先读；有值得记住的信息就更新；过期条目删除或标注。
> 最近更新：2026-08-16

## 项目约定
- 包名 `com.kaze.newage`，应用名 "Kaze SLauncher"（Kaze 与 SLauncher 之间带空格；连字符仅为 GitHub 仓库名所需，用户 2026-08-14 明确），GPL-3.0（与旧版 v2/v3 可共存）。
- 2026-08-14：用户要求去除 New Age 标识与连字符（应用名/界面文案改回 "Kaze SLauncher"）；内部类名（NewAgeApp/NewAgeTheme）与包名 com.kaze.newage 保留（非用户可见）。
- 后端 100% 继承 Kaze-SLauncher v3（proot + Ubuntu rootfs + Java/eula/服务端/下载/控制台，已验证可构建）；前端为全新双主题设计系统。
- 两主题：`clear`（简洁面板，默认）/ `glass`（液态玻璃）；原 aurora 极光已按用户要求替换，`fromId("aurora")` 自动迁移到 glass（2026-08-14）。存 SharedPreferences `theme_mode`；深浅色 `force_dark`（null=跟随系统，**两主题均生效**——用户反馈过"玻璃强制浅色"，已修：GlassDark 配色 + GlassBackdrop 明暗双版 + BackgroundCard 顶部镜面高光条）。玻璃光斑参数：深色 alphas 0.16-0.38 半径 0.38-0.72m（留深底）；浅色底色加深 #BFD5F1 系 + 白光斑 0.8+。
- 签名元素：`StatusOrb`（状态球，双主题差异化渲染）。动效尊重系统「动画时长缩放=0」。

## 决策记录
- 2026-08-14：用户拍板：① 自包含 proot 沿用 v3（不装 Termux）；② MVP 精简首发=Vanilla+Paper+自定义导入（UI 隐藏其他核心类型，后端 CoreSources 仍支持全部）；③ 三主题，默认简洁面板（参考 PiliPlus 风格——B 站第三方客户端，即清爽现代卡片面板）+ Aurora + Terminal Neon。之后用户要求移除霓虹 → 最终两主题（clear + aurora）。
- 2026-08-14：包名选 `com.kaze.newage`（与旧版共存，未让用户逐项确认，属"最优方案直接做"）。

## 构建要点（重要）
- 构建必须设 `GRADLE_USER_HOME=D:\Deepseek Harness\Kaze-SLauncher\v3\.gradle-user-home`（复用 v3 的 1.4GB 缓存：Gradle 8.11.1 dist + AGP/Kotlin/Compose 依赖，版本与 v3 完全一致；删 v3 前需先迁移该缓存）+ `TMP/TEMP=工作区\.tmp`。
- 签名：`debug.keystore`（android/androiddebugkey），根目录。
- JDK：系统 PATH 的 Microsoft OpenJDK 21（AGP 8.7.3 兼容）。
- 命令：`cmd /c "set GRADLE_USER_HOME=... && set TMP=... && gradlew.bat assembleDebug --no-daemon"`；基线构建 3m05s 通过。
- `local.properties` 已写 `sdk.dir=C:/Users/Flash/AppData/Local/Android/Sdk`。

## 真机测试（vivo V2352GA，Android 16/API 36，Adreno 735，1260x2800，2026-08-15）
- 无线调试：`adb pair <IP:配对端口> <6位码>`（配对码刷新弹窗就变，失败后要新码）→ `adb connect <IP:连接端口>`（配对端口≠连接端口，连接端口看无线调试主页「IP 地址和端口」）。配对过一次后可直接 connect（密钥已存 PC）。mDNS 发现在本网络不可用。
- **vivo 屏蔽应用 Log.d**（logcat 看不到 app 自产日志 KazeGlass/KazeEnv，系统 GC 日志可见）——真机排查别依赖日志，用截图+像素采样。
- vivo 安装需手机弹窗确认（否则 INSTALL_FAILED_ABORTED: User rejected permissions）。
- **RuntimeShader 兼容性实测（vivo Adreno 735/API36）**：波浪+色差 shader（BiliPai 旧 FullBar 版）黑屏（含无三角多项式版）；SDF 边缘 shader（BiliPai 旧 LiquidGlassShader）✅；**Kyant0 圆角矩形折射透镜（BiliPai 现役 lens 同款）✅ 渲染正常**。规则：无三角函数 + 单次 img.eval 的 SDF 类 shader 可用；sin/cos 或多采样版本黑。模拟器 SwiftShader 一律跳过（probeGpuRenderer 黑名单）。
- **当前基线 =「现在的 BiliPai」版式（Miuix IosLiquidGlassNavigationBar，Apache-2.0 已记 NOTICES）**：底栏 = 24dp 边距浮动圆胶囊（64dp）+ 10dp 投影 + 三层结构（层①背景玻璃链=haze+饱和1.5+Kyant0透镜24/24dp，层②surfaceContainer a0.4，层③图标标签最上——**透镜只作用于背景层，绝不能套在内容上**（会把图标吞掉，"选项都没了"bug 根因））。背景=纯色渐变+柔光斑（用户要求不改背景）。卡片=Haze+饱和1.5。用户方向（2026-08-15）：**"先完善UI，不要塑料UI"**——拒绝扁平塑料感，走真玻璃（透镜+景深+高光）。
- **去"劣质大框架"（2026-08-15，用户反馈三页廉价大卡框）**：① BackgroundCard 玻璃模式默认**无发丝边框**（effectiveBorder=null；M3 保留 hairline；显式 border 参数仍生效）；② CardTitleLayout 玻璃分隔线软化为 white 0.10/0.30；③ ServerScreen **删除整页大卡**——工具条+列表直接铺背景，实例卡悬浮；④ HomeScreen **删除大面板卡**——状态球/信息行/环境行直接铺背景，选择器改为 itemColor 小型玻璃 chip（Surface onClick，圆角 16）。真机实测：三页无框架、选择器下拉正常、胶囊底栏导航正常。
- **真机 blur 实测在工作**（开/关像素有差异）。玻璃观感全部来自面板层。**注意：测试中改动的用户 prefs 要恢复原状（theme_mode_value 曾误改 1→已恢复 0 跟随系统）**。
- **真机 rootfs 部署失败的根因 = 内部存储 /data/user/0 写入不稳定（2026-08-15 实锤）**：症状——提取器完整跑完（dash 检查通过、apt 日志存在、"环境初始化完成"），但 usr/bin 在 setup 后变空、var/lib/apt/lists 空、rootfs 只有 11MB；proot-home/instances.json/prefs 等小写入正常。与模拟器存储损坏同病。**解法：设置 → 存放到外部存储（env_external=true）→ 重新部署**。外部存储实测完整成功：rootfs 134MB、usr/bin 287 文件、dash 存在、"Linux 环境就绪"、进入 Java 21 安装阶段。**adb 调试注意：`run-as pkg sh -c '相对路径'` 的 CWD 是 / 不是应用目录（写入会落到根目录报 ENOENT），一律用绝对路径 /data/user/0/<pkg>/...；`adb shell 2>/dev/null` 在 PowerShell 会当成本地路径报错，用 2>&1。**
- **设置页去框架（2026-08-15，用户"设置页还是有塑料似的边框，弄成和其它页面一样"）**：删除全部 BackgroundCard+CardTitleLayout 包装——外观/环境/通用三个分区改为纯文字标题（titleMedium）+ 内容直接铺背景；环境卡的「部署」按钮移到标题行右侧；通用手风琴保持（行+分隔线）。真机实测无卡片框架。cardBorderColor 仅剩小元素（chip/磁贴）使用。
- **gradle 构建沙箱注意**：DSH 沙箱为 workspace-write 时，Gradle 写工作区外的 GRADLE_USER_HOME（v3\.gradle-user-home）会被拒（wrapper .lck 拒绝访问）；需 danger-full-access 或确认策略。残留 .lck 可删。
- **ServerGuardService 前台守护（2026-08-16 重做成功）**：旧 GuardService 崩因为 startForegroundService 后 5 秒内没 startForeground + 类型权限缺失。正确写法：manifest 声明 `foregroundServiceType="specialUse"` + `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性（缺一 SecurityException）；onCreate 第一时间 `ServiceCompat.startForeground(this, id, notif, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`；渠道 IMPORTANCE_LOW；服务端启动→start、全部停止→stop（DefaultServerManager 持 appContext）。MainActivity 请求 POST_NOTIFICATIONS（拒绝仅隐藏通知）。真机实测 isForeground=true、退后台 90 秒应用+服务端进程均存活、无崩溃。**建议用户把应用加入电池优化白名单**。

## 真机环境部署通关记录（2026-08-16）
- **根因实锤：targetSdk≥29 应用的 W^X + zygote seccomp**（vivo Android 16/API36 实测）：① W^X 禁止 exec /data/data 下 app_data_file ELF（proot exec guest /bin/sh 报 EACCES）；② zygote seccomp 拦截 18+ 系统调用（chdir/chmod/getcwd/clone3…）。`run-as` 能跑通是因为它没有 seccomp 过滤器（/proc/<pid>/status Seccomp: 2 vs 0）。与 oonid/pr 文档《important-notes.md》三拦路虎描述 100% 吻合（他们已用 "W^X + seccomp + targetSdk 35 proot Android" 验证）。
- **最终方案（前人经验，oonid/pr，GPL-2.0-or-later 与 GPL-3.0 兼容）**：bundle 其修补版 proot 二进制到 `app/src/main/jniLibs/arm64-v8a/{libproot.so(2.5MB), libproot-loader.so(18KB)}`（jsdelivr 从 github.com/oonid/pr 下载，勿改动）。proot 直接从 **nativeLibraryDir** 运行（唯一允许 untrusted_app execve 的位置），PROOT_LOADER 指同目录 loader（mmap 加载 guest ELF 绕过 W^X），修补版内置 SIGSYS 处理器模拟被 seccomp 拦的系统调用。**启动参数照搬其 shared.rs**：`--rootfs --cwd=/ --change-id=0:0 --kill-on-exit --link2symlink --kernel-release=6.17.0-pr -b /dev -b /proc -b /sys -b /proc/self/fd:/dev/fd -b /dev/urandom:/dev/random -b rootfs/sys/.empty:/sys/fs/selinux -b cacheDir:/tmp -b rootfs/tmp:/dev/shm -b <实例目录>:/mnt`。环境：PROOT_TMP_DIR/TMPDIR=**内部 cacheDir**、PROOT_L2S_DIR=rootfs/.l2s（须与 rootfs 同文件系统，dpkg/apt 硬链接需要）、HOME/LANG/TERM。已删除 proot-home 资产提取（fixProotSonameLinks 等旧代码）。
- 备选快速方案（未用）：targetSdk 28 无 W^X/seccomp（oonid/pr 实测表 targetSdk 28 proot 直跑）。
- **extractNativeLibs 必须 true**（2026-08-16）：AGP 默认 run-from-apk 模式不落盘 .so，nativeLibraryDir 为空 → "proot 运行时缺失"。已在 manifest application 加 `android:extractNativeLibs="true"`。验证：装完 `ls /data/app/~~*/<pkg>-*/lib/arm64` 应看到 libproot.so。
- **MC 新版（26.x，如 26.1.1）需要 Java 25**（实测 bundler class file 69.0，Java 21 报 UnsupportedClassVersionError）。已改：JavaVersionInference（major≥26→25）、installedJdkVersions 加 25、设置页/新建实例 Java 列表加 25、InstanceStore.load 迁移旧档 javaMajor<25 自动升 25。Adoptium 直连下载 Temurin 25（api.adoptium.net，手机网络不受限）。
- **内部 rootfs 部署成功**：系统 tar 解压 + 全局 sync + dashReadable 实读校验重试 → 内部 rootfs 463MB 完整；JDK 348MB 外部→内部迁移成功（java-21-openjdk-arm64 完整可执行）。
- **真机 run-as 诊断法**：push 诊断脚本到 /data/local/tmp + `run-as com.kaze.newage sh /data/local/tmp/diag.sh`（CWD=/，脚本内绝对路径 + cd 目录 + 设 PROOT_LOADER/LD_LIBRARY_PATH/PROOT_NO_SECCOMP）。注意 run-as 无法访问 /storage/emulated/0 且无 seccomp，只能证明"文件与 proot 本身 OK"，应用内问题必须以应用自身日志定位（proot -v 4 输出会经 console 流落盘）。
- 资产长度校验：`assets.openFd` 对 compressed 资产抛异常 → 用 `assets.open().use { it.available().toLong() }`。
- 日志证据：`/storage/emulated/0/Android/data/com.kaze.newage/files/instances/man/console-output.log` 会累积历史会话错误，诊断前先 rm 再触发。
- **✅ 端到端跑通（2026-08-16 14:24，vivo 真机）**：应用内 启动服务端 → proot(nativeLibraryDir) → Ubuntu rootfs(内部463MB) → Temurin Java 25 → vanilla-26.1.1 → "Preparing level world / All chunks are saved / Server empty for 60 seconds, pausing"，adb forward 25565 TcpTestSucceeded=True。已提交推送 0815（62cb90e）。遗留：authlib 周期性网络 WARNING（无害）；局域网直连手机 IP 需手机防火墙放行（vivo 可能默认拦）。

## 模拟器测试（MuMu 12，重要发现）
- adb 设备：emulator-5554（伪装 vivo V2203A），Android 15，主 ABI x86_64 但 **abilist 含 arm64-v8a**（houdini ARM 翻译层）→ `Build.SUPPORTED_ABIS` 判定走 arm64 路线，**proot aarch64 二进制可运行**（日志 `houdini: executing proot`）。
- 已实测通过：安装/启动、双主题切换+持久化、强制浅色（LocalDarkTheme bug 已修）、版本列表拉取、Paper 26.2 下载建实例（52MB jar）、server.properties 自动生成（端口 25565）。
- **环境部署完整通过两次**（18:59 旧解压器、19:29 新解压器）：proot 在 houdini 下运行、106MB rootfs 完整解压、**apt-get update 在 proot 内成功**（证明 tar/符号链接/proot/网络全链路 OK）。
- **模拟器存储损坏（环境问题，非应用 bug）——最终诊断**：重启 MuMu 虚拟机也无效（22:00 后验证）。特征：应用视角内「写后立读」都不保证——dash 提取完成检查通过（exists()=true）后毫秒级消失（isReady 复查=false，KazeEnv 日志实锤）；shell FUSE 视图显示 0 文件/18M 恒定。rootfs 解压 4 次完整成功（18:59/19:29/两轮外部存储）+ proot 内 apt 通过，之后始终卡住。**需重建 MuMu 模拟器实例或换真机**。大文件流式写入（jar/备份 zip）与 /data/local/tmp 正常。
- **主题体系（用户要求照搬 BiliPai，GPL-3.0 已记入 NOTICES）**：设置页「外观」含 BiliPai 全套——主题样式（M3/安卓原生液态玻璃）、主题模式（跟随系统/浅色/深色 `theme_mode_value`）、深色样式（普通黑/AMOLED纯黑 `dark_style`）、MD3 颜色来源（跟随壁纸/自定义 `md3_color_source`+`md3_custom_color` hex）、取色风格（materialkolor PaletteStyle 九种）、液态玻璃模式（清晰/均衡/磨砂 `glass_mode`，BiliPai progress lerp 参数）、原生模糊开关（`glass_blur`）。依赖：materialkolor **2.1.1**（4.x 是 Kotlin 2.3 编译不兼容；2.x API = `dynamicColorScheme(seedColor: Color, isDark, isAmoled, style=...)`）、Haze 1.6.10（1.7.x 需 compileSdk36）。**黑屏真凶是折射 RuntimeShader（API33+ SwiftShader 不支持），不是 Haze**——底栏已改用 Haze，真模糊模拟器实测可用（glass_blur ON 渲染正常）；LiquidGlassEffect.kt 折射着色器保留文件暂未接线（备真机启用）。
- **新增功能（Round 2）**：设置→环境卡「存放到外部存储」开关（`env_external`；`ProotEnvironment` 构造参数 `linuxBase: () -> File`，AppContainer 传入 provider；内部空间不足场景，切换后需重新部署）。
- TarExtractor **不要加 fd.sync()**：FUSE 外部存储上 sync 可能长时间阻塞（实测卡死）；已移除（Downloader 的 sync 保留，实测正常）。
- 修复过的问题：tar 软链条目 linkname 在 header offset 157（已修）；修复函数调用时机（fixProotSonameLinks 原在 rootfs 解压前调用）；`usr/*` 在注释里触发 Kotlin 嵌套块注释（编译错）；isReady 曾依赖 canExecute（模拟器不可靠，已移除并加诊断日志 KazeEnv）；TarExtractor/Downloader 已加 fd.sync()。
- **adb 截图必须 `cmd /c "adb exec-out screencap -p > file.png"`**（PowerShell `>` 重定向损坏二进制）；像素采样用 System.Drawing 验证配色。
- 测试教训：自动化坐标点击可能误触设置页「背景图」流程（保存暗图 + 遮罩）→ 全屏灰蒙层掩盖主题渲染；排查时先看 prefs 的 `bg_enabled` 与 `files/background.png`。pm clear 可重置。
- 背景图 UX 修复（2026-08-14，用户反馈"设置不了"）：①「选择背景图」按钮**常驻**（不再被开关隐藏；选图后自动开启显示）；②遮罩默认 60→**25**、范围 0~90（0=不遮，60% 黑遮罩曾让图片几乎不可见、像设置失败）；③滑杆仅在已选图时显示。开关实测功能正常（tap x≈830 标题行右侧）。
- **液态玻璃"没效果"根因修复（2026-08-14，用户三次反馈）**：①浅色模式光斑用 `BlendMode.Screen` 叠加在近白底上=数学上不可见（Screen 只适用于深底）→ 浅色改 SourceOver 直叠高饱和色斑；②背景全平滑渐变、无高频细节，模糊无从体现 → 新加 **bokeh 锐利光点**（drawCircle 小圆点，卡片模糊后与背景形成清晰度差）；③参数强化：blur 3→30 提到 **10→38dp**、surfaceAlpha 降到 0.10→0.34（更透）、Haze 加蓝色 Plus tint 0.06 + noiseFactor 0.05；④深色 bloom alpha 0.16-0.38 提到 0.30-0.60 + 新增粉/青斑；⑤BackgroundCard 深色镜面高光 0.35→0.55。模拟器实测：浅色边缘底 R138G168B239（饱和蓝，原灰白 206,208,212）卡内 R162G175B240 磨砂白蓝；深色深空蓝底+霓虹光斑。Haze 1.6.10 HazeStyle API：`HazeStyle(blurRadius, noiseFactor, fallbackTint, tints=listOf(HazeTint(color, BlendMode)))`（tint 单参是 fallbackTint）。
- **旧键迁移注意**：prefs 里残留 `force_dark`（旧语义 null=跟随系统）→ SettingsPrefs 迁移为 themeModeValue（false→1 强制浅色），会导致 `cmd uimode night` 不生效。测试深色需在设置页点「深色模式」chip 或清 force_dark 键。
- **Zalith/FCL 版式移植（2026-08-14，用户"可以模仿FCL呀Zalith"，替换上一轮自创布局）**：主页=Zalith LauncherScreen RightMenu 启动面板（状态球+品牌为 avatar 位；当前实例选择器 VersionManagerLayout——图标28dp+名称+摘要跑马灯，点击弹 DropdownMenu，行点击=切换当前实例、行尾 ▶=直接启动；运行信息行 labelSmall alpha0.7；底部 56dp 全宽大启动按钮；导航全走底栏，快捷入口删除）；服务端=Zalith VersionsManageScreen（全页单张 BackgroundCard 画布；顶部横向滚动工具条=新建/导入 jar + 分类 chip 带数量「全部(N) 官方(N) 性能(N) 模组(N)」，按 coreType.category 筛选；列表项 VersionItemLayout=RadioButton 单选当前实例+34dp 图标+名称/摘要跑马灯+信息行 alpha0.7+启停钮+⋮菜单；FAB 已删）。设置页保持 BiliPai 体系不动。**踩坑：Compose 局部变量遮蔽——listOpen 声明两次导致点击设外层、DropdownMenu 读内层，下拉永不弹出（编译通过、无警告）**。参考源码 .tmp/ref/zl_versions_manage_screen.kt / zl_launcher_screen.kt / zl_versions_manage_elements.kt（GPL-3.0，已记 NOTICES）。

## 架构（多开）
- DefaultServerManager 已重构为多开会话：`ConcurrentHashMap<String, RuntimeSlot>`（每实例独立 Process/state/uptime/console），环境与 Java 安装用 AtomicBoolean 互斥；states 汇总流 `Map<instanceId, ServerState>`。
- 控制台每实例独立：`consoleFor(id)` 保留历史；AppViewModel 用 collectLatest 跟随 currentInstanceId 切换。
- `ServerProperties`：server.properties 读写 + `findFreePort`（25565 起自动分配，创建实例时写初始文件）+ `ensureInitial`。
- **新建服务端为全屏两段式流程页 `NewServerScreen`**（2026-08-14 用户要求推倒重做，旧 NewInstanceDialog 已删除）：阶段①核心类型大卡片网格（Zalith 加载器卡片风，导入 jar 直接触发文件选择）→ 阶段②搜索+正式/快照筛选+版本列表（FCL 风，整行可点，40 条上限提示）+ 配置卡（名称/内存/Java 覆盖/端口提示）+ 底部「下载并创建」。参考 FCL VersionInstallPage、Zalith SelectGameVersion/DownloadGame 系（GPL-3.0）。
- **Java 不内置（用户明确要求，2026-08-14）**：rootfs 为纯净 Ubuntu base；Java 8/17/21 通过设置页「Java 运行时（可选下载）」卡片按需 apt 安装/删除；新建实例可手动指定 Java 版本（自动推断默认，兼容性覆盖）。
- 附加组件：`ModrinthApi`（v2 开放 API 搜索/版本）+ `AddonManager`（plugins/mods 目录、*.jar.disabled 启停、CDN 下载安装）；AddonsScreen 支持 Paper 插件与 Fabric/Forge 模组（supports() 按核心类型门控）。
- 核心类型已全量解锁（Vanilla/Paper/Purpur/Spigot/Fabric/Forge/NeoForge/导入）。

## 待办（全面完善路线，目标 goal-a807f30d）
- 已全部实现（2026-08-14）：①多开 ②实例详情+server.properties 编辑器 ③插件管理（Modrinth）④模组管理 ⑤备份/恢复/**导入导出**（SAF CreateDocument/OpenDocument）⑥**玩家管理**（ConsoleParser 解析 list 响应 + join/leave 事件 + OP/白名单/踢出快捷命令）⑦**崩溃报告 + latest.log 查看页**（LogsScreen，读文件末尾 300KB）。
- 模拟器已验证：备份 52MB zip 创建/恢复/导出按钮、日志页空态、玩家管理卡渲染；Java 安装+服务端运行仍受**模拟器 app-data 存储损坏**阻塞（应用代码在 18:59/19:29 两次环境部署全通过）。
- 踩坑：Kotlin 嵌套注释——注释里写 `usr/*`、`crash-reports/*.txt` 都会吞掉后续代码（"Unclosed comment"）。
- 参考源码已存 .tmp/ref/：Zalith LauncherScreen/VersionsManageElements（VersionItemLayout 卡片）、FCL VersionListItem/ProfileListAdapter/VersionInstallPage。
