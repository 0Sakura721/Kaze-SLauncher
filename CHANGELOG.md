# Changelog — Kaze SLauncher

> 遵循 [Keep a Changelog](https://keepachangelog.com/) 格式。
> 仓库：github.com/0Sakura721/Kaze-SLauncher · GPL-3.0

---

## [Unreleased]

### Added
- **增量补丁接入更新流程**：`UpdateInstaller.download()` 现在**先试补丁**——
  用本机已安装 APK（`applicationInfo.sourceDir`）的 sha256 去比对补丁元数据里的
  `baseSha256`，匹配就下补丁（1.6 MB 级）并拼装；任何一步不成立（没有补丁资产、
  基线不匹配、下载失败、拼装校验不过、签名不符）都**静默回退整包**。
  补丁是加速手段而不是必经路径，它失败绝不能让用户更新不了。
  拼装结果先比 `targetSha256`（`ApkPatchApplier` 内部）再比**签名证书**——
  两道都与整包路径一致，安全性没有放松。
- **CI 自动出补丁**：新增 `.github/workflows/release.yml`，打 `v*` tag 时自动
  跑测试 → 构建两个 ABI → **对上一个 release 的同 ABI APK 生成增量补丁** →
  创建/更新 release 并上传 APK + 补丁（命名 `patch-<from>-to-<to>-<abi>.{zip,json}`）。
  发布说明从 CHANGELOG 同版本段落抽（新增 `tools/extract_release_notes.py`，
  版本段落缺失时写占位说明而不是留空）。工作流会先校验 tag 与 `versionName` 一致。
- `UpdateChecker.pickPatchAssets()`：从 release 资产里挑出 `patch-*.json` 且配对的
  `.zip` 必须存在（纯函数，5 例单测）；不按 ABI 过滤 —— 该用哪份由 `baseSha256`
  决定，名字里的 arch 只是辅助。

### Fixed
- **更新弹窗把原始 Markdown 直接显示、而且被截断**（真机截图实锤）：原来
  `Text(info.body.take(400))` —— 用户看到的是 `## v0.3.1-fix`、`>`、`**修复向**`、
  `| 表格 |`，而且正文只露出开头几行。现在新增 `ReleaseNotes.toPlainText()` 把发布正文
  转成可读文本（标题 → 【】、引用去 `>`、加粗/行内代码/链接去标记、表格 → `·` 连接、
  分隔线 → 空行、首个含版本号的标题丢掉——弹窗标题已经写了版本），
  弹窗正文改成**固定高度 + 可滚动**、显示全文。
  踩到的坑：发布正文是**硬换行**的，`**加粗**` 很容易跨行，逐行跑正则会漏掉那一对
  （真机就是这么漏的）→ 必须先按段落合并软换行再清标记。
  新增 `ReleaseNotesTest` 7 例（含"跨行加粗"回归与"真实发布正文"用例）；
  并修了测试基建：`-Dkaze.*` 现在会透传给测试 JVM —— 此前 Gradle 的 `-D` 只作用于
  守护进程，两条"吃真实产物"的用例（真实补丁互通、真实发布正文）**一直在静默跳过**，
  绿的但什么都没验。

---

## [0.3.1-fix] — 2026-09-26

### Added
- **APK 增量补丁（补丁合成更新）—— 核心已实现并验证**：新版本只下"变化的那部分"，
  由客户端把**本机已安装 APK** 里没变的条目拼进来，合成与官方包**逐字节相同**的新包。
  - 实测：0.3.0 → 当前构建，103 个条目里 **83 个可本地拷贝**，补丁 **1.61 MB = 整包的 5.2%**
    （整包 30.8 MB）。大头是 `libproot.so` 等原生库，逐字节不变。
  - 格式 `kaze-apkraw-1`（参考 Operit 的 apkraw 思路、独立实现）：补丁 zip 携带
    `meta.json`（`baseSha256`/`targetSha256`/`entries[]`）+ `tail.bin`（中央目录 + EOCD +
    **APK 签名块**）+ 变化条目的**原始本地记录**。客户端按目标包的本地记录顺序重排，
    `copy` 从本机包拷、`add` 从补丁拷，再追加尾部。
  - 为什么不用 bsdiff/hdiffpatch：不需要 NDK、不用把 30MB 读进内存；更关键的是
    **输出与官方包一致，所以签名仍然可验**（普通差分合成的包验不过 APK 签名）。
  - 三道校验：打补丁**前**比对本机 APK 的 sha256 == `baseSha256`（用户装了别处来的包
    或版本不对就直接走整包）；拼装**后**比对 == `targetSha256`；交给安装器前仍由
    `UpdateInstaller` 比对**签名证书**。
  - 新增 `tools/make_apk_patch.py`（CI 侧生成）与 `core/update/ApkPatchApplier.kt`（客户端拼装），
    格式两端严格对应；新增 `ApkPatchApplierTest` 4 例：逐字节往返、基线不匹配拒绝、
    补丁被篡改被 sha256 拦下、**真实生成器产出的补丁能被拼装**（Python ↔ Kotlin 互通）。
  - 过程中被测试抓出两个真 bug：中央目录解析用了**大端**读（zip 是小端，导致目录解析成空表）；
    本地记录长度照**中央目录**的 extra 算（应与**本地头**一致，两者可不同），
    以及未处理 **data descriptor**（flag bit 3）。
- **应用内「诊断日志」**（设置 → 关于与许可证 → 诊断日志）：应用自身的 logcat 落盘 +
  环境自检合在一页，可刷新 / 分享 / 清空。
  - 为什么需要：Android 的 logcat 只在**内存环形缓冲**里（main/system/crash 各 256KiB），
    应用一崩、系统一回收就没了；普通应用也没有 `READ_LOGS` 权限读不到别人的日志。
    但**可以读自己的** —— 以自身 UID 跑 `logcat --pid=<自己>`，输出的正是本应用能看到的
    条目，包括系统为我们打的 `AndroidRuntime: FATAL EXCEPTION` 堆栈。接进文件即可，
    不用插电脑、不用 root 就能事后追溯（`files/logs/app-<日期>.log`，留最近 3 天、
    单文件 2MB 上限，服务端刷屏也写不满存储）。
- **闪退也一定留下现场**：日志采集器跑在应用进程里，进程一闪退它就跟着死，系统最后打的
  `FATAL EXCEPTION` / native 崩溃根本来不及落盘。两条补法：
  - **崩溃处理器**：`Thread.setDefaultUncaughtExceptionHandler` 在崩溃线程上**同步**写出
    完整堆栈（含 cause 链）再交给系统，保证 Java 崩溃有记录；
  - **启动时补捞**：logcat 的环形缓冲在进程死后**仍在内存里**，所以下次启动用
    `logcat -d --uid=<自己> -t 800` 把上一段的尾巴捞回日志文件并标注「闪退现场」——
    实测（MuMu 上 `kill -11` 制造真闪退）成功捞到
    `F DEBUG : signal 11 (SIGSEGV) ... SI_USER` 与周边的 crash_dump 行。
  - 新增 `AppLogStoreCrashTest` 3 例（堆栈完整性 / cause 链不丢 / 目录位置）。- **实例/存档目录默认改为手机根目录的 `KazeS/`**（如 `/sdcard/KazeS`）：存档、服务端 jar、
  运行日志、备份都在用户能用文件管理器直接看到、直接拷贝的地方，不用记
  `Android/data/...` 这种路径。写共享存储需要「所有文件访问」（Android 11+）或
  WRITE_EXTERNAL_STORAGE（11 以下），所以采用前会**实地探测可写**（写一个探针文件），
  没授权时退回 app 外部私有目录，不会出现"实例建到一半才发现写不进去"。
  旧默认目录里已有的实例会尝试 rename 搬过去（同分区内是瞬时的）；跨挂载点搬不动时
  留在原处照常可用（实例记录存的是绝对路径），设置页文案同步更新。

### Changed
- **「选择服务端核心」页改用各项目的官方图标**（此前是几何形状的 Material 图标 + 配色，
  Purpur / Spigot / Fabric / Forge / NeoForge 之间只能靠颜色区分）。现在 Vanilla 是草方块、
  Paper 是纸飞机、Purpur 是紫色立方、Spigot 是水龙头、Fabric 是线轴、Forge 是铁砧。
  图标统一归一化为 192×192（透明的裁掉留白后垫浅灰底，否则 Spigot 的深灰 logo 在深色卡片上看不见），
  首页实例下拉里的同一组件（28dp）一并生效。来源与授权见 `THIRD_PARTY_NOTICES.md`；
  原版那张草方块是**本项目自绘的像素图**，不含 Mojang 贴图素材。

### Fixed
- **控制台从别的页面切回来时日志停在旧位置、得自己往下翻**：自动滚底用的是
  `LaunchedEffect(lines.size)`，而 lines.size 每来一行就变一次 —— **每次变化都会取消
  上一个 effect**，`animateScrollToItem` 这种持续多帧的动画被取消就停在半路。平时
  "人已经在底部、只差 1 行"看不出来；切页回来时差了几百行，动画永远走不完，且服务端
  持续输出会让 Compose 一直不空闲。改成单个 `snapshotFlow` 收集器（滚动不被新行打断），
  并按距离区分：差得少平滑滚、差得多（切页回来/恢复跟随）直接跳。
  新增 `ConsoleFollowTest` 3 例钉住；旧实现下 2 例失败（ComposeTimeoutException）。
- **控制台日志上限 2000 行提到 5000 行**：原先 `ConsoleStream` 环形缓冲与 ViewModel 里
  三处 `takeLast(2000)` 各写各的。现在统一为 `CONSOLE_MAX_LINES`，行数标签到上限时会
  显示「5000 行（上限）」。完整日志始终落盘在实例目录的 `console-output.log`，
  不受这个上限影响（「保存日志」与实例日志页都拿全量）。
- **Forge/NeoForge「装了一半」导致永远起不来**：安装完成的判据原来是"有 `unix_args.txt`
  就算装好"，而安装器是**先**写入口文件、**后**下那 60 多个依赖库的 —— 中途失败（断网、
  DNS 不通、取消）会留下一个"看着装好了、库却不全"的目录，之后每次启动都跳过安装，
  启动时报一长串 `Missing required library` 并退出，用户除了删实例重建没有出路。
  改为以安装成功标记（`.forge-installed`，仅在 installer 退出码为 0 时写）为准；
  有入口文件而无标记时日志会明确提示"上次安装未完成，正在补齐"，installer 幂等、重跑即可自愈。
- **容器内 DNS**：proot 里从来没有配过 `/etc/resolv.conf`，容器内联网的程序一律解析失败
  （apt 静默失败、Forge 安装器直接 `UnknownHostException`）。现在按系统当前 DNS 写一份并
  在每次启动容器时绑定进去，换网络自动更新。
- **首页「启动服务端」旁的控制台快捷入口切不回主页**：该入口用的是裸 `navigate()`，
  绕过了底栏的 `popUpTo/saveState/restoreState` 语义，两种跳转混用后返回栈不可用。
  已统一为同一个 `navigateTab`。
- **版本页那个"常驻"的进度条**：波浪进度条的相位在定量态也一直流动，步骤指示条因此无休止
  流动；同时 `loadVersions/loadBuilds` 在任务被取消或抛异常时不复位 `loading`，
  进度条会永久停留。步骤条改为静态，加载状态用 `try/finally` + 请求序号复位，
  并加了 45s 的界面可见超时与「重试」按钮。

---

### Security / 健壮性（一次全仓排查后的修复）
- **实例库可能被一次半截写入清空**（数据丢失）：`load()` 解析失败静默返回空列表，
  紧接着 `rescan()` 把空列表写回文件，用户的全部实例永久消失。现在解析失败先把坏文件
  留档为 `instances.json.corrupt-<时间戳>`、再退回上一次的 `.bak`；写入改为
  "临时文件 + fsync + rename"原子落盘，每次成功写入前保留上一份为 `.bak`。
- **实例库移到内部存储**：原位置 `getExternalFilesDir` 在外部存储未挂载时返回 null，
  `File(null, "instances.json")` 会退化成相对进程 CWD 的路径（写不进去却没人知道）；
  Android 7–10 上其它应用也能改写它。旧文件一次性自动迁移。
- **保存失败不再静默**：读取告警与保存失败分别通过 `loadWarning` / `saveError` 暴露。
- **自更新 APK 增加签名校验**：哈希只在 GitHub 提供 `digest` 时才存在，而 APK 字节来自
  多个第三方加速镜像 —— 镜像被控制就能返回"魔数合法、体积足够"的包。现在额外比对 APK
  与本应用的签名证书，不一致或取不到签名信息一律判为不可用。
- **`runCommand` 的超时分支原本永远返回不了**：`readJob` 是 `withContext` 的子协程，
  proot 被杀后 guest 可能仍持有 stdout，阻塞读不会 EOF → 结构化并发一直等它，
  "有界超时"变成**永久挂起**（Java 安装 / apt 卡住时只能杀应用）。现在超时分支按
  SIGTERM → 关管道 → 强杀的顺序收尾。
- **JDK 判定要求虚拟机本体**：`bin/java` 只是约 100KB 的启动器，真正的 `libjvm.so`
  最后才落盘 —— 半截安装会被认成"已就绪"，服务端起不来又永远不会重装。现在必须有
  `libjvm.so`（server 或 client）才算装好。
- **实例状态表改为原子更新**：并发读改写会丢更新，表现为"已停止的实例仍显示运行中"，
  进而停不掉也删不掉。
- **控制台列表不再按下标回读实时 State**：后台协程整体替换 `lines`（切实例/清空）时
  可能越界崩溃，改为直接持有元素。
- **首页「重启」此前点了没反应**：它直接调 `startInstance`，运行中会被
  `guardActiveStates` 挡掉。现在走真正的"停止 → 等状态离开 Running/Stopping → 启动"。
- **端口允许空串**：清空端口再保存会写下 `server-port=`，端口占用统计随之漏掉该实例，
  新建实例会撞端口。空串现在被拒绝。
- **幻影实例**：备份/恢复用的 `restore_tmp_*` / `restore_old_*` 目录里有 jar，会被目录
  扫描当成实例并写进 JSON；现在扫描跳过隐藏目录与 `restore_` 前缀。
- **路由参数做 URL 编码**：实例 id 在目录扫描恢复路径下等于目录名，含 `#` 时会被当成
  fragment 截断，实例详情页一闪即退。
- **Lint**：去掉 `liquidGlassLensSafe` 上与实现（内部已自守卫）矛盾的 `@RequiresApi`，
  它在调用点被误报成 NewApi error。

### Removed（构建内）
- **液态玻璃从软件构建中彻底移除**（**代码完整保留**，随时可接回）：
  - 设置页删掉整个「液态玻璃」分区（玻璃模式 / 原生模糊 / 玻璃强度）与两个对话框，
    顶部分类条从 7 个分区变回 6 个（外观 / 背景图 / 存储 / Java / 后台 / 关于）；
  - 「主题样式」里不再出现 GLASS 选项（连那条"已封锁"提示一起删掉），
    外观分区副标题里残留的「玻璃强度」也清了；
  - `AppRoot` 底栏的玻璃链改为**编译期常量** `isGlass = false`。这一点是关键：
    原来写的是 `LocalAppTheme.current == GLASS`，R8 无法证明它恒假，于是整条玻璃链
    （`glassBackdropBlur` / `liquidGlassLensSafe`、`theme/blur/` 与 `theme/shader/` 两个包、
    `LiquidGlassEffect.kt` 里的 shader）都因"运行时可能可达"而被打进 APK；
    写成常量后编译器直接常量折叠，全部剥离。
  - 仓库里保留未引用的：`ui/theme/LiquidGlassEffect.kt`、`theme/blur/`、`theme/shader/`、
    `GlassMode`/`glassParams`/`LocalGlassMode` 等令牌、`SettingsPrefs` 的玻璃项、
    `AppThemeMode.GLASS` 枚举与玻璃配色分支，以及各处说明性注释。
  - **实测验证**：对 release dex 搜玻璃相关的**字符串字面量**（R8 会重命名类/方法，
    类名不可靠）——`cornerRadii` 3→0、`refractionHeight` 4→0、`refractionAmount` 3→0、
    `depthEffect` 3→0、`KazeGlass` 2→0、`uniform`（shader 源码）6→0，全部归零。

---

## [0.3.0] — 2026-09-25

> 这一版把界面整体重做到 Material 3 Expressive，并修掉一批「真机根本跑不起来」的问题。
> 在这之前，**v0.2.0 在任何 arm64 真机**上都部署不了环境——四个缺陷叠在一起，而模拟器是
> x86_64 + houdini，跑 proot 直接段错误，所以这些问题在开发环境里从未暴露。

### Added
- **设置页顶部分类条**：7 个分区（外观 / 玻璃 / 背景图 / 存储 / Java / 后台 / 关于）横向排列、
  固定在顶部；点击平滑滚到对应分区，滚动时自动高亮当前分区并把它滚进可视范围。
  分区偏移用 `onGloballyPositioned` 实测而非写死下标（条目高度随文案换行变化，
  Java 分区条目数还取决于已安装版本数）。
- **Java 安装情况自动检测**：扫描 rootfs 的 `usr/lib/jvm`，优先读 `release` 文件里的
  `JAVA_VERSION`（权威），读不到再从目录名解析，因此 apt 装的、Adoptium 解压的、
  以及标准列表之外的版本都能认出来；进设置页时自动重新检测，行内显示确切版本
  （如「已安装 · 17.0.20.1」）。

### Changed
- **界面重构为 Material 3 Expressive**。设计不是手写的：用 [M3E Canvas](https://github.com/lnkiai/m3e-canvas)
  在代码里构造画布文档、跑它自己的 prompt 引擎导出 9 屏设计稿，再据此重做界面。
  设计源（整包 prompt、逐屏 prompt、画布 JSON、可直接在浏览器打开的分享链接、生成脚本）
  全部入库于 [docs/m3e](docs/m3e/)。
  - 新增 `ui/theme/Expressive.kt`：官方形状 / 动效 / 排版令牌。本项目锁在 Compose BOM 2024.12.01
    （material3 1.3.1，**没有** Expressive API），所以按 androidx token 落常量 —— 6 组 spring、
    缓动曲线与时长、Expressive 圆角阶（卡片 20dp / 对话框 28dp / 全圆按钮）、
    强调字阶（15 个字阶的 size 与 lineHeight 不变，只提字重与字距）。
  - 新增 `ui/components/M3EComponents.kt`：Expressive 版式组件层（卡片三变体、72dp 列表项、
    相连列表 28/8dp 圆角、屏幕头、状态胶囊、指标块、分段选择）。可点组件统一带涟漪与轻微缩小反馈。
  - 新增 `ui/components/LoadingIndicator.kt` + `LoadingShapes.kt`：官方「会变形的」加载指示器。
    7 个形状取自 Material Design 形状资产，每 650ms 变一次（一个循环 4.5s），变形按官方的
    0.6/200 弹簧回落（峰值过冲约 9%，用解析式而不是 Animatable —— 形状序号是跳变目标，
    有限动画追不准，也会让 Compose 测试等不到空闲），旋转按 50°+90°/形 的模型并补掉循环余量，
    使跨形不跳。它同时接管了原来的「状态球」：运行中常速、启动中加速、停止时定格成单个形状。
  - 新增 `ui/components/WavyProgress.kt`：官方波浪形线性进度条（容器 10dp、波幅 3dp、波长 40/20dp，
    二次贝塞尔而非正弦），部署与安装进度改用它。
  - 删除旧设计层：`BackgroundCard.kt`（整页大卡框架，重构后已无任何调用）、
    `StatusOrb` 的自绘圆环/玻璃球（其职责由形状变化指示器接管，`StatusTone` 保留在
    `StatusTone.kt`），以及 `Theme.kt` 里只服务于它们的 `cardColor/cardShape/cardTitleColor/
    itemColor/serverItemBorderColor` 等助手。主页的实例选择从下拉菜单改为「点卡片展开」，
    「运行概况」并入实例卡正文。
  - 新增 `LoadingShapesTest`（7 形的点数一致 / 归一化居中 / 等弧长重采样 / 插值不外溢与不塌缩）
    与 `ExpressiveComponentsTest`（组件层截图）；屏幕截图补上有实例时的版式与深色版。
  - **常驻底栏（液态玻璃浮动胶囊）本次刻意不改**，仅确认它与新内容的相对位置。

### Fixed
- **真机上一部署环境就失败（四个叠加缺陷）**：
  1. `targetSdk` 35 → **28**。Android 按 targetSdk 选 SELinux 域，≥30 落到 `untrusted_app`，
     AOSP 用 `neverallow` 禁止 execve 应用私有目录中的文件（W^X），而 proot 必须执行 rootfs
     里的 guest 二进制 → `execve("/usr/bin/sh"): Permission denied`。Termux / PojavLauncher
     同样停在 28。
  2. **usrmerge 兜底绑定根本不存在**：`repairRootfsLinks` 的注释声称"建不出符号链接时由
     `buildProotCommand` 的 `-b` 绑定负责映射"，但绑定列表里从来没有 `/bin` `/lib` `/sbin`
     → `'/bin/sh' not found`。现在真的补上绑定，并改用 `/usr/bin/sh`（真文件）而非 `/bin/sh`。
  3. **`ensureMultiarchLinks` 在 arm64 上从不生效**：守卫检查的是硬编码的
     `usr/lib/arm-linux-gnueabihf`，而 arm64 rootfs 里只有 `aarch64-linux-gnu` → 函数第一行
     就 return，`usr/lib/ld-linux-aarch64.so.1` 等顶层 soname 软链**从未创建**，
     dash 的 PT_INTERP 解析不到，proot 一步都跑不动。
  4. **解压不还原 tar 权限**：只按路径给 `bin/` 与 `libexec/` 加 x，而 Java 创建的文件默认
     0600 → `usr/lib` 下的共享库（含动态链接器）全都没有执行位，execve 返回 ENOENT。
     现在解析 tar 头的 mode 字段并用 chmod 应用。
  配套：`repairExecPermissions` 对**已经解压过**的 rootfs 幂等补齐执行位（老用户不必删掉重来）；
  `dumpDiagnostics` 把 rootfs 关键文件的存在性 / 权限 / SELinux 上下文与两项 exec 实测写到
  外部目录，真机没有 root 时也能直接取到（应用每次启动写一份）。
- **启动瞬间整屏位移**：window insets 异步下发，首帧组合时 `WindowInsets.statusBars` 仍为 0，
  内容先按"没有状态栏"布局（标题与状态栏图标重叠），insets 到达后整屏下移一个状态栏高度
  （720p 实测 76px、持续约 0.45s）。现在 insets 就绪前只画背景不画前景。
- **部署失败被静默吞掉**：阶段 3 的 `apt-get update` 结果无人查看，离线或源不可达时照样报
  「环境已就绪」，之后所有 `apt-get install`（装 Java）都失败且重试无用。现在失败即明确报错，
  重试会补做这一步（已解压的环境不会重复下载）。
- **Java 明明装了却显示「未安装」**：判定用 `bin/java` 体积 > 1MB，而 OpenJDK 的 `bin/java`
  只是约 100KB 的启动器（真正的大头是 `lib/server/libjvm.so`）→ 任何正常安装都被判成未安装。
  服务端能跑是因为启动流程自己拼路径、没走这个检测，问题才被掩盖。
- **实例摘要被断词截断**（`Java 21 · 4096 M…`）：`M3EListItem` 固定 72dp 高，而正文允许两行，
  第二行被压掉。改为最小高度，长内容让行高自然增长。
- **每种核心给独立图标与配色**：此前 7 种核心只有 3 种图标，Purpur / Spigot / Fabric /
  Forge / NeoForge 全是同一个灰块，新建向导里连着 5 个一模一样的灰方块。
- 控制台空状态用一个定格的灰椭圆表示"待命"，在空荡荡的日志区里读起来像渲染残渣，换成终端图标。
- 「EULA 未接受」占掉约 55dp 把标题挤到不足 90dp，改为紧凑的警示图标（语义保留给读屏）；
  玻璃设置页的长说明句被省略号截断，已精简。
- 设置页「AMOLED 纯黑」在浅色模式下无效且无提示；AMOLED 覆盖漏了 `surfaceBright`
  （M3 卡片底色用的就是它）。
- CI：`android-actions/setup-android` 内部会安装 Google 已下架的旧 `tools` 包，必定失败；
  去掉该 action 后 `sdkmanager` 不在 PATH，改为显式定位 runner 预装的 cmdline-tools。

### Removed
- **不再发布 `universal` 包**。安装包只出 arm64-v8a 与 armeabi-v7a 两个；
  更新器的选包逻辑同步改造（精确后缀 → 名字含本机架构 → 旧命名 → universal → 单个安全包），
  **任何情况下都不会把别的架构的包发给用户**，并补了 8 个针对性测试。
- v7a（armeabi-v7a）包标记为 **experimental**：在真机上验证得少，请优先用 arm64 包。

---

## [0.2.0] — 2026-09-11 · 替换构建 2026-09-12

> 本版本为**替换构建**（2026-09-12）：0.2.0 已发布的安装包替换为含完整审计修复的构建，
> 版本号与签名不变，可直接覆盖升级。

> ⚠️ **本版更换了签名密钥**：旧版（≤ v0.1.2）用公开的 Android 调试密钥签名，本版起改用正式发布密钥。
> Android 不允许签名不同的包互相覆盖，**安装前必须先卸载旧版**（实例与存档在外部存储，不受影响）。

### 替换构建新增（2026-09-12）

主要内容是**一轮系统性的交互逻辑审计与修复**：四路并行审计（新建向导 / 实例启停与控制台 /
设置主题更新与环境 / 导航骨架与横切）后逐条复核。

#### Added
- **Forge / NeoForge 支持启动**：补齐 `--installServer` 安装步骤。现代版（Forge 1.17+ / NeoForge）
  用 `@user_jvm_args.txt` + `@unix_args.txt`，旧版（≤ 1.16.5）用 `-jar forge-*.jar`，两者都追加 `nogui`。
  是否已安装以"启动入口是否存在"判断，避免每次启动重装。
- **核心构建 / 加载器选择**：Paper 可选 build、Fabric 可选 loader（都只列稳定项）；
  没有构建列表的核心自动隐藏该入口，不给假选项。
- **安装时的服务器设置**：端口（留空自动分配）、最大玩家数、正版验证、默认游戏模式，
  创建时写入 `server.properties`。
- **EULA 安装时显式勾选**，未同意不允许创建；进入配置页时按「核心-版本」自动填可改的实例名。
- **CI**：推送与 PR 自动跑单元测试并产出界面截图。
- **Roborazzi 截图测试**：组件级 + 屏幕级，纯 JVM 渲染，不需要模拟器或真机。

#### Fixed
- **实例名可逃出实例根目录（删库级）**：`.` 与 `..` 不含被替换的非法字符，会被原样用作目录名
  → `File(instancesRoot(), "..")` 解析到实例根的**父目录**。删除这种实例时 `deleteRecursively()`
  会清空全部实例、世界存档与 `instances.json`。现在净化首尾点号并用 canonicalPath 断言不越界。
- **同名实例复用同一目录**：向导默认名是「核心-版本」，不改名连续建两次会落到同一目录，
  第二条的 jar 覆盖第一条、新建时的属性覆盖项还会改写第一条的 `server.properties`。
- **Fabric 永远创建不了**：核心 jar 校验下限是 1MB，而 Fabric 官方 server launcher 实测仅
  181,840 字节 → 每次下载都被判非法、删文件重试，最终报"所有源不可用"。
- **Spigot 永远创建不了**：`download.getbukkit.org` 已 NXDOMAIN，换到仍在服务的 `cdn.getbukkit.org`。
- **Paper 版本类型判定永久失效**：v3 API 的 version 对象没有 `releaseChannel` 字段（读取恒为 null）
  → 66 个版本全判正式版，12 个 rc/pre 混入；叠加排序缺陷导致「正式版」筛选第一行是候选版。
  改为按版本号预发布标记判类型，并让正式版排在预发布版之前。
- **导入 jar 100% 失败且会删掉用户文件**：调用方已把文件拷进实例目录，`importJar` 又建同名目录并
  `copyTo(overwrite=true)` → 先删目标（=删源）→ 抛异常 → catch 再 `deleteRecursively()`。
  另外复制在主线程（几十 MB 的 jar 会 ANR）、失败被静默吞掉、Toast 在 IO 线程调用会崩。
- **Android 8.0–10 点「选择目录」崩溃**：`Environment.isExternalStorageManager()` 是 API 30+ 的方法
  而 minSdk 27，且不在任何 try 内（`NoSuchMethodError` 属 Error）。11 以下改走存储权限申请。
- **按返回键退出会取消进行中的部署/下载/启动**：这些长任务原本在 `viewModelScope`（绑 Activity）里，
  与"前台服务守护、后台不被打断"的承诺相反。改用应用级作用域，并单独放行 `CancellationException`
  （此前会被记成"启动失败"）。
- **强杀只杀 proot 宿主**：直接 `destroyForcibly()` 使 proot 来不及执行 `--kill-on-exit` 清理，
  guest 里的 java 会脱离继续跑（界面显示已停止、实际仍占着端口与世界文件）。改为先 SIGTERM。
- **启动期间没有任何停止入口**：部署 / 装 Java / 下载核心 / Forge 安装可能几分钟，此前按钮全是禁用态，
  用户只能删掉实例。现在 busy 时三个页面都显示「停止 / 取消启动」，并补齐了启动流程的取消检查点。
- **删除"正在启动"的实例**会与仍在写盘的安装器抢目录（目录被重建、旧 jar 被当成新实例"复活"）
  → 未停稳时拒绝删除并提示。
- **备份恢复可能把新旧数据一起删掉**：换入失败分支的条件不完整，失败期间目录被并发重建时会跳过回滚，
  随后删除旧目录、`finally` 又删临时目录。
- **部署失败后"环境已就绪"短路**：rootfs 解压完即视为就绪，阶段 3（apt 初始化）永远不会补做
  → 之后所有 apt 安装 Java 都失败且重试无用。
- **控制台**：清除只清 UI 不清缓冲（切走再回来整段复活）；自动跟随在 2000 行上限后彻底失效；
  `\r` 覆盖式进度行在实时视图仍逐行刷屏。
- **自动重启死锁**：先把状态置 Starting 再调 `start()`，被自身防重入拦下 → 永不重启且状态永久卡住。
- 运行时长在"停止→再启动"后恒显示 0 秒；导入的自定义核心（Velocity/BungeeCord）永久卡在"首次启动"；
  卸载正在被运行实例使用的 Java；主页当前实例与 `serverState` 不同源导致运行中却显示「启动服务端」。
- **界面**：状态球 `size` 参数从未生效（实际按 0×0 测量，首页完全不显示）；首页空状态与主按钮重复；
  服务端卡片状态改为胶囊、「EULA 未接受」改为醒目警示条；主题预览两种主题看起来一样；
  版本徽章改用真实版本号；输入框页面补键盘避让；状态栏图标跟随主题；状态球动画后台停止。
- **换背景图不刷新**、**取色器 HEX 输入框打不进字**、**详情页未保存编辑被静默回滚**、
  字体/语言变更重建 Activity 丢状态、更新弹窗忽略安装结果、自定义实例目录失效时静默回落。

#### Security
- **更新包补 SHA-256 完整性校验**：APK 更新包从 13 个第三方加速镜像下载，而校验只有
  "体积 > 1MB 且以 PK 开头" —— 任一镜像被控制即可投毒，应用还会引导用户安装。
  改用 GitHub asset 的 `digest`（取自直连的 api.github.com，不经过镜像）做比对。
- 核心 jar 增加 ZIP 魔数校验；卸载/安装类操作的连点竞态统一加了同步守卫。

### 0.2.0 首发内容（2026-09-11）

> ⚠️ **本版更换了签名密钥**：旧版（≤ v0.1.2）用公开的 Android 调试密钥签名，本版起改用正式发布密钥。
> Android 不允许签名不同的包互相覆盖，**安装前必须先卸载旧版**（实例与存档在外部存储，不受影响）。

#### Security
- **发布签名密钥轮换**：`release` 构建类型此前未配置 `signingConfig`，实际发布的是用仓库内公开
  `debug.keystore`（口令明文写在构建脚本里）签名的包 —— 任何人都能伪造一个「签名匹配、版本号更高」的 APK
  被系统当作合法升级安装。现在改为从环境变量 / `local.properties` 读取正式密钥，取不到时产出未签名包
  而**不会**回退到 debug 密钥。轮换步骤与发版检查清单见 [`docs/RELEASE-SIGNING.md`](docs/RELEASE-SIGNING.md)。
- **下载校验补强**：vanilla 服务端下载接上官方清单里的 SHA-1（此前被丢弃）；proot 运行时下载补 gzip
  魔数校验（此前零校验即解压并执行）；`Downloader.validate` 取消「默认放行」的兜底值，强制调用方显式校验。
- `.gitignore` 移除 `!release-keystore.jks` 白名单，避免发布密钥被误提交。

#### Fixed
- **删除实例会清空其它实例的备份**：备份原先全部平铺在所有实例共享的 `backups/` 目录，而删除实例时对该
  目录整体递归删除。现在每个实例的备份各占独立目录，删除只影响自己；旧版留下的备份仍可正常列出与恢复。
- **`survival` 实例会列出 `survival2` 的备份**：列表匹配由实例名前缀改为完整前缀（带分隔符）。
- **服务端可能停不掉**：在「部署 / 首次启动」阶段点停止，旧实现会让界面显示已停止、而 java 进程仍在后台
  运行且无法再停止（占用端口与内存）。现在会置取消标志、由启动流程自行收尾。
- **重新启动实例时误杀新进程**：优雅停止的 10 秒强杀逻辑读取的是「当前 slot 的进程」而非启动时的那个，
  且遗留任务未取消；现在捕获当时进程并在重启前取消旧任务。
- **首启探测进程失控**：探测进程此前未登记进实例会话，用户点停止时既不被强杀路径覆盖、也无人持有引用。
- **并发部署超时判断失效**：`setup()` 的超时分支因标志位恒为 false 而不可达，真卡住时界面永远停在「部署中」。
- **命令拼接转义不完整**：只对含空格的参数加引号，`server(1).jar`、带引号或 `&` 的文件名会导致服务端
  启动即退出且没有可读提示。改为完整 POSIX 单引号转义。
- **控制台切换到运行中的实例显示空白**：实时流不带 replay，而用于回填历史的 `snapshot()` 定义了却无人调用。
- **控制台高负载下静默丢日志**：缓冲区溢出策略改为丢弃最旧，保证最新输出一定到达界面。
- **聊天内容被误判为玩家事件**：玩家说一句 "Alex joined the game" 会凭空多出一个在线玩家；聊天含
  `error`/`warn` 也会整行染色。两者现均按聊天行排除。
- **日志轮转上限失效**：改名失败时仍继续追加，8MB 上限形同虚设并可能撑满存储；现在检查改名结果并退化为截断。
- **版本列表请求无超时**：网络卡住时界面永久转圈且取消不掉（同步 IO 不可中断）；补上连接与读取超时，
  并修掉重定向缺少 Location 时的连接泄漏。
- **设置页显示错误版本号**：硬编码 `v0.1.0`，与 `versionName` 长期不一致；改为读 `BuildConfig.VERSION_NAME`。
- **命令输入框 placeholder 闪烁/消失**：停止时输入框仍可输入（发送按钮禁用），残留文字顶掉 placeholder；运行/停止切换时 placeholder 文字不一致。修复：停止时 `enabled=false` 并清空 input 残留，placeholder 恒定显示「服务端运行后可输入命令」；运行时显示「输入命令（stop / op 玩家名 / say …）」。
- **命令输入框键盘遮挡**：键盘弹出时输入框距键盘上方 96dp（底栏占位未收起）。修复：`WindowInsets.isImeVisible` 监听键盘状态，键盘可见时自动去掉 96dp 底部 padding，输入框紧贴输入法上方。

#### Changed
- **开启 R8 代码压缩与资源裁剪**（`isMinifyEnabled` + `isShrinkResources`）。应用实际只用到 28 个图标，
  此前却把整套 Material 图标打进包（dex 内 5.7 万处 `material/icons/` 引用）：arm64 release 的 dex
  由 16.75 MB 降至 1.24 MB，三个架构的安装包分别缩小约 40% / 42% / 29%。
- **Forge / NeoForge 的失败提示改为明确说明**：这两类核心下载到的是 `-installer.jar`，需先执行
  `--installServer` 生成运行环境，该步骤尚未实现。此前只提示「没有服务端核心 jar」，让用户对着已存在的
  installer 无从判断。**该功能仍不可用**，请使用 Paper / Purpur / Fabric 或「导入 jar」。
- 备份导入的文件名做了路径穿越防护。

#### Removed
- 删除 `ui/theme/blur/**`（22 个文件、4881 行）与 `ui/theme/shader/**`（2 个文件、178 行）：整包引入的
  compose-miuix-ui 实现，包外唯一引用是 `AppRoot.kt` 中 5 行从未被调用的 import。实际生效的玻璃效果走
  `LiquidGlassEffect` + `util/StackBlur.java`。

#### Added
- **单元测试**：25 个纯 JVM 测试（无需设备）—— 版本比较（含「同号正式版 > 预发布」的预发布段语义）、
  `server.properties` 读写与端口分配、控制台解析的聊天误判防护。
- [`docs/RELEASE-SIGNING.md`](docs/RELEASE-SIGNING.md)：发布密钥轮换步骤与发版检查清单。
- **控制台「复制日志」按钮**：右上角「保存日志」左侧新增复制按钮。复制内容改为控制台内存流（与原屏幕所见一致），不再读文件；空日志 Toast「暂无日志」。
- **保存日志同步修改**：「保存日志」导出逻辑改为导出控制台内存流内容（与复制、屏幕三者一致）。

#### 体积对比（开启 R8 后）

| 架构 | 0.2.0 | v0.1.2 | 降幅 |
|---|---|---|---|
| arm64-v8a | **30.6 MB** | 51.0 MB | −40% |
| armeabi-v7a | **27.7 MB** | 47.2 MB | −41% |
| universal | **56.6 MB** | 79.8 MB | −29% |

arm64 的 dex 由 16.75 MB 降至 1.24 MB。

---

## [0.1.2] — 2026-08-28

### Fixed
- **更新检查会下到装不上的包**：改为按设备架构挑下载地址。此前取 assets 里第一个 `.apk`，
  armeabi-v7a 设备会拿到 arm64 包，下载完成后安装失败。选取顺序：
  当前架构 → 旧命名兼容（`-arm64`）→ `universal` → 任意 apk（永远有 universal 兜底）。

### Changed
- `versionCode` 2 → 3。

---

## [0.1.1] — 2026-08-28

### Added
- **应用内更新检查**：走 GitHub Releases API，GitHub 原链 + 多个国内加速镜像测速择优下载，
  经 FileProvider 调起系统安装器（新增 `UpdateChecker`、`UpdateInstaller`、`file_paths.xml`）。
- **armeabi-v7a 支持**：补 `libtalloc.so`，32 位老设备可以安装运行（0.1.0 仅 arm64-v8a）。
- **CHANGELOG.md**：采用 Keep a Changelog 格式，替代原先放在仓库里的 `MEMORY.md`（后者已移出仓库）。

### Changed
- **新建服务端流程重写**：筛选 + 实时搜索 + 版本列表全量展示；启动速度优化。
- **空服自动暂停默认关闭**：默认模板写 `pause-when-empty-seconds=-1`，旧实例缺该键时自动补 -1
  —— proot 下暂停唤醒会卡死，进而触发 Watchdog 崩溃循环。
- 主题取色方案调整；背景图免裁剪；设置页版本徽章由硬编码 `v1.0.0` 修正为 `v0.1.0`。

### Fixed
- **24 项逻辑修复 + 备份/下载/UI 加固**，其中：
  - 下载支持取消；端口分配同步；配置文件原子写（避免写一半掉电损坏）
  - 停止等待与重启守卫；恢复备份守卫；导入 jar 补上结果反馈
  - 键盘弹出时输入框悬浮在屏幕中部（`adjustNothing` 与 `imePadding` 双重压缩）
  - 控制台输入框与底部导航栏重叠
  - 版本检测失效

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

[Unreleased]: https://github.com/0Sakura721/Kaze-SLauncher/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/0Sakura721/Kaze-SLauncher/releases/tag/v0.2.0
[0.1.2]: https://github.com/0Sakura721/Kaze-SLauncher/releases/tag/v0.1.2
[0.1.1]: https://github.com/0Sakura721/Kaze-SLauncher/releases/tag/v0.1.1
[0.1.0]: https://github.com/0Sakura721/Kaze-SLauncher/releases/tag/v0.1.0
