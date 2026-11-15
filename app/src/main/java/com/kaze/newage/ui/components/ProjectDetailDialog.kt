package com.kaze.newage.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.i18n.t
import com.kaze.newage.ui.ContentController

/**
 * 项目详情对话框（对齐 ZalithLauncher2 的下载页信息层级）。
 *
 * 列表行只有一句简介；点进详情后展示：图标、标题、下载量、分类标签、完整介绍，
 * 以及「收藏 / 安装」。安装仍走版本选择，避免"点一下就装错版本"。
 */
@Composable
fun ProjectDetailDialog(
    state: ContentController.ProjectDetail,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val project = state.project ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteIcon(
                    url = project.icon_url,
                    fallback = if (project.project_type == "resourcepack") Icons.Filled.FileDownload
                    else Icons.Filled.Extension,
                    size = 48.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        project.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatDownloadsLong(project.downloads)} ${t("次下载")} · ${project.project_type.ifBlank { t("模组") }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = t("收藏"),
                        tint = if (favorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (project.categories.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        project.categories.take(6).forEach { category ->
                            AssistChip(onClick = {}, label = { Text(category) })
                        }
                    }
                }
                if (state.loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                Text(
                    project.description.ifBlank { t("（无描述）") },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val body = remember(state.project?.body) { state.project?.body?.let { markdownToPlain(it) }.orEmpty() }
                if (body.isNotBlank()) {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                state.error?.let {
                    Text(
                        t("详情加载失败：%s", it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onInstall) { Text(t("安装")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("关闭")) } },
    )
}

/** 把 Modrinth 的 Markdown 简介压成纯文本（图片/代码块/链接语法直接去掉） */
private fun markdownToPlain(md: String): String {
    if (md.isBlank()) return ""
    return md
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("```[\\s\\S]*?```"), "")
        .replace(Regex("`"), "")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "· ")
        .replace(Regex("[*_~]"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
        .take(1500)
}

private fun formatDownloadsLong(value: Long): String =
    if (value >= 1_000_000) String.format("%.1fM", value / 1_000_000.0)
    else if (value >= 1_000) String.format("%.1fK", value / 1_000.0)
    else value.toString()
