package com.kaze.newage.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.BlurLinear
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.BuildConfig
import com.kaze.newage.core.update.UpdateChecker
import com.kaze.newage.ui.AppUpdateState
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.ExpressiveLoadingIndicator
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EConnectedList
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EScreenColumn
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3ESegmentedRow
import com.kaze.newage.ui.components.M3EStatusChip
import com.kaze.newage.ui.components.WavyLinearProgress
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.FgColorMode
import com.kaze.newage.ui.theme.LocalDarkTheme
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.theme.parseSeedColor
import com.kaze.newage.ui.theme.statusPalette
import com.kaze.newage.util.LanIp
import com.kaze.newage.util.StorageDirUtil
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.LaunchedEffect

/** 背景图选图即用（显示端自适应铺满+模糊+遮罩），不再进入手动裁剪页 */

/**
 * Java 可安装的版本列表（新建实例时也可以手动指定别的版本）
 */
private val JAVA_INSTALL_VERSIONS = listOf(8, 17, 21, 25)

/**
 * 关于与许可证正文（GPL-3.0 与第三方组件声明）。
 *
 * 这是**合规义务**：内容必须保持完整可读 —— 换版式可以，删减条款说明不行。
 */
internal const val ABOUT_LICENSE_TEXT =
    "Kaze SLauncher\n" +
        "在 Android 上运行 Minecraft Java 服务端的启动器。\n\n" +
        "本软件以 GNU GPL-3.0 发布，源码随发行提供。\n" +
        "架构与 UI 体系参考 Fold Craft Launcher（FCL）与 ZalithLauncher2（均 GPL-3.0）；" +
        "运行环境为 proot + Ubuntu 24.04 rootfs。完整第三方组件清单见 THIRD_PARTY_NOTICES.md。\n\n" +
        "下载或运行 Minecraft 服务端即表示你同意 Minecraft EULA（aka.ms/MinecraftEULA）。\n" +
        "Minecraft 是 Mojang Studios 的商标，本项目与其无关。"

/**
 * 设置：外观 / 运行与存储 / 关于与许可证，一屏到底。
 *
 * 版式来自 m3e-canvas 生成的 `docs/m3e/prompt-设置.md`、`prompt-外观设置.md`、
 * `prompt-运行与存储.md`：
 *   一个分组 = 一张浮起卡片当分组头（标题 + 一行摘要）+ 紧跟其后的设置行（列表项），
 *   行与行 8dp、组与组 16dp（M3EScreenColumn 的节奏）。
 * 互斥选项（主题样式/主题模式/颜色来源/深色样式/图标与文字颜色/玻璃模式/更新通道）
 * 与数值项（玻璃强度/背景模糊/遮罩浓度）统一「整行点开 → 分段选择或滑杆」，
 * 行本身保持 72dp 的规整节奏；长说明（为什么这么做）放在行组之后的脚注里，不塞进行高。
 *
 * 与旧版的差别：三区差异化布局（主题磁贴 + 手风琴）换成「分组卡 + 连接列表」；
 * 主题磁贴的迷你预览（写死颜色，真机上两个预览几乎一样）与版本徽章的写死背板色
 * （#A87C27）随旧版式删除，颜色一律走 MaterialTheme.colorScheme 角色。
 */
@Composable
fun SettingsScreen(viewModel: AppViewModel, onOpenDiagnostics: () -> Unit = {}) {
    val javaVersions by viewModel.envJavaVersions.collectAsStateWithLifecycle()
    // 检测到的确切版本号（release 文件里的 JAVA_VERSION），用于行内显示"已安装 · 17.0.20.1"
    val javaVersionDetails by viewModel.envJavaVersionDetails.collectAsStateWithLifecycle()
    val javaTask by viewModel.javaTask.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val uiPrefs = viewModel.uiPrefs
    val clipboard = LocalClipboardManager.current

    // 同一时刻只开一个「行 → 选择器」弹窗（null = 全关）
    var openDialog by remember { mutableStateOf<SettingsDialog?>(null) }
    // 二级色取色器（叠在「颜色来源」弹窗之上的独立窗口）
    var showColorPicker by remember { mutableStateOf(false) }
    // 删除 Java 的二次确认（带版本号，单独一份状态）
    var javaDeleteConfirm by remember { mutableStateOf<Int?>(null) }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                // 选图即用：解码压缩保存（显示端自适应铺满 + 模糊/遮罩），无需手动裁剪
                uiPrefs.saveBackgroundImage(uri)
                uiPrefs.setBgEnabled(true)
            } catch (e: Exception) {
                android.util.Log.w("KazeSettings", "设置背景图失败", e)
                // 选图即用却什么都不发生，用户只会重复试。必须给出可见原因
                android.widget.Toast.makeText(
                    appContext,
                    "背景图设置失败：${e.message ?: e.javaClass.simpleName}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dir = StorageDirUtil.treeUriToFile(uri)
        if (dir == null) {
            Toast.makeText(appContext, "所选目录暂不支持（请选择主存储或 SD 卡目录）", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (!StorageDirUtil.isWritableDir(dir)) {
            Toast.makeText(appContext, "所选目录不可写，无法存放实例", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        // 持久化 SAF 授权（防系统回收）+ 保存路径
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val old = uiPrefs.instanceDirUri.value
        uiPrefs.setInstanceDir(dir.absolutePath, uri.toString())
        // 释放旧目录的持久授权。
        //
        // **同一目录不能再释放一次**：`take` 把引用计数加到 2，紧接着释放一次只减到 1，
        // 看着"没坏"，但只要系统或用户再释放一次（或这条路又走一遍），计数归零 ——
        // 用户"重新选了一下同一个目录"就得到处重新授权。
        // 顺序也必须是"先 take 新的、再 release 旧的"：反过来时，若两次选的是同一个
        // 目录，release 会在 take 之前把授权清掉。
        if (old.isNotBlank() && old != uri.toString()) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        viewModel.rescanInstances()
        Toast.makeText(appContext, "实例目录已切换，正在扫描所选目录…", Toast.LENGTH_LONG).show()
    }

    /** 释放当前自定义目录的 SAF 持久授权（恢复默认目录时调用） */
    fun releaseInstanceDirGrant() {
        val uri = uiPrefs.instanceDirUri.value
        if (uri.isBlank()) return
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    // Android 11 以下没有「所有文件访问」开关，走传统的 WRITE_EXTERNAL_STORAGE 运行时权限。
    // 之前无论什么版本都只弹一句「请去系统设置授予所有文件访问」，而这些系统上
    // 那个设置页根本不存在（Intent 解析失败还被 runCatching 吞掉），用户完全无从下手。
    val legacyStoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) dirPicker.launch(null)
        else Toast.makeText(appContext, "未授予存储权限，无法选择自定义目录", Toast.LENGTH_LONG).show()
    }

    // 「实例目录」行的按钮与整行点击共用这一段判定（分支理由见上）
    val requestInstanceDir: () -> Unit = {
        when {
            StorageDirUtil.hasAllFilesAccess(appContext) -> dirPicker.launch(null)
            // API < 30：直接申请传统存储权限（有权限才能用 File API 写任意目录）
            StorageDirUtil.needsLegacyStoragePermission() ->
                legacyStoreLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else -> {
                // Android 11+：先引导授予「所有文件访问」（分区存储下 File API 读写任意目录的前提）
                Toast.makeText(
                    appContext,
                    "请在系统设置中授予「所有文件访问」后，再次点击选择目录",
                    Toast.LENGTH_LONG,
                ).show()
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${appContext.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                }
            }
        }
    }

    // ── 电池优化白名单：有没有进白名单决定这一行是「已加入」还是「加入白名单」 ──
    val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(appContext.packageName)) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryOk = pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }
    val requestIgnoreBattery: () -> Unit = {
        try {
            batteryLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${appContext.packageName}"),
                )
            )
        } catch (e: Exception) {
                android.util.Log.w("KazeSettings", "请求忽略电池优化失败", e)
                android.widget.Toast.makeText(appContext, "系统未提供该设置入口（部分机型会屏蔽）", android.widget.Toast.LENGTH_SHORT).show()
            }
    }

    // ── 检查更新：状态与下载都在 ViewModel 里（见 AppUpdateState）──
    // 原来这里用 `remember` + `rememberCoroutineScope` 持有状态与协程：离开设置页或转屏
    // 会让组合销毁 → 作用域取消 → 下载静默中止，回来看还是 Idle，像"没点过"。
    val updateState by viewModel.appUpdate.collectAsStateWithLifecycle()
    val currentVersion = remember { viewModel.currentVersionName() }

    fun checkUpdate() = viewModel.checkUpdate()

    fun downloadAndInstall(info: UpdateChecker.ReleaseInfo) = viewModel.downloadAndInstallUpdate(info)

    // 「发现新版本 / 已是最新」都是**终点状态**：卡片自己给了关闭入口（见 UpdateStatusCard），
    // 检查入口则只在真正"正在检查/正在下载"时才禁用 —— 原来 Found 也禁用，
    // 于是卡片一旦出现就既关不掉、也重查不了（死端）。
    val canCheckUpdate = updateState !is AppUpdateState.Checking &&
        updateState !is AppUpdateState.Downloading

    // 「AMOLED 只在深色下有意义」：读主题层解析后的实际深浅色（跟随系统也要看系统），
    // 不能读 isSystemInDarkTheme() —— 强制浅色/深色时系统值会和实际生效值不一致。
    val darkNow = LocalDarkTheme.current

    // 进设置页就重新扫一遍 Java：rootfs 里的 JDK 可能在别处被装/删
    //（比如启动服务端时按版本自动装的、或用户自己进容器 apt 装的），
    // 只靠 AppViewModel 初始化那一次会显示过期状态。
    LaunchedEffect(Unit) { viewModel.refreshJava() }

    // ── 分区导航 ──
    // 设置页有 8 个分区、20 多个条目，一整页滚下来找一项要滑很久。顶部固定一排气分类 chip：
    // 点击平滑滚到对应分区，滚动时自动高亮当前分区。
    // 偏移量用 onGloballyPositioned 实测（不用写死的下标）：各条目高度随文案换行变化，
    // 而且 Java 分区的条目数还依赖已安装的版本数。
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val sectionOffsets = remember { mutableStateMapOf<SettingsSection, Int>() }   // 相对 M3EScreenColumn
    var columnTop by remember { mutableIntStateOf(0) }                            // M3EScreenColumn 在滚动内容里的位置
    val currentSection by remember {
        derivedStateOf {
            val y = scrollState.value + SECTION_STICKY_SLOP
            val atBottom = scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 4
            when {
                // 滚到底就直接算最后一个分区：末尾内容（「关于」只有两行）比一屏短，
                // 它的标题永远滚不到顶部，按偏移量判断会一直停在倒数第二个分区
                //（真机实测：内容已经在「关于与许可证」，高亮的却是「后台」）
                atBottom -> SettingsSection.entries.last()
                else -> SettingsSection.entries.lastOrNull {
                    (sectionOffsets[it] ?: Int.MAX_VALUE) + columnTop <= y
                } ?: SettingsSection.Appearance
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            // 自定义颜色 HEX 等输入框在 adjustNothing 下会被键盘盖住
            .imePadding(),
    ) {
        // ── 页头：标题 + 版本胶囊（旧版是写死 #A87C27 背板的徽章，颜色不能写死故换掉）──
        // 页头与分类条固定在顶部、不参与滚动，分类条才有「吸附」的意义
        M3EScreenHeader(
            title = "设置",
            trailing = {
                M3EStatusChip(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )

        SettingsCategoryBar(
            current = currentSection,
            onSelect = { section ->
                val target = columnTop + (sectionOffsets[section] ?: 0)
                scope.launch { scrollState.animateScrollTo(target.coerceIn(0, scrollState.maxValue)) }
            },
        )

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                // 底部空白承载常驻栏：滚动中内容充分透过底栏玻璃，滚到底时最后内容不被遮挡
                .padding(bottom = M3Spacing.bottomBarSpace),
        ) {
        M3EScreenColumn(
            modifier = Modifier.onGloballyPositioned { columnTop = it.positionInParent().y.toInt() },
        ) {
            // ═══ 外观 ═══
            // 「标题与标签使用强调字重」「表现力弹簧动效」两个开关没有落地：SettingsPrefs
            // 里没有对应的持久化项，主题与组件层目前恒为强调字阶 + 表现力弹簧；要加开关
            // 必须同时改 SettingsPrefs/Theme/组件层，超出本次界面重构（只动这一屏）的范围。
            GroupHeader(
                title = "外观",
                icon = Icons.Filled.Palette,
                supporting = "主题模式 · 颜色来源 · 深色样式",
                section = SettingsSection.Appearance,
                sectionOffsets = sectionOffsets,
            )
            M3EConnectedList(count = 5) { index, shape ->
                when (index) {
                    0 -> M3EListItem(
                        headline = "主题样式",
                        supporting = AppThemeMode.fromId(uiPrefs.themeMode.value).let { "${it.label} · ${it.desc}" },
                        leadingIcon = Icons.Filled.AutoAwesome,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.ThemeStyle },
                        trailing = { RowChevron() },
                    )
                    1 -> M3EListItem(
                        headline = "主题模式",
                        supporting = themeModeLabel(uiPrefs.themeModeValue.value),
                        leadingIcon = Icons.Filled.Brightness6,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.ThemeMode },
                        trailing = { RowChevron() },
                    )
                    2 -> M3EListItem(
                        headline = "颜色来源",
                        supporting = if (uiPrefs.md3ColorSource.value == "custom") {
                            "自定义颜色 · ${uiPrefs.md3CustomColor.value}"
                        } else {
                            "壁纸动态取色"
                        },
                        leadingIcon = Icons.Filled.Colorize,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.ColorSource },
                        trailing = { RowChevron() },
                    )
                    3 -> M3EListItem(
                        headline = "深色样式",
                        supporting = darkStyleLabel(uiPrefs.darkStyle.value),
                        leadingIcon = Icons.Filled.DarkMode,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.DarkStyle },
                        trailing = { RowChevron() },
                    )
                    else -> M3EListItem(
                        headline = "图标与文字颜色",
                        supporting = FgColorMode.fromId(uiPrefs.fgColorMode.value).label,
                        leadingIcon = Icons.Filled.FormatColorText,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.FgColor },
                        trailing = { RowChevron() },
                    )
                }
            }
            // AMOLED 只在深色下有意义：浅色（或跟随系统且系统为浅色）时点了界面毫无变化，
            // 用户会以为设置没保存。这里把前提写清楚。
            if (!darkNow) {
                SettingNote("「AMOLED 纯黑」只在深色模式下生效，当前是浅色")
            }

            // ═══ 背景图 ═══
            val hasBg = uiPrefs.hasBackgroundImage
            GroupHeader(
                modifier = Modifier.padding(top = M3Spacing.betweenParts),
                title = "背景图",
                icon = Icons.Filled.Image,
                supporting = if (hasBg) "已设置 · 可调模糊与遮罩" else "自定义壁纸 · 选图即用，无需裁剪",
                section = SettingsSection.Background,
                sectionOffsets = sectionOffsets,
            )
            // 有图时才出现显示/模糊/遮罩/清除四行：行形状要连续，所以按序号映射
            val bgRows = buildList {
                add(BackgroundRow.Image)
                if (hasBg) {
                    add(BackgroundRow.Toggle)
                    add(BackgroundRow.Blur)
                    add(BackgroundRow.Mask)
                    add(BackgroundRow.Clear)
                }
            }
            M3EConnectedList(count = bgRows.size) { index, shape ->
                when (bgRows[index]) {
                    BackgroundRow.Image -> M3EListItem(
                        headline = "背景图片",
                        supporting = if (hasBg) "已设置 · 点击可更换" else "未选择背景图",
                        leadingIcon = Icons.Filled.Wallpaper,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        // 整行 onClick 与行尾 TextButton **二选一**：两个都挂就是嵌套 clickable，
                        // 一次点击两条路径都触发 —— 文件选择器被连拉两次，第二次 launch 会抛
                        // IllegalStateException（"Can only use lifecycle-aware launchers…"）。
                        // 行尾只留文字按钮，整行交给它，行为不变但不会发两次。
                        trailing = {
                            TextButton(onClick = { imageLauncher.launch(arrayOf("image/*")) }) {
                                Text(if (hasBg) "更换" else "选择图片")
                            }
                        },
                    )
                    BackgroundRow.Toggle -> M3EListItem(
                        headline = "显示背景",
                        supporting = "选择图片后自动开启",
                        leadingIcon = Icons.Filled.Visibility,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { uiPrefs.setBgEnabled(!uiPrefs.bgEnabled.value) },
                        trailing = {
                            // onCheckedChange 必须是 null：整行的 onClick 已经负责切换。
                            // 两个都设 = 同一个动作有两条触发路径（开关自身的 toggleable +
                            // 行的 clickable），点一下开关会被切换两次、又弹回原位。
                            Switch(
                                checked = uiPrefs.bgEnabled.value,
                                onCheckedChange = null,
                            )
                        },
                    )
                    BackgroundRow.Blur -> M3EListItem(
                        headline = "模糊强度",
                        supporting = "当前 ${uiPrefs.bgBlur.floatValue.toInt()}",
                        leadingIcon = Icons.Filled.BlurLinear,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.BgBlur },
                        trailing = { RowChevron() },
                    )
                    BackgroundRow.Mask -> M3EListItem(
                        headline = "遮罩浓度",
                        supporting = "当前 ${uiPrefs.bgOpacity.floatValue.toInt()} · 越高越暗，0 为不遮",
                        leadingIcon = Icons.Filled.Opacity,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.BgMask },
                        trailing = { RowChevron() },
                    )
                    BackgroundRow.Clear -> M3EListItem(
                        headline = "清除背景图",
                        supporting = "删除已保存的壁纸并关闭显示",
                        leadingIcon = Icons.Filled.DeleteOutline,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = {
                            uiPrefs.clearBackgroundImage()
                            uiPrefs.setBgEnabled(false)
                        },
                    )
                }
            }

            // ═══ 存储 ═══
            val customDir = uiPrefs.instanceDirPath.value
            GroupHeader(
                modifier = Modifier.padding(top = M3Spacing.betweenParts),
                title = "存储",
                icon = Icons.Filled.Storage,
                supporting = "实例目录 · 默认在手机根目录 KazeS/，可自定义到其他分区或 SD 卡",
                section = SettingsSection.Storage,
                sectionOffsets = sectionOffsets,
            )
            M3EConnectedList(count = if (customDir.isBlank()) 1 else 2) { index, shape ->
                if (index == 0) {
                    M3EListItem(
                        headline = "实例目录",
                        supporting = when {
                            customDir.isBlank() -> "默认位置 · 手机根目录 KazeS/"
                            // 目录失效时要说明：InstanceStore 会静默回落到默认目录（新实例建在
                            // 用户找不到的地方），而这里若只显示旧路径 → 两边说法不一致
                            File(customDir).isDirectory -> "自定义目录 · 可用"
                            else -> "自定义目录当前不可用（已回落到默认目录）"
                        },
                        leadingIcon = Icons.Filled.FolderOpen,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        // 同上：整行不再挂 onClick，只由行尾按钮触发。
                        // 这条尤其不能连发：两次 requestInstanceDir 会连续拉两次目录选择器，
                        // 而且"释放旧授权 + 持久化新授权"会连着跑两遍，授权计数对不上。
                        trailing = {
                            TextButton(onClick = requestInstanceDir) { Text("选择目录") }
                        },
                    )
                } else {
                    M3EListItem(
                        headline = "恢复默认目录",
                        supporting = "回到手机根目录 KazeS/",
                        leadingIcon = Icons.Filled.RestartAlt,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = {
                            // 恢复默认之后自定义目录的持久授权就没用了，必须释放：
                            // 否则系统里一直挂着一条本应用对该目录的读写授权（设置页会一直列着它），
                            // 用户以为"取消自定义 = 收回权限"，实际没有。
                            releaseInstanceDirGrant()
                            uiPrefs.setInstanceDir("")
                            viewModel.rescanInstances()
                            Toast.makeText(appContext, "已恢复默认实例目录", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            if (customDir.isNotBlank()) {
                SettingNote("当前目录：${StorageDirUtil.displayPath(customDir)}")
            }

            // ═══ Java 运行时 ═══
            GroupHeader(
                modifier = Modifier.padding(top = M3Spacing.betweenParts),
                title = "Java 运行时",
                icon = Icons.Filled.Coffee,
                supporting = "已装：" + if (javaVersions.isEmpty()) {
                    "无"
                } else {
                    javaVersions.sorted().joinToString(" / ") { "Java $it" }
                },
                section = SettingsSection.JavaRuntime,
                sectionOffsets = sectionOffsets,
            )
            // 检测到但不在标准列表里的版本（例如自己进容器 apt 装的）也补成行，
            // 否则标题写着「已装：Java 22」、下面却找不到对应条目
            val javaRows = JAVA_INSTALL_VERSIONS + javaVersions.filter { it !in JAVA_INSTALL_VERSIONS }.sorted()
            M3EConnectedList(count = javaRows.size) { index, shape ->
                val version = javaRows[index]
                val installed = javaVersions.contains(version)
                val isTaskFor = javaTask.version == version
                M3EListItem(
                    headline = "Java $version",
                    supporting = when {
                        // 带上确切版本号：光写「已安装」看不出检测到的是哪个小版本
                        installed -> javaVersionDetails[version]?.let { "已安装 · $it" } ?: "已安装"
                        javaTask.running && isTaskFor -> "任务进行中…"
                        javaTask.error != null && isTaskFor -> javaTask.error.orEmpty().take(80)
                        else -> "未安装"
                    },
                    leadingIcon = Icons.Filled.Coffee,
                    iconContainer = if (installed) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = shape,
                    // 出错的那一行整行高亮：辅助文本的颜色由列表项组件固定，出错要让用户一眼看到
                    highlighted = javaTask.error != null && isTaskFor,
                    trailing = {
                        when {
                            installed -> OutlinedButton(
                                onClick = { javaDeleteConfirm = version },
                                enabled = !javaTask.running,
                            ) { Text("删除") }
                            javaTask.running && isTaskFor && !javaTask.cancelRequested -> OutlinedButton(
                                onClick = { viewModel.cancelJavaTask() },
                            ) { Text("取消") }
                            javaTask.error != null && isTaskFor -> Button(
                                onClick = { viewModel.installJava(version) },
                                enabled = !javaTask.running,
                            ) { Text("重试") }
                            else -> Button(
                                onClick = { viewModel.installJava(version) },
                                enabled = !javaTask.running,
                            ) {
                                Text(
                                    if (javaTask.running && isTaskFor) "取消中…" else "下载安装"
                                )
                            }
                        }
                    },
                )
            }
            SettingNote("MC 对应 Java：1.8–1.16.5→8 · 1.17–1.20.4→17 · ≥1.20.5→21 · ≥26.x→25")
            if (javaTask.running) {
                // 旧版是行内 LinearProgressIndicator，这里换成 Expressive 波浪进度条
                M3ECard(
                    variant = M3ECardVariant.Outlined,
                    title = "正在处理 Java ${javaTask.version ?: ""}",
                    // content 必须具名传入：尾随 lambda 会绑到卡片签名最后一个参数（trailing），
                    // 那是标题行右侧的位置，不是标题下方的正文区
                    content = {
                        WavyLinearProgress(
                            progress = javaTask.progress.coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        javaTask.message.let {
                            if (it.isNotBlank()) {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    },
                )
            }

            // ═══ 控制台 ═══
            GroupHeader(
                modifier = Modifier.padding(top = M3Spacing.betweenParts),
                title = "控制台",
                icon = Icons.Filled.Terminal,
                supporting = "AI 入口 · 常驻显示",
                section = SettingsSection.Console,
                sectionOffsets = sectionOffsets,
            )
            M3EConnectedList(count = 1) { _, shape ->
                M3EListItem(
                    headline = "控制台 AI 入口",
                    supporting = "在控制台命令输入行常驻显示 AI 助手按钮",
                    leadingIcon = Icons.Filled.SmartToy,
                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                    shape = shape,
                    onClick = { uiPrefs.setConsoleAiEntry(!uiPrefs.consoleAiEntry.value) },
                    trailing = {
                        // onCheckedChange 必须是 null：整行的 onClick 已经负责切换，
                        // 两条触发路径会让开关点一下被切两次、又弹回原位
                        Switch(
                            checked = uiPrefs.consoleAiEntry.value,
                            onCheckedChange = null,
                        )
                    },
                )
            }

            // ═══ MCP 服务 ═══
            GroupHeader(
                modifier = Modifier.padding(top = M3Spacing.betweenParts),
                title = "MCP 服务",
                icon = Icons.Filled.Lan,
                supporting = "外部 AI 控制 · 映射端口 · 访问令牌",
                section = SettingsSection.Mcp,
                sectionOffsets = sectionOffsets,
            )
            M3EConnectedList(count = 5) { index, shape ->
                when (index) {
                    0 -> M3EListItem(
                        headline = "启用 MCP 服务",
                        supporting = "开放本地接口，供外部 AI 客户端控制服务端",
                        leadingIcon = Icons.Filled.SmartToy,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { viewModel.setMcpEnabled(!uiPrefs.mcpEnabled.value) },
                        trailing = {
                            // onCheckedChange 必须是 null：整行 onClick 已负责切换，双路径会切两次弹回
                            Switch(
                                checked = uiPrefs.mcpEnabled.value,
                                onCheckedChange = null,
                            )
                        },
                    )
                    1 -> M3EListItem(
                        headline = "监听地址与端口",
                        supporting = "${uiPrefs.mcpBindAddress.value} · 端口 ${uiPrefs.mcpPort.value}",
                        leadingIcon = Icons.Filled.Router,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.McpPort },
                        trailing = { RowChevron() },
                    )
                    2 -> M3EListItem(
                        headline = "自动分配范围",
                        supporting = "映射端口被占用时按范围顺延（${uiPrefs.mcpPortRangeStart.value}–${uiPrefs.mcpPortRangeEnd.value}）",
                        leadingIcon = Icons.Filled.Tune,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.McpRange },
                        trailing = { RowChevron() },
                    )
                    3 -> M3EListItem(
                        headline = "访问令牌",
                        supporting = "Bearer 认证：外部请求必须携带此令牌",
                        leadingIcon = Icons.Filled.Key,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.McpToken },
                        trailing = { RowChevron() },
                    )
                    4 -> {
                        // 实际监听端口（映射端口被占用时是顺延后的）：外部 AI 配置以它为准。
                        // 地址按监听地址显示：仅本机绑定时局域网 IP 连不上，不能误导
                        val boundPort = viewModel.mcpServer.boundPort.collectAsStateWithLifecycle().value
                        val lastError = viewModel.mcpServer.lastError.collectAsStateWithLifecycle().value
                        val lanIp = remember { LanIp.lanIpv4() }
                        val displayHost = when (val bind = uiPrefs.mcpBindAddress.value) {
                            "127.0.0.1" -> "127.0.0.1"
                            "0.0.0.0" -> lanIp ?: "127.0.0.1"
                            else -> bind // 绑了具体网卡 IP，直接显示它
                        }
                        val address = boundPort?.let { "http://$displayHost:$it/mcp" }
                        M3EListItem(
                            headline = "连接地址",
                            supporting = address
                                ?: if (uiPrefs.mcpEnabled.value) {
                                    "服务未运行${lastError?.let { "（$it）" } ?: ""}"
                                } else {
                                    "启用服务后显示连接地址"
                                },
                            leadingIcon = Icons.Filled.Link,
                            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                            shape = shape,
                            onClick = address?.let { a -> {
                                clipboard.setText(AnnotatedString(a))
                                Toast.makeText(appContext, "已复制连接地址", Toast.LENGTH_SHORT).show()
                            } },
                            trailing = {
                                if (address != null) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            SettingNote("监听地址选 0.0.0.0 时局域网内设备均可访问；请妥善保管令牌，不用时关闭。")

            // ═══ 后台与更新 ═══
            GroupHeader(
                modifier = Modifier.padding(top = M3Spacing.betweenParts),
                title = "后台与更新",
                icon = Icons.Filled.Shield,
                supporting = "电池优化 · 更新通道 · 自动检查",
                section = SettingsSection.BackgroundAndUpdate,
                sectionOffsets = sectionOffsets,
            )
            SettingNote("服务端运行时会拉起前台服务，退后台不被系统回收。")
            M3EConnectedList(count = 4) { index, shape ->
                when (index) {
                    0 -> M3EListItem(
                        headline = "电池优化",
                        supporting = if (batteryOk) {
                            "已允许后台运行，应用不被省电策略回收"
                        } else {
                            "未加入白名单：应用在后台可能被系统回收"
                        },
                        leadingIcon = Icons.Filled.BatterySaver,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        // 没加白名单时整行高亮：这一项被系统回收会直接杀掉正在跑的服务端
                        highlighted = !batteryOk,
                        onClick = if (batteryOk) null else requestIgnoreBattery,
                        trailing = {
                            if (batteryOk) {
                                M3EStatusChip(
                                    text = "已加入白名单",
                                    color = statusPalette().running,
                                    icon = Icons.Filled.CheckCircle,
                                )
                            } else {
                                TextButton(onClick = requestIgnoreBattery) { Text("加入白名单") }
                            }
                        },
                    )
                    1 -> M3EListItem(
                        headline = "启动时自动检查更新",
                        supporting = "打开后每次启动应用自动检查，发现新版本会弹窗提示",
                        leadingIcon = Icons.Filled.SystemUpdate,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { uiPrefs.setAutoUpdate(!uiPrefs.autoUpdate.value) },
                        trailing = {
                            // 同上：切换只由整行的 onClick 负责，Switch 不再单独绑定，
                            // 否则两条触发路径互相抵消，开关看起来"按不动"
                            Switch(
                                checked = uiPrefs.autoUpdate.value,
                                onCheckedChange = null,
                            )
                        },
                    )
                    2 -> M3EListItem(
                        headline = "更新通道",
                        supporting = updateChannelLabel(uiPrefs.updateChannel.value),
                        leadingIcon = Icons.AutoMirrored.Filled.AltRoute,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.UpdateChannel },
                        trailing = { RowChevron() },
                    )
                    3 -> M3EListItem(
                        headline = "更新方式",
                        supporting = updateModeLabel(uiPrefs.updateMode.value),
                        leadingIcon = Icons.Filled.CloudDownload,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.UpdateMode },
                        trailing = { RowChevron() },
                    )
                    else -> M3EListItem(
                        headline = "检查更新",
                        supporting = "当前版本 $currentVersion · GitHub Releases · 自动测速选择最快下载线路",
                        leadingIcon = Icons.Filled.CloudDownload,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        // 整行点击就是"检查"：行尾不再放一个同动作的 TextButton ——
                        // 嵌套 clickable 会让一次点击连发两次（目录选择器/文件选择器被拉两次，
                        // 新版 Android 上第二次 launch 直接抛 IllegalStateException）。
                        // 行尾只保留状态文字 + 箭头，动作统一由整行承担。
                        onClick = if (canCheckUpdate) ({ checkUpdate() }) else null,
                        trailing = {
                            if (updateState is AppUpdateState.Checking) {
                                ExpressiveLoadingIndicator(size = 22.dp)
                            } else {
                                RowChevron()
                            }
                        },
                    )
                }
            }
            if (!batteryOk) {
                SettingNote("vivo 等机型另有「后台耗电管理」：请在 系统设置 → 电池 → 后台耗电管理 中允许 Kaze SLauncher。")
            }
            UpdateStatusCard(
                state = updateState,
                onCheck = { checkUpdate() },
                onDownload = { downloadAndInstall(it) },
                onCancel = { viewModel.cancelUpdateDownload() },
                onDismiss = { viewModel.resetUpdateState() },
            )

            // ═══ 关于与许可证 ═══
            GroupHeader(
                modifier = Modifier.padding(top = M3Spacing.betweenParts),
                title = "关于与许可证",
                icon = Icons.Filled.Gavel,
                supporting = "v${BuildConfig.VERSION_NAME} · GNU GPL-3.0",
                section = SettingsSection.About,
                sectionOffsets = sectionOffsets,
            )
            M3EConnectedList(count = 2) { index, shape ->
                if (index == 0) {
                    M3EListItem(
                        headline = "诊断日志",
                        supporting = "应用日志 · 环境自检 · 出问题可直接分享",
                        leadingIcon = Icons.Filled.BugReport,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = onOpenDiagnostics,
                        trailing = { RowChevron() },
                    )
                } else {
                    M3EListItem(
                        headline = "许可证与第三方组件",
                        supporting = "GPL-3.0 · 第三方组件清单 · Minecraft EULA 声明",
                        leadingIcon = Icons.Filled.Gavel,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        onClick = { openDialog = SettingsDialog.About },
                        trailing = { RowChevron() },
                    )
                }
            }
        }

        // ── 行 → 选择器：行本身保持列表节奏，互斥选项与滑杆点开才出现 ──
        when (openDialog) {
            null -> Unit

            SettingsDialog.ThemeStyle -> ChoiceDialog(
                title = "主题样式",
                // 液态玻璃已从构建中移除：主题列表里不再出现该选项
                //（相关代码保留在 ui/theme/LiquidGlassEffect.kt 与 blur/shader 目录，未接入）
                options = AppThemeMode.entries.filter { it != AppThemeMode.GLASS },
                selected = AppThemeMode.fromId(uiPrefs.themeMode.value),
                label = { it.label },
                note = AppThemeMode.fromId(uiPrefs.themeMode.value).desc,
                onSelect = { mode -> uiPrefs.setThemeMode(mode.id) },
                onDismiss = { openDialog = null },
            )

            SettingsDialog.ThemeMode -> ChoiceDialog(
                title = "主题模式",
                options = listOf(0, 1, 2),
                selected = uiPrefs.themeModeValue.value,
                label = { themeModeLabel(it) },
                onSelect = { uiPrefs.setThemeModeValue(it) },
                onDismiss = { openDialog = null },
            )

            // 二级色的取色器见 theme/Theme.kt seedColorScheme：取色风格已移除，自定义颜色只选种子色
            SettingsDialog.ColorSource -> AlertDialog(
                onDismissRequest = { openDialog = null },
                title = { Text("颜色来源") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        M3ESegmentedRow(
                            options = listOf("wallpaper", "custom"),
                            selected = uiPrefs.md3ColorSource.value,
                            label = { if (it == "wallpaper") "壁纸动态取色" else "自定义颜色" },
                            onSelect = { uiPrefs.setMd3ColorSource(it) },
                            height = 44.dp,
                        )
                        if (uiPrefs.md3ColorSource.value == "custom") {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(
                                            runCatching {
                                                Color(
                                                    uiPrefs.md3CustomColor.value.removePrefix("#")
                                                        .toLong(16).toInt() or 0xFF000000.toInt()
                                                )
                                            }.getOrDefault(MaterialTheme.colorScheme.primary)
                                        )
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("二级色", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        uiPrefs.md3CustomColor.value,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(onClick = { showColorPicker = true }) { Text("取色") }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { openDialog = null }) { Text("完成") } },
            )

            SettingsDialog.DarkStyle -> ChoiceDialog(
                title = "深色样式",
                options = listOf(0, 1),
                selected = uiPrefs.darkStyle.value,
                label = { darkStyleLabel(it) },
                note = if (darkNow) null else "「AMOLED 纯黑」只在深色模式下生效，当前是浅色",
                onSelect = { uiPrefs.setDarkStyle(it) },
                onDismiss = { openDialog = null },
            )

            SettingsDialog.FgColor -> ChoiceDialog(
                title = "图标与文字颜色",
                options = FgColorMode.entries,
                selected = FgColorMode.fromId(uiPrefs.fgColorMode.value),
                label = { it.label },
                note = "深色背景图选「白色」、浅色图选「黑色」可保证可读性",
                onSelect = { uiPrefs.setFgColorMode(it.id) },
                onDismiss = { openDialog = null },
            )


            SettingsDialog.BgBlur -> SliderDialog(
                title = "模糊强度",
                value = uiPrefs.bgBlur.floatValue,
                valueText = "模糊强度：${uiPrefs.bgBlur.floatValue.toInt()}",
                range = 0f..25f,
                onValueChange = { uiPrefs.setBgBlur(it) },
                onDismiss = { openDialog = null },
            )

            SettingsDialog.BgMask -> SliderDialog(
                title = "遮罩浓度",
                value = uiPrefs.bgOpacity.floatValue,
                valueText = "遮罩浓度：${uiPrefs.bgOpacity.floatValue.toInt()}",
                range = 0f..90f,
                note = "越高越暗，0 为不遮",
                onValueChange = { uiPrefs.setBgOpacity(it) },
                onDismiss = { openDialog = null },
            )

            SettingsDialog.UpdateMode -> ChoiceDialog(
                title = "更新方式",
                options = listOf("full", "patch"),
                selected = uiPrefs.updateMode.value,
                label = { updateModeLabel(it) },
                note = "完整安装包最稳妥（默认）；增量补丁只下载变化的部分，约省 95% 流量，" +
                    "拼装后会校验 sha256 与签名，失败自动回退整包",
                onSelect = { uiPrefs.setUpdateMode(it) },
                onDismiss = { openDialog = null },
            )

            SettingsDialog.UpdateChannel -> ChoiceDialog(
                title = "更新通道",
                options = listOf("preview", "stable"),
                selected = uiPrefs.updateChannel.value,
                label = { updateChannelLabel(it) },
                note = "预览版优先推送最新功能（含测试版本）；正式版仅推送稳定发布",
                onSelect = { uiPrefs.setUpdateChannel(it) },
                onDismiss = { openDialog = null },
            )

            SettingsDialog.McpPort -> {
                // 三选一：0 = 0.0.0.0（全网卡）· 1 = 127.0.0.1（仅本机）· 2 = 自定义 IPv4
                val current = uiPrefs.mcpBindAddress.value
                var addrChoice by remember {
                    mutableStateOf(if (current == "0.0.0.0") 0 else if (current == "127.0.0.1") 1 else 2)
                }
                var customAddr by remember { mutableStateOf(current) }
                var text by remember { mutableStateOf(uiPrefs.mcpPort.value.toString()) }
                AlertDialog(
                    onDismissRequest = { openDialog = null },
                    title = { Text("监听地址与端口") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = addrChoice == 0,
                                    onClick = { addrChoice = 0 },
                                    label = { Text("0.0.0.0") },
                                )
                                FilterChip(
                                    selected = addrChoice == 1,
                                    onClick = { addrChoice = 1 },
                                    label = { Text("127.0.0.1") },
                                )
                                FilterChip(
                                    selected = addrChoice == 2,
                                    onClick = { addrChoice = 2 },
                                    label = { Text("自定义") },
                                )
                            }
                            if (addrChoice == 2) {
                                OutlinedTextField(
                                    value = customAddr,
                                    onValueChange = { s -> customAddr = s.trim().take(15) },
                                    label = { Text("IPv4 地址") },
                                    singleLine = true,
                                )
                            }
                            OutlinedTextField(
                                value = text,
                                onValueChange = { s -> text = s.filter { it.isDigit() }.take(5) },
                                label = { Text("端口（1024–65535）") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Text(
                                "0.0.0.0 局域网内均可访问，127.0.0.1 仅本机；端口被占用时" +
                                    "按「自动分配范围」顺延，实际以「连接地址」为准。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val p = text.toIntOrNull()
                            if (p == null || p !in 1024..65535) {
                                Toast.makeText(appContext, "端口需在 1024 ~ 65535", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            val addr = when (addrChoice) {
                                0 -> "0.0.0.0"
                                1 -> "127.0.0.1"
                                else -> customAddr.trim()
                            }
                            if (!isValidIpv4(addr)) {
                                Toast.makeText(appContext, "监听地址不是有效的 IPv4", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            uiPrefs.setMcpBindAddress(addr)
                            uiPrefs.setMcpPort(p)
                            viewModel.rebindMcp()
                            openDialog = null
                        }) { Text("保存") }
                    },
                    dismissButton = { TextButton(onClick = { openDialog = null }) { Text("取消") } },
                )
            }

            SettingsDialog.McpRange -> {
                var startText by remember { mutableStateOf(uiPrefs.mcpPortRangeStart.value.toString()) }
                var endText by remember { mutableStateOf(uiPrefs.mcpPortRangeEnd.value.toString()) }
                AlertDialog(
                    onDismissRequest = { openDialog = null },
                    title = { Text("自动分配范围") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = startText,
                                    onValueChange = { s -> startText = s.filter { it.isDigit() }.take(5) },
                                    label = { Text("起始") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                OutlinedTextField(
                                    value = endText,
                                    onValueChange = { s -> endText = s.filter { it.isDigit() }.take(5) },
                                    label = { Text("结束") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                            }
                            Text(
                                "映射端口被占用时，从起始端口顺延取第一个可用端口。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val s = startText.toIntOrNull()
                            val e = endText.toIntOrNull()
                            if (s == null || e == null || s !in 1024..65535 || e !in 1024..65535 || s > e) {
                                Toast.makeText(
                                    appContext,
                                    "范围无效：起止须在 1024 ~ 65535，且起始不大于结束",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                uiPrefs.setMcpPortRange(s, e)
                                viewModel.rebindMcp()
                                openDialog = null
                            }
                        }) { Text("保存") }
                    },
                    dismissButton = { TextButton(onClick = { openDialog = null }) { Text("取消") } },
                )
            }

            SettingsDialog.McpToken -> {
                var token by remember { mutableStateOf(uiPrefs.mcpToken.value) }
                AlertDialog(
                    onDismissRequest = { openDialog = null },
                    title = { Text("访问令牌") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = token,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Bearer 令牌") },
                                singleLine = true,
                            )
                            Text(
                                "外部请求需带请求头 Authorization: Bearer <令牌>。" +
                                    "重新生成后旧令牌立即失效，已配置的客户端要改用新令牌。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    confirmButton = { TextButton(onClick = { openDialog = null }) { Text("完成") } },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(token))
                                Toast.makeText(appContext, "已复制访问令牌", Toast.LENGTH_SHORT).show()
                            }) { Text("复制") }
                            TextButton(onClick = { token = uiPrefs.regenerateMcpToken() }) { Text("重新生成") }
                        }
                    },
                )
            }

            SettingsDialog.About -> AboutDialog(onDismiss = { openDialog = null })
        }

        // 二级色取色器：叠在「颜色来源」之上（独立窗口，关掉它仍回到颜色来源）
        if (showColorPicker) {
            ColorPickerDialog(
                initialArgb = parseSeedColor(uiPrefs.md3CustomColor.value) ?: 0x00FFFF.toInt(),
                onConfirm = { argb ->
                    uiPrefs.setMd3CustomColor(String.format("#%06X", argb and 0xFFFFFF))
                    showColorPicker = false
                },
                onDismiss = { showColorPicker = false },
            )
        }

        // 删除 Java：不可逆（约 200MB 运行时文件），必须先确认
        javaDeleteConfirm?.let { version ->
            AlertDialog(
                onDismissRequest = { javaDeleteConfirm = null },
                title = { Text("删除 Java $version？") },
                text = { Text("将删除运行时文件（约 200MB）并清理下载残留。正在使用 Java $version 的实例将无法启动，直到重新安装。") },
                confirmButton = {
                    TextButton(onClick = {
                        javaDeleteConfirm = null
                        viewModel.uninstallJava(version)
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { javaDeleteConfirm = null }) { Text("取消") }
                },
            )
        }
    }
}
    }
