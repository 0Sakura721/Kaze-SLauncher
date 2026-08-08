package com.mcserver.launcher.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.core.server.PluginManager
import com.mcserver.launcher.data.DownloadStatus
import com.mcserver.launcher.data.DownloadStatus.CANCELED
import com.mcserver.launcher.data.DownloadStatus.COMPLETED
import com.mcserver.launcher.data.DownloadStatus.DOWNLOADING
import com.mcserver.launcher.data.DownloadStatus.FAILED
import com.mcserver.launcher.data.DownloadStatus.PAUSED
import com.mcserver.launcher.data.DownloadStatus.PENDING
import com.mcserver.launcher.data.DownloadTask
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.ui.components.AddonManageScreen
import com.mcserver.launcher.ui.components.CompactGroup
import com.mcserver.launcher.ui.components.EmptyState
import com.mcserver.launcher.ui.components.ListGroup
import com.mcserver.launcher.ui.components.LocalUiMessenger
import com.mcserver.launcher.ui.components.RowItemDivider
import com.mcserver.launcher.ui.components.SectionHeader
import com.mcserver.launcher.ui.theme.KazeCorners
import com.mcserver.launcher.ui.theme.KazeError
import com.mcserver.launcher.ui.theme.KazeSpacing
import com.mcserver.launcher.ui.theme.KazeSuccess
import com.mcserver.launcher.ui.theme.KazeWarning
import com.mcserver.launcher.ui.theme.badgeColor
import com.mcserver.launcher.ui.theme.badgeLetter
import com.mcserver.launcher.util.FileFormat
import com.mcserver.launcher.util.FileImporter
import kotlinx.coroutines.launch

private enum class TaskSegment(val label: String) {
    DOWNLOADING("下载中"),
    COMPLETED("已完成")
}

@Composable
fun DownloadScreen(modifier: Modifier = Modifier) {
    val tasks by DownloadCenter.tasks.collectAsState()
    val instances by InstanceStore.instances.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messenger = LocalUiMessenger.current
    var manageInstance by remember { mutableStateOf<ServerInstance?>(null) }
    var segment by remember { mutableStateOf(TaskSegment.DOWNLOADING) }
    var importTargetPicker by remember { mutableStateOf<List<ServerInstance>?>(null) }
    var pendingImportUris by remember { mutableStateOf<List<Uri>?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            if (instances.size == 1) {
                scope.launch {
                    val dest = PluginManager.addonDir(instances.first())
                    uris.forEach { uri ->
                        FileImporter.copyFile(context, uri, dest).onFailure {
                            messenger.toastError("导入失败:${it.message}")
                        }.onSuccess {
                            messenger.toast("已导入 ${it.name}")
                        }
                    }
                }
            } else if (instances.size > 1) {
                pendingImportUris = uris
                importTargetPicker = instances
            } else {
                messenger.toastError("请先创建实例再导入插件/模组")
            }
        }
    }

    BackHandler(enabled = manageInstance != null) { manageInstance = null }

    val activeTasks = tasks.filter { it.status == PENDING || it.status == DOWNLOADING || it.status == PAUSED }
    val finishedTasks = tasks.filter { it.status == COMPLETED || it.status == FAILED || it.status == CANCELED }
    val displayTasks = if (segment == TaskSegment.DOWNLOADING) activeTasks else finishedTasks
    val finishedCount = finishedTasks.size

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = KazeSpacing.pageTop,
                bottom = KazeSpacing.xxxl
            )
        ) {
            item {
                SectionHeader(
                    title = "下载中心",
                    subtitle = "下载任务、插件/模组资源管理"
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KazeSpacing.pageHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KazeSpacing.sm)
                ) {
                    FilterChip(
                        selected = segment == TaskSegment.DOWNLOADING,
                        onClick = { segment = TaskSegment.DOWNLOADING },
                        label = {
                            Text(
                                "${TaskSegment.DOWNLOADING.label}${if (activeTasks.isNotEmpty()) " · ${activeTasks.size}" else ""}",
                                fontSize = 13.sp
                            )
                        },
                        shape = KazeCorners.small
                    )
                    FilterChip(
                        selected = segment == TaskSegment.COMPLETED,
                        onClick = { segment = TaskSegment.COMPLETED },
                        label = {
                            Text(
                                "${TaskSegment.COMPLETED.label}${if (finishedCount > 0) " · $finishedCount" else ""}",
                                fontSize = 13.sp
                            )
                        },
                        shape = KazeCorners.small
                    )
                    Spacer(Modifier.weight(1f))
                    if (finishedCount > 0) {
                        AssistChip(
                            onClick = { DownloadCenter.clearFinished() },
                            label = { Text("清除已完成", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    null,
                                    Modifier.size(14.dp)
                                )
                            },
                            shape = KazeCorners.small
                        )
                    }
                    AssistChip(
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/java-archive",
                                    "application/octet-stream"
                                )
                            )
                        },
                        label = { Text("导入", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.FolderOpen,
                                null,
                                Modifier.size(14.dp)
                            )
                        },
                        shape = KazeCorners.small
                    )
                }
            }
            item { Spacer(Modifier.height(KazeSpacing.sectionTitleGap)) }

            if (instances.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "已安装资源管理",
                        subtitle = "选择实例管理本地插件/模组",
                        count = instances.size
                    )
                    ListGroup {
                        instances.forEachIndexed { idx, inst ->
                            InstanceEntryRow(inst) { manageInstance = inst }
                            if (idx < instances.size - 1) {
                                RowItemDivider(indent = KazeSpacing.xxxl)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(KazeSpacing.groupGap)) }
            }

            item {
                SectionHeader(
                    title = if (segment == TaskSegment.DOWNLOADING) "下载中" else "历史记录",
                    count = displayTasks.size
                )
            }
            if (displayTasks.isEmpty()) {
                item {
                    EmptyState(
                        title = if (segment == TaskSegment.DOWNLOADING) "暂无下载任务" else "暂无历史记录",
                        description = if (segment == TaskSegment.DOWNLOADING) "新建实例或导入插件/模组后,任务会在这里出现" else "已完成/失败/已取消的任务会出现在这里",
                        icon = Icons.Filled.Extension
                    )
                }
            } else {
                item {
                    CompactGroup {
                        displayTasks.forEachIndexed { idx, task ->
                            key(task.id) {
                                DownloadTaskRow(task)
                            }
                            if (idx < displayTasks.size - 1) {
                                RowItemDivider(indent = 26.dp)
                            }
                        }
                    }
                }
            }
        }

        importTargetPicker?.let { targets ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    importTargetPicker = null
                    pendingImportUris = null
                },
                title = { Text("选择导入目标实例") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        targets.forEach { inst ->
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    val uris = pendingImportUris
                                    if (uris != null) {
                                        scope.launch {
                                            val dest = PluginManager.addonDir(inst)
                                            uris.forEach { uri ->
                                                FileImporter.copyFile(context, uri, dest).onFailure {
                                                    messenger.toastError("导入失败:${it.message}")
                                                }.onSuccess {
                                                    messenger.toast("已导入 ${it.name} 到 ${inst.name}")
                                                }
                                            }
                                        }
                                    }
                                    importTargetPicker = null
                                    pendingImportUris = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${inst.name} (${PluginManager.dirLabel(inst)})",
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Start
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        importTargetPicker = null
                        pendingImportUris = null
                    }) { Text("取消") }
                }
            )
        }

        AnimatedVisibility(
            visible = manageInstance != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {
                manageInstance?.let { inst ->
                    AddonManageScreen(
                        instance = inst,
                        onBack = { manageInstance = null },
                        modifier = modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun InstanceEntryRow(
    inst: ServerInstance,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KazeSpacing.rowItemH)
            .padding(horizontal = KazeSpacing.rowHorizPad)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(KazeCorners.tiny)
                .background(inst.coreType.badgeColor()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                inst.coreType.badgeLetter(),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            )
        }
        Spacer(Modifier.width(KazeSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                inst.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${inst.coreType.name} ${inst.mcVersion} · ${PluginManager.dirLabel(inst)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilterChip(
            selected = false,
            onClick = onClick,
            label = { Text("管理", fontSize = 12.sp) },
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
private fun DownloadTaskRow(task: DownloadTask) {
    val progress = when {
        task.totalBytes > 0 -> (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
        else -> -1f
    }
    val showProgress = task.status == DOWNLOADING || task.status == PAUSED

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KazeSpacing.rowHorizPad, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (task.status) {
                DOWNLOADING -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                COMPLETED -> TaskAccentDot(KazeSuccess)
                FAILED -> TaskAccentDot(KazeError)
                PENDING -> TaskAccentDot(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                PAUSED -> TaskAccentDot(KazeWarning)
                CANCELED -> TaskAccentDot(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
            Spacer(Modifier.width(KazeSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val speedText =
                    if (task.status == DOWNLOADING && task.speedBytesPerSec > 0)
                        " · ${FileFormat.size(task.speedBytesPerSec)}/s" else ""
                Text(
                    buildString {
                        append(task.status.label)
                        if (task.totalBytes > 0) {
                            append(" · ${FileFormat.size(task.downloadedBytes)} / ${FileFormat.size(task.totalBytes)}")
                        }
                        append(speedText)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showProgress) {
                    Spacer(Modifier.height(6.dp))
                    if (progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(KazeCorners.pill),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(KazeCorners.pill),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.width(KazeSpacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.status == DOWNLOADING) {
                    MiniIconButton(Icons.Filled.Pause, "暂停") { DownloadCenter.pause(task.id) }
                    MiniIconButton(
                        Icons.Filled.Close,
                        "取消",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    ) { DownloadCenter.cancel(task.id) }
                } else if (task.status == PAUSED) {
                    MiniIconButton(Icons.Filled.PlayArrow, "继续") { DownloadCenter.resume(task.id) }
                    MiniIconButton(
                        Icons.Filled.Close,
                        "取消",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    ) { DownloadCenter.cancel(task.id) }
                }
                if (task.status in listOf(COMPLETED, FAILED, CANCELED)) {
                    MiniIconButton(
                        Icons.Filled.Delete,
                        "删除记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        DownloadCenter.remove(task.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskAccentDot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(KazeCorners.pill)
            .background(color)
    )
}

@Composable
private fun MiniIconButton(
    icon: ImageVector,
    desc: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint
        )
    ) {
        Icon(icon, desc, Modifier.size(16.dp), tint = tint)
    }
}

private val DownloadStatus.label: String
    get() = when (this) {
        PENDING -> "等待中"
        DOWNLOADING -> "下载中"
        PAUSED -> "已暂停"
        COMPLETED -> "已完成"
        FAILED -> "失败"
        CANCELED -> "已取消"
    }
