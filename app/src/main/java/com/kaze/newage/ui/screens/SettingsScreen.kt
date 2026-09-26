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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.BuildConfig
import com.kaze.newage.core.update.UpdateChecker
import com.kaze.newage.core.update.UpdateInstaller
import com.kaze.newage.ui.AppViewModel
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
import com.kaze.newage.util.StorageDirUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
private const val ABOUT_LICENSE_TEXT =
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
            } catch (_: Exception) { }
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
        // 释放旧目录的持久授权
        uiPrefs.instanceDirUri.value.takeIf { it.isNotBlank() }?.let { old ->
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        uiPrefs.setInstanceDir(dir.absolutePath, uri.toString())
        viewModel.rescanInstances()
        Toast.makeText(appContext, "实例目录已切换，正在扫描所选目录…", Toast.LENGTH_LONG).show()
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
                        Uri.parse("package:com.kaze.newage"),
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
        } catch (_: Exception) { }
    }

    // ── 检查更新：GitHub Releases API + 多线路加速下载（逻辑与旧版一致，只换了版式）──
    val updateScope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var cancelDownload by remember { mutableStateOf(false) }
    val currentVersion = remember {
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }

    fun checkUpdate() {
        updateState = UpdateUiState.Checking
        updateScope.launch(Dispatchers.IO) {
            try {
                val info = UpdateChecker.check(uiPrefs.updateChannel.value)
                if (info == null || !UpdateChecker.isNewer(info.tag, currentVersion)) {
                    withContext(Dispatchers.Main) { updateState = UpdateUiState.Latest }
                } else {
                    withContext(Dispatchers.Main) { updateState = UpdateUiState.Found(info) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateState = UpdateUiState.Error(e.message ?: "检查失败（网络不可达？）")
                }
            }
        }
    }

    fun downloadAndInstall(info: UpdateChecker.ReleaseInfo) {
        cancelDownload = false
        updateState = UpdateUiState.Downloading(info, 0f, "准备下载…")
        updateScope.launch(Dispatchers.IO) {
            val file = UpdateInstaller.download(
                context = appContext,
                info = info,
                onProgress = { done, total, p ->
                    val msg = "下载中 $done MB" + (if (total > 0) " / $total MB" else "")
                    updateState = UpdateUiState.Downloading(info, p, msg)
                },
                shouldCancel = { cancelDownload },
            )
            if (file == null) {
                withContext(Dispatchers.Main) {
                    updateState = if (cancelDownload) UpdateUiState.Idle
                    else UpdateUiState.Error("下载失败：所有线路不可用，请稍后重试")
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (UpdateInstaller.install(appContext, file)) {
                    updateState = UpdateUiState.Idle
                } else {
                    updateState = UpdateUiState.Error("无法打开安装器，请到设置中开启「安装未知应用」权限")
                }
            }
        }
    }

    val canCheckUpdate = updateState !is UpdateUiState.Checking &&
        updateState !is UpdateUiState.Downloading &&
        updateState !is UpdateUiState.Found

    // 「AMOLED 只在深色下有意义」：读主题层解析后的实际深浅色（跟随系统也要看系统），
    // 不能读 isSystemInDarkTheme() —— 强制浅色/深色时系统值会和实际生效值不一致。
    val darkNow = LocalDarkTheme.current

    // 进设置页就重新扫一遍 Java：rootfs 里的 JDK 可能在别处被装/删
    //（比如启动服务端时按版本自动装的、或用户自己进容器 apt 装的），
    // 只靠 AppViewModel 初始化那一次会显示过期状态。
    LaunchedEffect(Unit) { viewModel.refreshJava() }

    // ── 分区导航 ──
    // 设置页有 7 个分区、20 多个条目，一整页滚下来找一项要滑很久。顶部固定一排气分类 chip：
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
                        onClick = { imageLauncher.launch(arrayOf("image/*")) },
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
                            Switch(
                                checked = uiPrefs.bgEnabled.value,
                                onCheckedChange = { uiPrefs.setBgEnabled(it) },
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
                        onClick = requestInstanceDir,
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
            SettingNote(
                "游戏实例默认存到应用外部目录；可自定义到其他目录（如大分区/SD 卡），" +
                    "新实例存到所选目录，所选目录中已有的服务端也会被直接识别运行。"
            )

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
            SettingNote(
                "Java 不随应用内置，按需下载所需版本；不同 MC 版本对 Java 的要求不同" +
                    "（1.8–1.16.5→8 / 1.17–1.20.4→17 / ≥1.20.5→21 / ≥26.x→25），可在新建实例时手动指定。" +
                    "下载支持断点续传，断网会自动重试；中断后可点击继续。"
            )
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

            // ═══ 后台与更新 ═══
            GroupHeader(
                modifier = Modifier.padding(top = M3Spacing.betweenParts),
                title = "后台与更新",
                icon = Icons.Filled.Shield,
                supporting = "电池优化 · 更新通道 · 自动检查",
                section = SettingsSection.BackgroundAndUpdate,
                sectionOffsets = sectionOffsets,
            )
            SettingNote("前台服务守护：服务端运行时会拉起前台服务，应用退到后台也不被系统回收。")
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
                            Switch(
                                checked = uiPrefs.autoUpdate.value,
                                onCheckedChange = { uiPrefs.setAutoUpdate(it) },
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
                    else -> M3EListItem(
                        headline = "检查更新",
                        supporting = "当前版本 $currentVersion · GitHub Releases · 自动测速选择最快下载线路",
                        leadingIcon = Icons.Filled.CloudDownload,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        // 检查中/下载中/已发现新版本时不给重复触发（旧版这些状态也没有按钮）
                        onClick = if (canCheckUpdate) ({ checkUpdate() }) else null,
                        trailing = {
                            if (canCheckUpdate) {
                                TextButton(onClick = { checkUpdate() }) { Text("检查") }
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
                onCancel = { cancelDownload = true },
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

            SettingsDialog.UpdateChannel -> ChoiceDialog(
                title = "更新通道",
                options = listOf("preview", "stable"),
                selected = uiPrefs.updateChannel.value,
                label = { updateChannelLabel(it) },
                note = "预览版优先推送最新功能（含测试版本）；正式版仅推送稳定发布",
                onSelect = { uiPrefs.setUpdateChannel(it) },
                onDismiss = { openDialog = null },
            )

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

// ──────────────────────────────────────────────────────────────
// 分组版式的零件
// ──────────────────────────────────────────────────────────────

/**
 * 分组头卡：一张浮起卡片就是一组设置的开头，行紧随其后。
 * 组与组之间用 `Modifier.padding(top = M3Spacing.betweenParts)` 补足 16dp。
 */
@Composable
private fun GroupHeader(
    title: String,
    supporting: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    /** 属于哪个分区；给了就把纵向偏移量上报给顶部分类条（点击跳转 / 滚动高亮用） */
    section: SettingsSection? = null,
    sectionOffsets: MutableMap<SettingsSection, Int>? = null,
) {
    M3ECard(
        modifier = modifier.then(
            if (section != null && sectionOffsets != null) {
                Modifier.onGloballyPositioned { coords ->
                    // positionInParent 相对 M3EScreenColumn，不受滚动影响；
                    // 与 columnTop 相加即得它在滚动内容里的绝对位置
                    val v = coords.positionInParent().y.toInt()
                    if (sectionOffsets[section] != v) sectionOffsets[section] = v
                }
            } else {
                Modifier
            }
        ),
        variant = M3ECardVariant.Elevated,
        title = title,
        titleIcon = icon,
        supporting = supporting,
    )
}

/** 行尾的「›」：表示这一行点开是一个选择器（分段选择 / 滑杆） */
@Composable
private fun RowChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 分组脚注：长说明（为什么这么做）放在行组之后完整换行，不塞进 72dp 的列表行 */
@Composable
private fun SettingNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** 互斥选择弹窗：分段选择即时生效（与旧版点 chip 一样，点完就写 prefs） */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                M3ESegmentedRow(
                    options = options,
                    selected = selected,
                    label = label,
                    onSelect = onSelect,
                    height = 44.dp,
                )
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/** 数值弹窗：滑杆塞进 72dp 的列表行会把行撑变形，所以点开才出现 */
@Composable
private fun SliderDialog(
    title: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = range,
                )
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/** 许可证全文：合规义务，完整可读（可滚动，不截断） */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于与许可证") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    ABOUT_LICENSE_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 更新流程的状态块：只有「有事发生」时才出现一张卡，Idle 时什么都不显示 */
@Composable
private fun UpdateStatusCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: (UpdateChecker.ReleaseInfo) -> Unit,
    onCancel: () -> Unit,
) {
    // 卡片的正文一律用 content 具名传入：尾随 lambda 会绑到 trailing（标题行右侧），不是正文区
    when (state) {
        is UpdateUiState.Idle -> Unit

        is UpdateUiState.Checking -> M3ECard(
            variant = M3ECardVariant.Outlined,
            title = "正在检查更新…",
            content = {
                WavyLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
            },
        )

        is UpdateUiState.Error -> M3ECard(
            variant = M3ECardVariant.Outlined,
            title = "检查更新失败",
            supporting = state.msg,
            content = {
                Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("重试") }
            },
        )

        is UpdateUiState.Latest -> M3ECard(
            variant = M3ECardVariant.Outlined,
            title = "已是最新版本",
            supporting = "（或仓库暂未发布更新）",
        )

        is UpdateUiState.Found -> M3ECard(
            variant = M3ECardVariant.Elevated,
            title = "发现新版本 ${state.info.tag}",
            supporting = state.info.body.take(600).ifBlank { null },
            content = {
                Button(
                    onClick = { onDownload(state.info) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("下载并安装") }
            },
        )

        is UpdateUiState.Downloading -> M3ECard(
            variant = M3ECardVariant.Outlined,
            title = "正在下载更新",
            supporting = state.message,
            content = {
                WavyLinearProgress(
                    progress = state.progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("取消") }
            },
        )
    }
}

/** 更新流程 UI 状态 */
private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Error(val msg: String) : UpdateUiState
    data object Latest : UpdateUiState
    data class Found(val info: UpdateChecker.ReleaseInfo) : UpdateUiState
    data class Downloading(
        val info: UpdateChecker.ReleaseInfo,
        val progress: Float,
        val message: String,
    ) : UpdateUiState
}

/** 「行 → 选择器」弹窗的标识（同一时刻只开一个） */
private enum class SettingsDialog {
    ThemeStyle,
    ThemeMode,
    ColorSource,
    DarkStyle,
    FgColor,
    BgBlur,
    BgMask,
    UpdateChannel,
    About,
}

/** 背景图分组的行序：有图时才出现后四行，行形状按序号连续 */
private enum class BackgroundRow { Image, Toggle, Blur, Mask, Clear }

private fun themeModeLabel(value: Int): String = when (value) {
    1 -> "浅色模式"
    2 -> "深色模式"
    else -> "跟随系统"
}

private fun darkStyleLabel(value: Int): String = if (value == 1) "AMOLED 纯黑" else "普通黑"

private fun updateChannelLabel(value: String): String =
    if (value == "stable") "仅正式版" else "预览版（含测试版）"

/** 自研 HSV 取色器（自研实现，无第三方依赖；无第三方依赖）：
 * 色相条 + 饱和度/明度面板 + 实时预览，确定后回调 ARGB */
@Composable
private fun ColorPickerDialog(
    initialArgb: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hsv by remember(initialArgb) {
        mutableStateOf(
            floatArrayOf(0f, 0f, 0f).also {
                android.graphics.Color.colorToHSV(initialArgb, it)
                if (it[0].isNaN()) it[0] = 0f
            }
        )
    }
    val hueColor = Color(
        android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
    )
    val current = Color(android.graphics.Color.HSVToColor(hsv))
    val density = LocalDensity.current
    val markerPx = with(density) { 8.dp.toPx() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择二级色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 饱和度（横轴）× 明度（纵轴）2D 面板
                var panelSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.White, hueColor)
                            )
                        )
                        .onSizeChanged {
                            panelSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat())
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                hsv = floatArrayOf(
                                    hsv[0],
                                    (down.position.x / size.width).coerceIn(0f, 1f),
                                    1f - (down.position.y / size.height).coerceIn(0f, 1f),
                                )
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: break
                                    hsv = floatArrayOf(
                                        hsv[0],
                                        (change.position.x / size.width).coerceIn(0f, 1f),
                                        1f - (change.position.y / size.height).coerceIn(0f, 1f),
                                    )
                                }
                            }
                        }
                ) {
                    // 明度罩：底部黑色
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black)
                                )
                            )
                    )
                    // 指示点
                    if (panelSize != androidx.compose.ui.geometry.Size.Zero) {
                        Box(
                            Modifier
                                .offset {
                                    androidx.compose.ui.unit.IntOffset(
                                        (hsv[1] * panelSize.width - markerPx).coerceAtLeast(0f).toInt(),
                                        ((1f - hsv[2]) * panelSize.height - markerPx).coerceAtLeast(0f).toInt(),
                                    )
                                }
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(1.dp, Color.Black.copy(alpha = 0.4f), CircleShape)
                        )
                    }
                }
                // 色相条
                var hueWidth by remember { mutableStateOf(0f) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.horizontalGradient(
                                (0..30).map { i ->
                                    Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 12f, 1f, 1f)))
                                }
                            )
                        )
                        .onSizeChanged { hueWidth = it.width.toFloat() }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                hsv = floatArrayOf(
                                    (down.position.x / size.width).coerceIn(0f, 1f) * 360f,
                                    maxOf(hsv[1], 0.001f),
                                    maxOf(hsv[2], 0.001f),
                                )
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: break
                                    hsv = floatArrayOf(
                                        (change.position.x / size.width).coerceIn(0f, 1f) * 360f,
                                        maxOf(hsv[1], 0.001f),
                                        maxOf(hsv[2], 0.001f),
                                    )
                                }
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    (hsv[0] / 360f * hueWidth - markerPx).coerceAtLeast(0f).toInt(),
                                    0,
                                )
                            }
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.dp, Color.Black.copy(alpha = 0.4f), CircleShape)
                    )
                }
                // 预览 + 手动 HEX 输入（合法则实时预览）
                //
                // 输入框必须有**独立的**文本状态：把 value 直接绑到 hsv 派生出的
                // "#RRGGBB" 上时，已显示文本固定 7 字符，用户按一个键就变 8 字符（>7 被丢弃）
                // 或删成 5 位（非法被丢弃），状态不变 → 输入被回滚，手动输入根本打不进字。
                var hexText by remember { mutableStateOf(String.format("#%06X", current.toArgb() and 0xFFFFFF)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(current)
                    )
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { input ->
                            // 只保留 HEX 允许的字符并限长，剩下的交给"合法才应用"的判定
                            val cleaned = input.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '#' }
                                .take(7)
                            hexText = cleaned
                            val raw = cleaned.removePrefix("#")
                            if (raw.length == 6) {
                                val argb = raw.toLong(16).toInt() or 0xFF000000.toInt()
                                val h = floatArrayOf(0f, 0f, 0f)
                                android.graphics.Color.colorToHSV(argb, h)
                                hsv = h
                            }
                        },
                        label = { Text("HEX") },
                        placeholder = { Text("#00FFFF") },
                        singleLine = true,
                        isError = hexText.removePrefix("#").length != 6,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current.toArgb()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ──────────────────────────────────────────────────────────────
// 分区导航
// ──────────────────────────────────────────────────────────────

/** 判定「当前分区」时允许的提前量：分区标题滚到顶部下方一点点就算已进入，避免高亮慢半拍 */
private const val SECTION_STICKY_SLOP = 48

/**
 * 设置页分区。**顺序必须与页面从上到下的顺序一致** —— 顶部分类条按这个顺序渲染，
 * 「当前分区」也是按偏移量取最后一个已越过的分区。
 */
private enum class SettingsSection(val label: String) {
    Appearance("外观"),
    Background("背景图"),
    Storage("存储"),
    JavaRuntime("Java"),
    BackgroundAndUpdate("后台"),
    About("关于"),
}

/**
 * 顶部吸附分类条：点击平滑滚到对应分区，滚动设置页时高亮当前分区。
 *
 * 设置页有 7 个分区、20 多个条目，一整页滚下来找一项要滑很久；
 * 分类条固定不滚动才有「吸附」的意义（见 SettingsScreen 的根布局）。
 */
@Composable
private fun SettingsCategoryBar(
    current: SettingsSection,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barScroll = rememberScrollState()
    val chipOffsets = remember { mutableStateMapOf<SettingsSection, Int>() }
    val chipWidths = remember { mutableStateMapOf<SettingsSection, Int>() }

    // 当前分区变化时把它滚进可视范围：设置页很长，滚到下半部分时高亮会落在右侧看不见的位置，
    // 分类条就失去了「我在哪」的作用（真机实测：滚到「关于」时条上没有任何高亮可见）。
    LaunchedEffect(current) {
        val start = chipOffsets[current] ?: return@LaunchedEffect
        val width = chipWidths[current] ?: 0
        val viewport = barScroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val desired = when {
            start < barScroll.value -> start
            start + width > barScroll.value + viewport -> start + width - viewport
            else -> null            // 已经完整可见，不折腾
        }
        desired?.let { barScroll.animateScrollTo(it.coerceIn(0, barScroll.maxValue)) }
    }

    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(barScroll)
            .padding(horizontal = M3Spacing.screenMargin, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsSection.entries.forEach { section ->
            FilterChip(
                selected = section == current,
                onClick = { onSelect(section) },
                label = { Text(section.label) },
                modifier = Modifier.onGloballyPositioned { coords ->
                    val x = coords.positionInParent().x.toInt()
                    if (chipOffsets[section] != x) chipOffsets[section] = x
                    val w = coords.size.width
                    if (chipWidths[section] != w) chipWidths[section] = w
                },
            )
        }
    }
}
