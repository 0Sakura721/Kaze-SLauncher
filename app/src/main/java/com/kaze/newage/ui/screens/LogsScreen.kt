package com.kaze.newage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.BackgroundCard
import com.kaze.newage.ui.components.CardTitleLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志与崩溃报告查看页：crash-reports 目录下的 txt 与 logs/latest.log。
 * 内容读取文件末尾（大文件只取末尾 300KB），等宽字体展示。
 */
@Composable
fun LogsScreen(
    viewModel: AppViewModel,
    instanceId: String,
    onBack: () -> Unit,
) {
    val instances by viewModel.instances.collectAsState()
    val instance = instances.firstOrNull { it.id == instanceId }
    if (instance == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }

    var selected by remember { mutableStateOf<File?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    val crashReports = remember(instanceId, refresh) {
        File(instance.dir, "crash-reports").listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    val latestLog = remember(instanceId, refresh) { File(instance.dir, "logs/latest.log") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ── 顶部栏 ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { if (selected != null) selected = null else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(if (selected != null) selected!!.name else "日志与崩溃报告", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(instance.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (selected != null) {
            // ── 文件查看器 ──
            val content = remember(selected) { readTail(selected!!) }
            BackgroundCard(Modifier.fillMaxWidth()) {
                CardTitleLayout(
                    "${selected!!.name}（${content.length / 1024} KB，显示末尾 ${content.lines().size} 行）"
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .clip(RoundedCornerShape(8.dp))
                            .background(com.kaze.newage.ui.theme.consoleBackgroundColor())
                            .padding(10.dp)
                    ) {
                        Text(
                            content,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = com.kaze.newage.ui.theme.consoleLineColor(com.kaze.newage.core.console.LineType.Info),
                        )
                    }
                }
            }
        } else {
            // ── 崩溃报告列表 ──
            BackgroundCard(Modifier.fillMaxWidth()) {
                CardTitleLayout("崩溃报告（${crashReports.size}）") {
                    if (crashReports.isEmpty()) {
                        Text(
                            "暂无崩溃报告。服务端异常崩溃后会生成 crash-reports/*.txt。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    crashReports.forEach { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selected = f }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(f.name.removeSuffix(".txt"), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified())),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "查看",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // ── 服务器日志 ──
            BackgroundCard(Modifier.fillMaxWidth()) {
                CardTitleLayout("服务器日志") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { if (latestLog.exists()) selected = latestLog }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text("latest.log", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (latestLog.exists()) "${latestLog.length() / 1024} KB · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(latestLog.lastModified()))}"
                                else "尚未生成（服务端首次运行后出现）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (latestLog.exists()) {
                            Text(
                                "查看",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

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
