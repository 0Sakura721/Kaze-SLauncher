# Changelog — Kaze SLauncher

> 遵循 [Keep a Changelog](https://keepachangelog.com/) 格式。
> 仓库：github.com/0Sakura721/Kaze-SLauncher · GPL-3.0

---

## [Unreleased]

_（暂无未发布内容；下次发版时把本节内容并入对应版本号）_

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
