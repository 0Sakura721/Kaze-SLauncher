package com.kaze.newage.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.addons.ModrinthVersion
import com.kaze.newage.ui.ContentController
import com.kaze.newage.core.i18n.t

/**
 * 安装前的**版本选择**对话框。
 *
 * 以前装模组/插件/资源包都是"自动挑最新兼容版本"，用户没法回退到旧版本，也看不到
 * 到底装的是哪个文件。这里把 Modrinth 的版本列表摊开：
 *  - 默认只列兼容当前实例（加载器 + MC 版本）的版本；
 *  - 「显示全部 MC 版本」可以放宽到该加载器的所有版本（很多旧整合包需要）。
 */
@Composable
fun VersionPickerDialog(
    projectTitle: String,
    state: ContentController.VersionPickerState,
    onToggleAll: (Boolean) -> Unit,
    onInstall: (ModrinthVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    projectTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    t("选择要安装的版本"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 430.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.includeAll, onCheckedChange = onToggleAll)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(t("显示全部 MC 版本"), style = MaterialTheme.typography.labelMedium)
                        Text(
                            t("关闭时只显示兼容当前实例的版本"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(0.dp))
                if (state.loading) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                } else {
                    state.error?.let {
                        Text(
                            t("版本列表加载失败：%s", it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    if (state.versions.isEmpty() && state.error == null) {
                        Text(
                            t("没有可用版本；可以打开「显示全部 MC 版本」再试"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(state.versions) { version ->
                            VersionRow(version = version, onInstall = { onInstall(version) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("关闭")) } },
    )
}

@Composable
private fun VersionRow(version: ModrinthVersion, onInstall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                version.version_number.ifBlank { version.id.take(8) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                buildString {
                    if (version.game_versions.isNotEmpty()) {
                        append(t("MC %s", version.game_versions.take(4).joinToString("/")))
                        if (version.game_versions.size > 4) append("…")
                    }
                    if (version.loaders.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(version.loaders.joinToString("/"))
                    }
                }.ifBlank { t("未标注兼容版本") },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onInstall) { Text(t("安装")) }
    }
}
