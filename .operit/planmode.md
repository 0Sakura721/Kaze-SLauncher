# Kaze SLauncher 2.0 全面重构——超级完整实施蓝图

## 0. 项目全景

### 0.1 一句话定位
Android 上驱动 Minecraft Java 服务端的**最强前端**：多实例、核心市场、实时控制台、性能仪表、备份、JRE 管理，UI 为原创液态玻璃设计语言（默认 PiliPlus 风格，四套主题可切换）。

### 0.2 质量方针
- **分层单向依赖**：ui → data → core（core 不 import 任何 Android UI 类）
- **可编译**：`assembleDebug` 通过是硬门槛；不引入实验性 API
- **自包含**：不依赖网络才能构建（除 Maven 依赖下载）；手写 JSON、手写下载器，最小依赖面
- **体积**：JRE 与 rootfs 不进 APK，APK 目标 < 8MB

### 0.3 验收清单（逐项打勾）
- [ ] `./gradlew assembleDebug` 零错误
- [ ] 产出 `app/build/outputs/apk/debug/app-debug.apk`
- [ ] 四套风格切换编译期完整（StyleTokens 齐全）
- [ ] 引擎层单元测试（JVM）通过（InstanceStore 编解码 / 日志正则 / 路径逻辑）
- [ ] 推送到 GitHub `modn1` 分支成功
- [ ] 工作区无临时残留文件

## 1. 技术栈定版

| 项 | 版本/选型 | 说明 |
|----|-----------|------|
| AGP | 9.0.0 | 模板自带，保守配置 |
| Kotlin | 2.3.10 | 模板自带 + compose 插件 |
| Compose BOM | 2026.01.01 | material3 / icons-extended |
| minSdk / targetSdk | 24 / 35 | 覆盖 7.0+；前台服务 specialUse |
| 导航 | navigation-compose 2.9.0 | 4 个一级路由 + 1 个二级路由 |
| 持久化 | DataStore Preferences 1.1.3（设置）+ 自写 JSON（实例） | 实例需手控格式，设置用 DataStore |
| 下载 | 自写 HttpURLConnection 下载器 | 可控断点续传/取消/镜像回退 |
| JSON | org.json（Android 内置） | 零依赖 |
| 后台 | 前台服务 specialUse | 服务器常驻 + 通知控制 |
| 构建网络 | 阿里云 Maven 镜像 + 华为云镜像 | 已在 settings.gradle.kts |

## 2. 目录与线程模型

### 2.1 进程/线程约定
| 线程 | 用途 |
|------|------|
| Main | Compose 渲染、短操作 |
| `Dispatchers.IO` | 下载、解压、备份、进程读写（引擎 scope） |
| `Dispatchers.Default` | TPS 正则解析、统计计算 |
| 引擎输出泵 | 独立协程循环 readLine |

### 2.2 数据流
```
ServerEngine ──logs(SharedFlow)──> ConsoleScreen 订阅
            ──state/stats(StateFlow)──> Home/Console 仪表
DownloadManager ──state(StateFlow)──> DownloadScreen 进度
InstanceStore ──instances.json──> AppViewModel 列表
SettingsStore ──DataStore──> 主题/风格/语言/内存预设
```

## 3. 引擎层 core/ 详细设计（每个文件到函数级）

### 3.1 engine/ServerEngine.kt ✅（已实现，列出契约供校验）
| 成员 | 签名 | 行为契约 |
|------|------|----------|
| `state` | `StateFlow<ServerState>` | Idle/Starting/Running(pid)/Stopping/Crashed(code) |
| `logs` | `SharedFlow<String>` | 行级日志，extraBufferCapacity=4096 |
| `stats` | `StateFlow<RuntimeStats>` | cpuPercent/memMb/tps/uptimeMs/playerCount |
| `start(context, inst, javaPath)` | `Boolean` | 检查 jar→写 eula→ProcessBuilder(java, jvmArgs, -jar, jar, nogui)→输出泵→监控循环→TPS 探测循环 |
| `stop(graceMs=15000)` | `Unit` | 发 `stop` →轮询 isAlive →超时 destroyForcibly |
| `sendCommand(cmd)` | `Boolean` | 写 stdin+\n |
| `logHistory()` | `List<String>` | 2000 行环形缓冲回放 |
| 日志解析 | — | `TPS from last` → tps；`There are X of a max of Y players` → playerCount |
| `/proc` 监控 | — | utime+stime 差值算 CPU%；VmRSS 算内存 |

### 3.2 engine/JreManager.kt ★（新写）
```
enum class JreStatus { NONE, DOWNLOADING, READY, ERROR }
data class JreInfo(val version: String?, val arch: String?)

object JreManager {
    val status: StateFlow<JreStatus>
    val progress: StateFlow<Float>          // 0..1
    fun detect(): JreInfo?                  // 扫 filesDir/runtime 下目录，读 release 文件或 java -version
    suspend fun download(onProgress: (Float)->Unit): Result<File>   // 镜像回退
    suspend fun extract(tarGz: File): Result<File>                  // 手写 gzip+tar 解压（不引依赖）
    fun verify(dir: File): Boolean          // 执行 bin/java -version 成功即 true
    fun importFromDir(srcDir: File): Result<File>                   // 复制导入
    fun installedDir(): File?
}
```
- 下载列表：Adoptium API `https://api.adoptium.net/v3/binary/latest/21/ga/linux/aarch64/jre/hotspot/normal/eclipse` → 清华 `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jre/aarch64/linux/` 解析 → 阿里 `https://mirrors.aliyun.com/adoptium/`；依次回退
- 解压：TarInputStream（org.apache.commons? 不，手写）→ 使用 `GZIPInputStream + 自实现 tar 解析`（512 字节块头，正则/longname 支持），或降级方案：仅支持 gzip+tar（JDK 的 tar.gz 是标准格式，手写解析约 80 行）
- 校验：`ProcessBuilder(dir/bin/java, -version)` 捕获 stderr 含 `openjdk version` 即通过

### 3.3 download/CoreCatalog.kt ✅（已实现）
| 函数 | 返回 | 说明 |
|------|------|------|
| `listMcVersions(type)` | `List<String>` | Paper: `/v2/projects/paper` versions；Vanilla: manifest 的 release id；Fabric: `/v2/versions/game` stable |
| `resolve(type, version)` | `CoreVersion?` | Paper 取最新 build 的 application 下载；Vanilla 取 version json 的 server.url；Fabric 拼 loader+installer 的 server/jar；Spigot/Forge 返回 null（引导自备） |

### 3.4 download/DownloadManager.kt ★（重写）
```
object DownloadManager {
    val state: StateFlow<DownloadState>     // Idle/Progress/Done/Failed
    suspend fun download(url: String, dest: File, mirrors: List<String> = emptyList()): Result<File>
    fun cancel()
    // 内部：.part 文件 + Range 续传；Content-Length 取总长；完成 rename；镜像逐个失败回退
}
```

### 3.5 backup/BackupManager.kt ★
```
data class BackupEntry(val file: File, val sizeMb: Long, val time: Long)
object BackupManager {
    suspend fun backup(instanceId: String): Result<File>   // zip: world/plugins/configs/server.properties/核心jar（jar 可选排除）
    fun list(instanceId: String): List<BackupEntry>
    suspend fun restore(instanceId: String, backupFile: File): Result<Unit>  // 先重命名现有目录为 .pre-restore
    fun delete(backupFile: File): Boolean
}
```
- zip 名：`<实例名>-<yyyyMMdd-HHmmss>.zip`；排除 `logs/`、`*.tmp`、`session.lock`

### 3.6 service/ServerService.kt ★
- `onStartCommand`：启动引擎或仅挂起；`START_NOT_STICKY`
- 通知：标题=实例名，正文=状态+玩家数+TPS；Action「停止」；点击回 MainActivity
- 前台类型：`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，Manifest property 声明
- `stopSelf` 由引擎 state 变为 Idle/Crashed 时触发

## 4. 数据层 data/ 详细设计

### 4.1 Models.kt ✅ / AppPaths.kt ✅（保持）
### 4.2 SettingsStore.kt ★
```
object SettingsStore {
    val themeStyle: StateFlow<String>        // 键见 Styles.kt，默认 "piliplus"
    val themeMode: StateFlow<Int>            // 0系统 1浅 2深
    val language: StateFlow<String>          // zh/en
    val memoryPreset: StateFlow<Int>         // MB，默认 2048
    val keepAwake: StateFlow<Boolean>
    val maxLogLines: StateFlow<Int>          // 默认 2000
    suspend fun setXxx(...)                  // DataStore edit
    fun init(context)
}
```

## 5. UI 设计系统（超级完整规范）

### 5.1 StyleTokens 结构（每套风格一份）
```
data class StyleTokens(
    val key: String, val label: String,
    val primary: Color, val secondary: Color, val accent: Color,
    val background: Color, val surface: Color, val surfaceVariant: Color,
    val onBackground: Color, val onSurface: Color, val outline: Color,
    val cornerSmall: Dp, val cornerMedium: Dp, val cornerLarge: Dp,
    val glassEnabled: Boolean, val blurEnabled: Boolean,
    val glowColors: List<Color>,             // 背景光斑色
    val dynamicColor: Boolean,
)
val ThemeStyle: enum { PILIPLUS_GLASS, BILI_CLASSIC, MATERIAL_YOU, OLED_NIGHT }
fun tokensFor(styleKey: String, isDark: Boolean): StyleTokens
```

### 5.2 GlassEffects.kt 组件
| 组件 | 说明 |
|------|------|
| `GlassBackground` | 全屏光斑层：3 个大径向渐变圆（粉/蓝/青），`animateFloatAsState` 缓慢漂移；OLED 风格关闭 |
| `glassCardModifier(style)` | Modifier 组合：blur(API31+)→半透明底→渐变描边→左上高光 |
| `FrostedBox` | 通用毛玻璃容器（blur 或降级模拟） |
| `LiquidCapsule` | 贯穿式状态胶囊（渐变底+呼吸灯+文字） |

### 5.3 Components.kt 组件库（签名级）
| 组件 | 签名 | 用途 |
|------|------|------|
| `GlassCard` | `(modifier, style, onClick?, content)` | 通用玻璃卡 |
| `GradientButton` | `(text, colors, enabled, onClick, loading?)` | 主按钮（粉蓝渐变） |
| `MetricRing` | `(value: Float, max, label, color, modifier)` | 弧形仪表（Canvas 描边圆环） |
| `OverlapRingGroup` | `(cpu, mem, tps, style)` | 首页三环交叠错位组 |
| `StatusDot` | `(state: ServerState, modifier)` | 呼吸灯（2.4s 循环缩放+alpha） |
| `LiquidCapsule` | `(text, trailing, modifier)` | 状态胶囊 |
| `SegmentRail` | `(options, selected, onSelect)` | 分段滑轨（内存档位） |
| `TabCarousel` | `(items, selectedIndex, onSelect)` | 横向卡片轮播（首页实例切换） |
| `SideRail` | `(options, selected, onSelect)` | 竖向胶囊选择条（核心类型） |
| `TrackGroup` | `(sections, activeIndex)` | 设置页轨道分组容器 |
| `GlassTray` | `(selected, onSelect, centerAction)` | 液态托盘底导航（中央凸出按钮） |
| `InnerInput` | `(value, onChange, hint, modifier)` | 底线式内联输入（无框无 label 套壳） |

### 5.4 每屏布局（详细线框说明）
**HomeScreen**
```
┌────────────────────────────┐
│ Kaze                [风格] │ ← 32sp 大标题 + 快捷风格圆钮
│ 8月13日 · 周四             │ ← 日期副标题
│ ┌─────────┐ ┌──┐          │
│ │ CPU 环  │╲│TPS│  ← 交叠  │ ← 三环错位组（偏移24dp，破界标签卡）
│ │  ┌──────┼─┘  │          │
│ │  │内存环 │    │          │
│ └──┴──────┴────┘          │
│ ╭───────────────────╮     │
│ │ ● 云之巅服务器  ▶  │     │ ← 液体胶囊（呼吸灯+时长+启动圆钮半溢出）
│ ╰───────────────────╯     │
│ [卡1] [卡2] [卡3]          │ ← 横向3D轮播（当前放大居中）
│                            │
│      [液态托盘导航]        │ ← 中央启动/停止凸钮
└────────────────────────────┘
```

**ConsoleScreen**：全屏终端窗（窗框条三圆点+实例名）→ 日志区 78% → 悬浮命令胶囊（半浮出底边）→ 右侧纵向快捷圆钮列

**InstanceScreen**：左竖排核心类型胶囊条（选中滑入玻璃高亮）｜右侧内联名称输入、版本单选项、内存分段滑轨、底部 EULA 签署条（点按翻转确认）+ 保存玻璃大按钮

**DownloadScreen**：顶部横向大卡片流（140dp 竖卡，选中放大上浮）→ 版本竖排大行距单选 → 整宽玻璃下载按钮（下载中=百分比波动进度胶囊）→ 完成弹出"一键创建实例"浮动卡

**SettingsScreen**：左侧竖排圆点分段指示 + 右侧轨道分组（风格选择器=交错叠放玻璃色卡实时预览；JRE=环形状态徽章；备份=时间轴条目；语言/主题/保活开关组）

### 5.5 动效与交互
- 页面过渡：中央缩放淡入；托盘浮起 240ms
- 按钮水波：`MutableInteractionSource` + 缩放 0.96
- 呼吸灯：2.4s infiniteRepeatable
- 光斑漂移：30s 缓动循环
- 无障碍：所有按钮 contentDescription；对比度 ≥4.5:1（OLED 风格校验）

## 6. 资源文件明细

| 资源 | 内容 |
|------|------|
| AndroidManifest.xml | `com.mcserver.launcher`；`FOREGROUND_SERVICE`+`FOREGROUND_SERVICE_SPECIAL_USE`+`POST_NOTIFICATIONS`；Service `android:foregroundServiceType="specialUse"` + property；exported=false |
| values/strings.xml | 全部中文文案（约 60 条 key） |
| values-en/strings.xml | 英文翻译 |
| values/themes.xml | 启动主题：粉蓝渐变底 + 无 ActionBar |
| drawable/ic_launcher_foreground.xml | 矢量：蓝底粉三角播放符（MC 风格方块角） |
| mipmap 各密度 | 由 adaptive icon 自动生成 |
| backup_rules / data_extraction_rules | 默认 |
| proguard-rules.pro | 保留 org.json、无反射库，基本默认 |

## 7. 单元测试（test/，JVM 可跑）

| 测试 | 覆盖 |
|------|------|
| InstanceStoreTest | 编解码往返、特殊字符转义、损坏 JSON 容错 |
| LogParseTest | TPS/玩家数正则对真实日志行解析 |
| TarTest | 手写 tar 解压器对 1 文件/多文件/长文件名样本 |
| DownloadManagerTest | Range 续传逻辑（mock 流） |

## 8. 实施顺序（阶段产出物 + 验收）

| 阶段 | 内容 | 产出物 | 验收 |
|------|------|--------|------|
| A | DownloadManager→JreManager→BackupManager→SettingsStore + 单测 | core+data 完整 | `./gradlew :app:compileDebugKotlin` 通过 |
| B | ServerService→Styles→GlassEffects→AppTheme→Components | 主题系统 | 风格枚举/令牌齐全，编译通过 |
| C | AppViewModel→AppRoot→5 屏→KazeApp/MainActivity | 全 UI | 全工程编译通过 |
| D | Manifest/strings/themes/图标/README/.operit | 资源 | 资源引用无缺失 |
| E | `bash setup_android_env.sh` → `./gradlew assembleDebug --no-daemon` | APK | APK 产出 |
| F | git 推送 modn1 + 清理 | 交付 | 分支可拉取，工作区干净 |

## 9. 构建命令序列（proot 环境）

```bash
chmod +x setup_android_env.sh && bash setup_android_env.sh   # 装 SDK35+build-tools+aapt2
export ANDROID_HOME=$HOME/Android
./gradlew :app:compileDebugKotlin --no-daemon -q              # 快速编译门禁（每阶段后跑）
./gradlew assembleDebug --no-daemon                           # 最终打包
./gradlew :app:testDebugUnitTest --no-daemon                  # 跑单测
```

## 10. 风险全表

| 风险 | 等级 | 对策 |
|------|------|------|
| proot 构建 OOM | 高 | gradle.properties 已降堆；`--no-daemon`；必要时 `org.gradle.workers.max=1` |
| AGP9 移除旧 DSL | 中 | 用 `kotlinOptions` 保守写法；报错即查即修 |
| Adoptium 网络慢 | 中 | 清华/阿里镜像回退；下载 UI 有进度与取消 |
| API24-30 无 blur | 中 | 降级玻璃模拟（半透明+渐变光斑） |
| 手写 tar 解压 bug | 中 | 单测覆盖；失败时提示"请手动解压后导入目录"兜底 |
| specialUse 审核风险 | 低 | 声明 property：`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` |
| 布局溢出小屏 | 中 | 基准 360dp；weight 相对布局；避免硬编码高度 |
| 核心 API 变更（PaperMC/Fabric） | 低 | 解析容错，失败引导自备 jar |

## 11. 交付与后续

1. commit message：`feat: 2.0 全面重构——原创液态玻璃UI+引擎分层+多风格主题`
2. 推送 modn1（force 或新 commit，保持分支历史干净）
3. 清理：删除 build/ 中间产物？保留 .gitignore 规则（build/ 已忽略）
4. 后续迭代建议（不在本次范围）：RCON 协议、备份导出到公共目录、模组市场、局域网穿透提示
