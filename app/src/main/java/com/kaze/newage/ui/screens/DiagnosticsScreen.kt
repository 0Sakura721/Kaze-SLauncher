package com.kaze.newage.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.theme.M3Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断日志：应用自身的 logcat 落盘（由 [com.kaze.newage.core.log.AppLogStore] 采集）
 * 与环境自检（diagnostics.txt）合在一页，方便出问题时直接看、直接发出来。
 *
 * 放在应用内的原因：Android 的 logcat 只在内存环形缓冲里，一崩/一重启就没了，
 * 而普通用户不会为了提个 bug 去装 adb。「诊断日志」把这两样都留在盘上。
 */
@Composable
fun DiagnosticsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("读取中…") }
    var envText by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    val appLog = viewModel.appLog
    // 触发一次重读（刷新按钮 / 清空后）
    val tick by remember(reload) { mutableStateOf(reload) }

    LaunchedEffect(tick) {
        text = appLog.tail(1200).ifBlank { "（暂无日志）" }
        envText = withContext(Dispatchers.IO) {
            runCatching {
                val f = viewModel.prootEnv.diagnosticsFile()
                if (f.isFile) f.readText().takeLast(4000) else "（尚未生成，应用启动时会写入一次）"
            }.getOrDefault("（读取失败）")
        }
    }

    Column(Modifier.fillMaxSize()) {
        M3EScreenHeader(
            title = "诊断日志",
            subtitle = "应用日志 · 环境自检",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { reload++ }) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Text("  刷新", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = {
                    // 分享当前日志文件：走 FileProvider（file:// 会被系统直接拒绝）
                    scope.launch {
                        runCatching {
                            val f = appLog.currentFile().takeIf { it.isFile } ?: appLog.files().firstOrNull()
                            val target = f ?: error("还没有日志文件")
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                target,
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                android.content.Intent.createChooser(intent, "分享诊断日志")
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            android.widget.Toast.makeText(
                                context,
                                "分享失败：${it.message}",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Text("  分享", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        appLog.clear()
                        reload++
                    }
                }) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                    Text("  清空", style = MaterialTheme.typography.labelLarge)
                }
            }

            M3ECard(
                variant = M3ECardVariant.Outlined,
                title = "应用日志",
                supporting = "logcat 只存在内存里（重启即失），这里留存最近 ${com.kaze.newage.core.log.AppLogStore.KEEP_FILES} 天",
            ) {
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            M3ECard(
                variant = M3ECardVariant.Outlined,
                title = "环境自检",
                supporting = "rootfs 关键文件、权限、容器 DNS、execve 实测",
            ) {
                SelectionContainer {
                    Text(
                        envText,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
