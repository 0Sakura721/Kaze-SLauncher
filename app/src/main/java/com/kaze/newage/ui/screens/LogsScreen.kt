package com.kaze.newage.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.console.LineType
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EConnectedList
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3ESegmentedRow
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.theme.consoleBackgroundColor
import com.kaze.newage.ui.theme.consoleLineColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 启动日志筛选：全部 / 只看失败（失败 = crash-reports 里留下的那几份） */
private enum class LogFilter(val label: String) {
    All("全部"),
    Failure("失败"),
}

/**
 * 启动日志：crash-reports 目录下的 txt、运行日志（console-output.log）与服务器日志（latest.log）。
 *
 * 版式来自 m3e-canvas 生成的 `docs/m3e/prompt-启动日志.md`：
 *   屏幕头（返回 + 标题 + 刷新）→ 全部/失败 筛选片 → 记录列表（图标与颜色直接表达失败，
 *   原因写在摘要里）→ 导出全部 / 清空记录（相连按钮组）。
 *
 * 文件内容仍读取末尾（大文件只取末尾 300KB），等宽字体展示在深色终端画布上。
 */
@Composable
fun LogsScreen(
    viewModel: AppViewModel,
    instanceId: String,
    onBack: () -> Unit,
) {
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val instance = instances.firstOrNull { it.id == instanceId }
    if (instance == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var selected by remember { mutableStateOf<File?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(LogFilter.All) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val crashReports = remember(instanceId, refresh) {
        File(instance.dir, "crash-reports").listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    val latestLog = remember(instanceId, refresh) { File(instance.dir, "logs/latest.log") }
    val runLog = remember(instanceId, refresh) { File(instance.dir, "console-output.log") }
    // 导出范围：崩溃报告 + 运行日志 + 服务器日志（存在哪个导哪个）
    val exportable = crashReports + listOfNotNull(
        runLog.takeIf { it.exists() },
        latestLog.takeIf { it.exists() },
    )

    // 导出全部：把上面的文件合成一个 txt 交给 SAF 保存。
    // MIME 用 text/plain：这里导出的是 .txt（不是 .log），vivo 的 .log 自动改名问题不适用
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { target ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val text = buildString {
                        appendLine("KAZE SLauncher 日志导出")
                        appendLine("实例：${instance.name}")
                        appendLine("目录：${instance.dir.absolutePath}")
                        appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                        exportable.forEach { f ->
                            appendLine()
                            appendLine("══════════ ${f.name} · ${fileMeta(f)} ══════════")
                            appendLine(readTail(f))
                        }
                    }
                    context.contentResolver.openOutputStream(target)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    // 清空记录：只删崩溃报告（那份文件才是「启动失败记录」）。
    // latest.log / console-output.log 是服务端与应用正在写入的活文件，删掉会让本次会话的
    // 「运行日志 / 服务器日志」整段消失，不属于「清历史记录」的范围，因此不动它们
    fun clearRecords() {
        val open = selected
        if (open != null && crashReports.contains(open)) selected = null
        crashReports.forEach { runCatching { it.delete() } }
        refresh++
    }

    Column(
        Modifier
            .fillMaxSize()
            // 崩溃报告是列表全量展开的，不滚动的话记录一多，下面两张日志卡会被推出视口且
            // 完全触达不到（反复崩溃的服务端必然产生多份报告）
            .verticalScroll(rememberScrollState())
            // 本页不在常驻底栏的四个目的地里（AppRoot 只在 Dest 路由上显示底栏），
            // 所以不需要 bottomBarSpace 的额外留白
            .padding(horizontal = M3Spacing.screenMargin)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 屏幕头：返回 +（文件名 / 启动日志）+ 刷新 ──
        val openFile = selected
        M3EScreenHeader(
            title = openFile?.name ?: "启动日志",
            subtitle = if (openFile != null) {
                "${instance.name} · 修改于 ${fileTime(openFile)}"
            } else {
                instance.name
            },
            leading = {
                // 返回：正在看文件时先退回列表，再按一次才退出本页（与旧版一致）
                IconButton(onClick = { if (selected != null) selected = null else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            trailing = {
                // 刷新：重新扫描 crash-reports / logs 目录，并重读当前打开的文件
                //（运行中的服务端一直在往 latest.log 里写）
                IconButton(onClick = { refresh++ }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        if (openFile != null) {
            // ── 文件查看器：深色终端画布（与主控制台同一套观感）──
            val content = remember(openFile, refresh) { readTail(openFile) }
            M3ECard(
                variant = M3ECardVariant.Filled,
                // 旧版把「多大 / 显示了多少行」写在卡片标题里，这里保留成辅助文本
                supporting = "${content.length / 1024} KB · 显示末尾 ${content.lines().size} 行",
                content = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(M3Shape.large)
                            .background(consoleBackgroundColor())
                            .padding(12.dp)
                    ) {
                        // 外层 Column 已经是全页滚动，这里不再套内层滚动
                        //（旧版内层 verticalScroll 在无界高度下永远滚不动，是死代码）
                        Text(
                            content,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = consoleLineColor(LineType.Info),
                        )
                    }
                },
            )
        } else {
            // ── 筛选：全部 / 只看失败 ──
            M3ESegmentedRow(
                options = LogFilter.entries,
                selected = filter,
                label = { it.label },
                onSelect = { filter = it },
            )

            // ── 失败记录：崩过的服务端都会在 crash-reports 里留下一份 txt ──
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(
                    if (filter == LogFilter.Failure) "启动失败记录（${crashReports.size}）"
                    else "崩溃报告（${crashReports.size}）"
                )
                if (crashReports.isEmpty()) {
                    M3ECard(
                        variant = M3ECardVariant.Outlined,
                        title = if (filter == LogFilter.Failure) "暂无启动失败记录" else "暂无崩溃报告",
                        supporting = "服务端异常崩溃后会生成 crash-reports/*.txt，这里可以直接翻看它留下的最后输出",
                        content = { },
                    )
                } else {
                    M3EConnectedList(crashReports.size) { i, shape ->
                        val f = crashReports[i]
                        M3EListItem(
                            headline = f.name.removeSuffix(".txt"),
                            // 失败原因直接写在摘要里：不必点进去才知道为什么崩
                            supporting = listOf(fileMeta(f), crashReason(f))
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            leadingIcon = Icons.Filled.ErrorOutline,
                            iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                            onClick = { selected = f },
                            shape = shape,
                            trailing = {
                                Text(
                                    "查看",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    }
                }
            }

            if (filter == LogFilter.All) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("完整日志")
                    M3EConnectedList(2) { i, shape ->
                        if (i == 0) {
                            LogFileRow(
                                headline = "运行日志",
                                file = runLog,
                                missingHint = "启动/部署过服务器后生成（含环境检查、Java 安装、启动报错等完整记录）",
                                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                                shape = shape,
                                onClick = { selected = runLog },
                            )
                        } else {
                            LogFileRow(
                                headline = "服务器日志",
                                file = latestLog,
                                missingHint = "尚未生成（服务端首次运行后出现）",
                                icon = Icons.Filled.Description,
                                shape = shape,
                                onClick = { selected = latestLog },
                            )
                        }
                    }
                }
            }

            // ── 底部动作：导出全部（填充）+ 清空记录（色调），相连按钮组 ──
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ConnectedAction(
                    label = "导出全部",
                    icon = Icons.Filled.FileDownload,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = M3Shape.groupFirst(56f),
                    enabled = exportable.isNotEmpty(),
                ) {
                    val name = "kaze-${instance.name}-logs-${
                        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    }.txt"
                    exportLauncher.launch(name)
                }
                ConnectedAction(
                    label = "清空记录",
                    icon = Icons.Filled.DeleteSweep,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = M3Shape.groupLast(56f),
                    enabled = crashReports.isNotEmpty(),
                ) { confirmClear = true }
            }
        }
    }

    // 清空是破坏性操作：先确认再删（旧版没有入口，这次把「清历史」补上时必须给二次确认）
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空崩溃报告？") },
            text = {
                Text(
                    "将删除 ${crashReports.size} 份崩溃报告（crash-reports/*.txt），删除后无法恢复。\n" +
                        "latest.log 与 console-output.log 是服务端正在写入的日志，不会被删除。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        clearRecords()
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/** 日志文件行：存在就点进终端画布看，不存在就把「什么时候会有」写清楚 */
@Composable
private fun LogFileRow(
    headline: String,
    file: File,
    missingHint: String,
    icon: ImageVector,
    shape: Shape,
    onClick: () -> Unit,
) {
    val exists = file.exists()
    M3EListItem(
        headline = headline,
        supporting = if (exists) "${file.name} · ${fileMeta(file)}" else missingHint,
        leadingIcon = icon,
        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
        onClick = if (exists) onClick else null,
        shape = shape,
        trailing = {
            Text(
                if (exists) "查看" else "未生成",
                style = MaterialTheme.typography.labelMedium,
                color = if (exists) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * 相连按钮组里的一颗：外角全圆、内侧 8dp（M3 Expressive connected button group），高 56dp。
 * 未启用时降到 surfaceContainerHigh + onSurfaceVariant，不再用主色喊「点我」。
 */
@Composable
private fun RowScope.ConnectedAction(
    label: String,
    icon: ImageVector,
    color: Color,
    contentColor: Color,
    shape: Shape,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .clip(shape),
        shape = shape,
        color = if (enabled) color else scheme.surfaceContainerHigh,
        contentColor = if (enabled) contentColor else scheme.onSurfaceVariant,
        enabled = enabled,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** 小标题：给记录分组（列表本身用 M3EListItem，这里只补一层文字层级） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 文件摘要：大小 · 修改时间 */
private fun fileMeta(file: File): String = "${file.length() / 1024} KB · ${fileTime(file)}"

/** 文件修改时间 */
private fun fileTime(file: File): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(file.lastModified()))

/**
 * 崩溃报告里的「为什么崩」：MC 的 crash-report 头部固定有 "Description: ..." 一行，
 * 没有就退回第一行非空内容。只读头部 4KB —— 报告动辄几百 KB，为了两行摘要全读进来不值得。
 */
private fun crashReason(file: File): String = runCatching {
    val head = file.inputStream().use { ins ->
        val buf = ByteArray(4096)
        val n = ins.read(buf)
        if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
    }
    val lines = head.lineSequence().map { it.trim() }
    val desc = lines.firstOrNull { it.startsWith("Description:") }
        ?.removePrefix("Description:")?.trim()
    (desc?.takeIf { it.isNotBlank() } ?: lines.firstOrNull { it.isNotBlank() }.orEmpty()).take(140)
}.getOrDefault("")

/** 读取文件末尾（最多 300KB），避免大日志撑爆内存 */
private fun readTail(file: File, maxBytes: Int = 300 * 1024): String {
    if (!file.exists()) return "（文件不存在）"
    val len = file.length()
    val skip = if (len > maxBytes) len - maxBytes else 0L
    return file.inputStream().use { ins ->
        ins.skip(skip)
        val bytes = ins.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        if (skip > 0) "…（已截断，仅显示末尾）\n$text" else text
    }
}
