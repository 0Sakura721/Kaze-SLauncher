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

// ──────────────────────────────────────────────────────────────
// 分组版式的零件
// ──────────────────────────────────────────────────────────────

/**
 * 分组头卡：一张浮起卡片就是一组设置的开头，行紧随其后。
 * 组与组之间用 `Modifier.padding(top = M3Spacing.betweenParts)` 补足 16dp。
 */
@Composable
internal fun GroupHeader(
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
internal fun RowChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 分组脚注：长说明（为什么这么做）放在行组之后完整换行，不塞进 72dp 的列表行 */
@Composable
internal fun SettingNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** IPv4 格式校验（点分四段、每段 0–255）；MCP 监听地址弹窗用，不做 DNS 解析 */
internal fun isValidIpv4(s: String): Boolean {
    val parts = s.split(".")
    if (parts.size != 4) return false
    return parts.all { p ->
        p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255
    }
}

/** 互斥选择弹窗：分段选择即时生效（与旧版点 chip 一样，点完就写 prefs） */
@Composable
internal fun <T> ChoiceDialog(
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
internal fun SliderDialog(
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
internal fun AboutDialog(onDismiss: () -> Unit) {
    // 许可证全文：GPL-3.0 第 4/5 条要求随程序提供许可证副本。
    // 正文从 assets 读取（构建时由仓库根目录的 LICENSE / THIRD_PARTY_NOTICES.md 复制），
    // 这样应用内看到的就是随发行提供的那一份，不会与仓库脱节。
    var legalDoc by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    if (legalDoc != null) {
        AlertDialog(
            onDismissRequest = { legalDoc = null },
            title = { Text(if (legalDoc == "gpl") "GNU GPL-3.0 全文" else "第三方组件声明") },
            text = {
                val body = remember(legalDoc) {
                    runCatching {
                        ctx.assets.open(if (legalDoc == "gpl") "LICENSE-GPL-3.0.txt" else "THIRD_PARTY_NOTICES.md")
                            .bufferedReader().use { it.readText() }
                    }.getOrElse { "许可证文本读取失败：${it.message}" }
                }
                Text(
                    body,
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            },
            confirmButton = { TextButton(onClick = { legalDoc = null }) { Text("关闭") } },
        )
    }
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

/**
 * 更新流程的状态块：只有「有事发生」时才出现一张卡，Idle 时什么都不显示。
 *
 * 每个**终点状态**（已是最新 / 发现新版本 / 检查失败）都必须有出口，
 * 否则用户点进来一次之后就再也回不到能操作的状态：
 *  - Latest 与 Found 给「关闭」（[onDismiss] 回到 Idle，检查入口同时解禁）；
 *  - Error 与 Latest 给「重新检查」；
 *  - Found 不再同时禁用「检查更新」那一行（见调用点），所以即使不关卡片也能重查。
 */
@Composable
internal fun UpdateStatusCard(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onDownload: (UpdateChecker.ReleaseInfo) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 卡片的正文一律用 content 具名传入：尾随 lambda 会绑到 trailing（标题行右侧），不是正文区
    when (state) {
        is AppUpdateState.Idle -> Unit

        is AppUpdateState.Checking -> M3ECard(
            variant = M3ECardVariant.Outlined,
            title = "正在检查更新…",
            content = {
                WavyLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
            },
        )

        is AppUpdateState.Error -> M3ECard(
            variant = M3ECardVariant.Outlined,
            title = "检查更新失败",
            supporting = state.msg,
            content = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCheck) { Text("重试") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            },
        )

        is AppUpdateState.Latest -> M3ECard(
            variant = M3ECardVariant.Outlined,
            title = "已是最新版本",
            supporting = "（或仓库暂未发布更新）",
            content = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCheck) { Text("重新检查") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            },
        )

        is AppUpdateState.Found -> M3ECard(
            variant = M3ECardVariant.Elevated,
            title = "发现新版本 ${state.info.tag}",
            // 设置页的检查更新**不显示 changelog**（真机要求）：这里只做"手动检查 + 一键更新"，
            // 不再把 Release 正文摊开。想看更新说明，去应用启动时的更新弹窗。
            supporting = "点「下载并安装」开始更新",
            content = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDownload(state.info) }) { Text("下载并安装") }
                    // 「以后再说」：卡片收起、检查入口解禁 —— 原来这一步是死端
                    TextButton(onClick = onDismiss) { Text("以后再说") }
                }
            },
        )

        is AppUpdateState.Downloading -> M3ECard(
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

/** 「行 → 选择器」弹窗的标识（同一时刻只开一个） */
internal enum class SettingsDialog {
    ThemeStyle,
    ThemeMode,
    ColorSource,
    DarkStyle,
    FgColor,
    BgBlur,
    BgMask,
    UpdateChannel,
    UpdateMode,
    McpPort,
    McpRange,
    McpToken,
    About,
}

/** 背景图分组的行序：有图时才出现后四行，行形状按序号连续 */
internal enum class BackgroundRow { Image, Toggle, Blur, Mask, Clear }

internal fun themeModeLabel(value: Int): String = when (value) {
    1 -> "浅色模式"
    2 -> "深色模式"
    else -> "跟随系统"
}

internal fun darkStyleLabel(value: Int): String = if (value == 1) "AMOLED 纯黑" else "普通黑"

internal fun updateModeLabel(value: String): String = when (value) {
    "patch" -> "增量补丁（省流量，约 5%）"
    else -> "完整安装包（默认，最稳妥）"
}

internal fun updateChannelLabel(value: String): String =
    if (value == "stable") "仅正式版" else "预览版（含测试版）"

/** 自研 HSV 取色器（自研实现，无第三方依赖；无第三方依赖）：
 * 色相条 + 饱和度/明度面板 + 实时预览，确定后回调 ARGB */
@Composable
internal fun ColorPickerDialog(
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
internal const val SECTION_STICKY_SLOP = 48

/**
 * 设置页分区。**顺序必须与页面从上到下的顺序一致** —— 顶部分类条按这个顺序渲染，
 * 「当前分区」也是按偏移量取最后一个已越过的分区。
 */
internal enum class SettingsSection(val label: String) {
    Appearance("外观"),
    Background("背景图"),
    Storage("存储"),
    JavaRuntime("Java"),
    Console("控制台"),
    Mcp("MCP"),
    BackgroundAndUpdate("后台"),
    About("关于"),
}

/**
 * 顶部吸附分类条：点击平滑滚到对应分区，滚动设置页时高亮当前分区。
 *
 * 设置页有 8 个分区、20 多个条目，一整页滚下来找一项要滑很久；
 * 分类条固定不滚动才有「吸附」的意义（见 SettingsScreen 的根布局）。
 */
@Composable
internal fun SettingsCategoryBar(
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
