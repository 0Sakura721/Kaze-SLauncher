package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.download.CoreDownload
import com.mcserver.launcher.core.download.CoreSources
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.core.server.PluginManager
import com.mcserver.launcher.data.CoreType
import com.mcserver.launcher.data.InstanceStatus
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.ui.components.Chip
import com.mcserver.launcher.ui.components.ConfirmDialog
import com.mcserver.launcher.ui.components.EmptyState
import com.mcserver.launcher.ui.components.KazeTopBar
import com.mcserver.launcher.ui.components.LocalUiMessenger
import com.mcserver.launcher.ui.components.StatusBadge
import com.mcserver.launcher.ui.screens.tabs.AddonTab
import com.mcserver.launcher.ui.screens.tabs.ConfigTab
import com.mcserver.launcher.ui.screens.tabs.ConsoleTab
import com.mcserver.launcher.ui.screens.tabs.WorldTab
import com.mcserver.launcher.ui.theme.KazeCorners
import com.mcserver.launcher.ui.theme.KazeSizes
import com.mcserver.launcher.ui.theme.KazeSpacing
import com.mcserver.launcher.ui.theme.KazeType
import com.mcserver.launcher.core.server.ServerManager
import kotlinx.coroutines.launch
import java.io.File

private val TAB_LABELS = listOf("控制台", "插件", "配置", "世界")

@Composable
fun InstanceDetailScreen(instance: ServerInstance, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val messenger = LocalUiMessenger.current
    val scope = rememberCoroutineScope()
    val systemPaddings = WindowInsets.systemBars.asPaddingValues()
    val status by ServerManager.status.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val commandHistory = remember { mutableStateListOf<String>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var currentName by remember { mutableStateOf(instance.name) }
    var checkCoreUpdate by remember { mutableStateOf(false) }
    var upgradeInfo by remember { mutableStateOf<CoreDownload?>(null) }
    var upgrading by remember { mutableStateOf(false) }
    var upgradeMsg by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(systemPaddings.calculateTopPadding()))

        CompactTopBar(
            instance = instance.copy(name = currentName),
            status = status,
            onBack = onBack,
            onRename = { showRename = true },
            onUpgrade = { checkCoreUpdate = true },
            onDelete = { showDeleteConfirm = true },
            onStart = {
                scope.launch {
                    val r = ServerManager.start(instance)
                    if (r.isFailure) messenger.toastError(r.exceptionOrNull()?.message ?: "启动失败")
                }
            },
            onStop = {
                scope.launch { ServerManager.stop() }
            }
        )

        Divider()

        TabRow(
            selectedTabIndex = tab,
            modifier = Modifier.fillMaxWidth(),
            containerColor = Color.Transparent,
            divider = { Divider() }
        ) {
            TAB_LABELS.forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ConsoleTab(instance = instance, commandHistory = commandHistory, onStart = {
                    scope.launch {
                        val r = ServerManager.start(instance)
                        if (r.isFailure) messenger.toastError(r.exceptionOrNull()?.message ?: "启动失败")
                    }
                })
                1 -> AddonTab(instance)
                2 -> ConfigTab(instance)
                3 -> WorldTab(instance)
            }
        }
    }

    if (showRename) {
        RenameInstanceDialog(
            instance = instance,
            initialName = currentName,
            onDismiss = { showRename = false },
            onRenamed = { newName -> currentName = newName; showRename = false }
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除实例",
            message = "将删除「${instance.name}」及其全部文件(核心/世界/插件/模组),此操作不可恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                scope.launch {
                    if (ServerManager.isRunningFor(instance.id)) {
                        messenger.toast("服务器运行中,请先停止再删除")
                        return@launch
                    }
                    InstanceStore.delete(instance.id)
                    onBack()
                }
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    if (checkCoreUpdate) {
        CoreUpgradeDialog(
            instance = instance,
            upgradeInfo = upgradeInfo,
            upgradeMsg = upgradeMsg,
            upgrading = upgrading,
            onDismiss = { checkCoreUpdate = false },
            onUpgrade = { info ->
                upgrading = true
                scope.launch {
                    val dir = instance.dir(InstanceStore.instancesDir)
                    val oldJar = dir.listFiles()?.firstOrNull {
                        it.extension == "jar" && !it.name.contains("installer") && !it.name.endsWith(".bak")
                    }
                    // 备份旧核心(失败可手动将 .bak 改回 .jar 恢复)
                    if (oldJar != null) {
                        oldJar.copyTo(File(dir, "${oldJar.name}.bak"), overwrite = true)
                        oldJar.delete()
                    }
                    DownloadCenter.enqueue(
                        id = "upgrade-${instance.id}-${System.currentTimeMillis()}",
                        title = "升级核心 ${instance.coreType.displayName} ${info.fileName}",
                        urls = listOf(info.url),
                        destFile = File(dir, info.fileName)
                    )
                    upgrading = false
                    upgradeInfo = null
                    checkCoreUpdate = false
                    messenger.toast("已加入下载中心,完成后重启服务器生效(旧核心已备份为 .bak)")
                }
            }
        )
        LaunchedEffect(Unit) {
            val r = runCatching {
                val versions = CoreSources.fetchVersions(instance.coreType).getOrThrow()
                val sameVersion = versions.firstOrNull { it.id == instance.mcVersion }
                    ?: return@runCatching UpgradeResult.VersionGone(instance.mcVersion, versions.firstOrNull()?.id)
                CoreSources.resolveDownload(instance.coreType, sameVersion.id, "", null)
                    .getOrNull()?.let { dl ->
                        val dir = instance.dir(InstanceStore.instancesDir)
                        val existing = dir.listFiles()?.firstOrNull {
                            it.extension == "jar" && !it.name.contains("installer") && !it.name.endsWith(".bak")
                        }?.name
                        if (existing == dl.fileName) UpgradeResult.None else UpgradeResult.Found(dl)
                    } ?: UpgradeResult.None
            }.getOrElse { UpgradeResult.Error(it.message ?: "检查失败") }
            when (r) {
                is UpgradeResult.Found -> upgradeInfo = r.download
                is UpgradeResult.None -> upgradeMsg = "当前版本 ${instance.mcVersion} 已是最新构建,无需升级。"
                is UpgradeResult.VersionGone -> upgradeMsg = "当前版本 ${instance.mcVersion} 已不再支持;如需新版请新建实例(检测到最新:${r.latest ?: "无"})。"
                is UpgradeResult.Error -> upgradeMsg = r.msg
            }
        }
    }
}

@Composable
private fun CompactTopBar(
    instance: ServerInstance,
    status: InstanceStatus,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onUpgrade: () -> Unit,
    onDelete: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val running = status == InstanceStatus.RUNNING

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KazeSpacing.sm, vertical = KazeSpacing.xs),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(KazeSizes.buttonHeight)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(KazeSpacing.sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        instance.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(KazeSpacing.xs))
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Edit, "重命名",
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "${instance.coreType.displayName} ${instance.mcVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(KazeSpacing.sm))
            StatusBadge(status = status)
            Spacer(Modifier.width(KazeSpacing.sm))
            // 启停按钮:与首页统一用 40dp 方形 tinted
            val actionSize = 40.dp
            if (running) {
                Box(
                    Modifier
                        .size(actionSize)
                        .clip(KazeCorners.tiny)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .clickable(onClick = onStop),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Stop, "停止",
                        Modifier.size(KazeSizes.iconSmall),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(actionSize)
                        .clip(KazeCorners.tiny)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable(onClick = onStart),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow, "启动",
                        Modifier.size(KazeSizes.iconSmall),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.width(KazeSpacing.xxs))
            var showMore by remember { mutableStateOf(false) }
            IconButton(onClick = { showMore = true }, modifier = Modifier.size(KazeSizes.buttonHeight)) {
                Icon(Icons.Filled.MoreVert, "更多操作")
            }
            DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                DropdownMenuItem(
                    text = { Text("升级核心", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Filled.SystemUpdate, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                    onClick = { showMore = false; onUpgrade() }
                )
                DropdownMenuItem(
                    text = { Text("重命名", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                    onClick = { showMore = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("删除实例", style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.error)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                    onClick = { showMore = false; onDelete() }
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KazeSpacing.pageHorizontal, vertical = KazeSpacing.xxs),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Chip(text = PluginManager.dirLabel(instance), compact = true)
        }
        Spacer(Modifier.height(KazeSpacing.xs))
    }
}

private sealed class UpgradeResult {
    data class Found(val download: CoreDownload) : UpgradeResult()
    object None : UpgradeResult()
    data class VersionGone(val current: String, val latest: String?) : UpgradeResult()
    data class Error(val msg: String) : UpgradeResult()
}

@Composable
private fun CoreUpgradeDialog(
    instance: ServerInstance,
    upgradeInfo: CoreDownload?,
    upgradeMsg: String?,
    upgrading: Boolean,
    onDismiss: () -> Unit,
    onUpgrade: (CoreDownload) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("升级核心", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text("当前:${instance.coreType.displayName} ${instance.mcVersion}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                when {
                    upgradeInfo == null && upgradeMsg == null -> {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在检查最新版本…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    upgradeMsg != null ->
                        Text(upgradeMsg, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> upgradeInfo?.let { info ->
                        Text("发现新版本:${info.fileName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("下载后将备份当前核心并替换,重启服务器生效。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (upgrading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (upgradeInfo != null && !upgrading) {
                Button(onClick = { onUpgrade(upgradeInfo) }) {
                    Icon(Icons.Filled.SystemUpdate, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("下载并替换")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (upgradeInfo != null || upgradeMsg != null) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun RenameInstanceDialog(
    instance: ServerInstance,
    initialName: String,
    onDismiss: () -> Unit,
    onRenamed: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名实例", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("实例标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val newName = name.trim()
                if (newName.isNotEmpty() && newName != instance.name) {
                    InstanceStore.update(instance.copy(name = newName))
                }
                onRenamed(if (newName.isEmpty()) instance.name else newName)
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
