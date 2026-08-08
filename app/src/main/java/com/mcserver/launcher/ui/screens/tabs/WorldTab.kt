package com.mcserver.launcher.ui.screens.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.ui.components.LocalUiMessenger
import com.mcserver.launcher.ui.components.pressSource
import com.mcserver.launcher.core.server.BackupManager
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.ui.theme.KazeCorners
import com.mcserver.launcher.ui.theme.KazeSpacing
import com.mcserver.launcher.util.FileFormat
import kotlinx.coroutines.launch
import java.io.File

/**
 * 世界管理 Tab:导入本地世界、立即备份、自动备份间隔、备份列表、当前世界列表。
 */
@Composable
fun WorldTab(instance: ServerInstance) {
    val context = LocalContext.current
    val messenger = LocalUiMessenger.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var worlds by remember { mutableStateOf<List<File>>(emptyList()) }
    var backups by remember { mutableStateOf<List<File>>(emptyList()) }
    var autoBackupHours by remember { mutableStateOf(instance.config.autoBackupHours) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val dest = File(instance.dir(InstanceStore.instancesDir), "world_import_tmp")
                if (dest.exists()) dest.deleteRecursively()
                com.mcserver.launcher.util.FileImporter.copyTree(context, uri, dest)
                    .onSuccess { count ->
                        if (count == 0) {
                            dest.deleteRecursively()
                            messenger.toast("所选目录为空")
                        } else {
                            val name = "world_" + System.currentTimeMillis().toString().takeLast(8)
                            val target = File(instance.dir(InstanceStore.instancesDir), name)
                            dest.renameTo(target)
                            messenger.toastSuccess("已导入世界 $name(可在配置页设置 level-name)")
                            worlds = listWorlds(instance)
                        }
                    }
                    .onFailure { err -> messenger.toastError("导入失败:${err.message}") }
            }
        }
    }

    LaunchedEffect(instance.id) {
        worlds = listWorlds(instance)
        backups = BackupManager.backupsFor(instance.id)
    }

    Column(Modifier.fillMaxSize().padding(KazeSpacing.lg)) {
        Text("世界管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("导入本地世界目录(优先本地,不耗流量);导入后可在配置页将 level-name 设为该目录名",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { importLauncher.launch(null) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text("导入世界")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val f = BackupManager.backupWorld(instance)
                    backups = BackupManager.backupsFor(instance.id)
                    messenger.toast(if (f != null) "备份完成:${f.name}" else "无世界数据,未备份")
                }
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Backup, null, Modifier.size(18.dp))
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text("立即备份")
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("世界备份", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row {
            listOf(0 to "关闭", 1 to "1h", 6 to "6h", 12 to "12h", 24 to "每天").forEach { (h, label) ->
                FilterChip(
                    selected = autoBackupHours == h,
                    onClick = {
                        autoBackupHours = h
                        InstanceStore.update(instance.copy(config = instance.config.copy(autoBackupHours = h)))
                        messenger.toast("已${if (h == 0) "关闭" else "设置每 $h 小时自动备份"},重启服务器后生效")
                    },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        Text("自动备份(运行期间) · 停止时也会自动备份,保留最近 10 份",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (backups.isEmpty()) {
            Text("暂无备份", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn {
                items(backups, key = { it.name }) { backup ->
                    Surface(
                        shape = KazeCorners.small,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(backup.name, style = MaterialTheme.typography.labelMedium)
                                Text(FileFormat.size(backup.length()), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val (pr, sr) = pressSource()
                            IconButton(onClick = {
                                scope.launch {
                                    val ok = BackupManager.restoreBackup(instance, backup)
                                    worlds = listWorlds(instance)
                                    messenger.toast(if (ok) "已还原,旧世界保留为 *_old_*" else "还原失败")
                                }
                            }, interactionSource = sr, modifier = pr) {
                                Icon(Icons.Filled.Restore, "还原", Modifier.size(18.dp))
                            }
                            val (pd, sd) = pressSource()
                            IconButton(onClick = {
                                scope.launch {
                                    BackupManager.deleteBackup(instance.id, backup.name)
                                    backups = BackupManager.backupsFor(instance.id)
                                }
                            }, interactionSource = sd, modifier = pd) {
                                Icon(Icons.Filled.Delete, "删除备份", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("当前世界", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (worlds.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("还没有世界,点上方导入", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(worlds, key = { it.name }) { world ->
                    Surface(
                        shape = KazeCorners.medium,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(world.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    if (world.name == instance.config.levelName) {
                                        Spacer(Modifier.padding(horizontal = 3.dp))
                                        Text("主世界", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Text(FileFormat.size(FileFormat.dirSize(world)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val (ps, ss) = pressSource()
                            IconButton(onClick = {
                                val newCfg = instance.config.copy(levelName = world.name)
                                InstanceStore.update(instance.copy(config = newCfg))
                                messenger.toastSuccess("已设「${world.name}」为主世界(重启生效)")
                            }, interactionSource = ss, modifier = ps) {
                                Icon(Icons.Filled.Star, "设为主世界", Modifier.size(18.dp),
                                    tint = if (world.name == instance.config.levelName) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val (pd, sd) = pressSource()
                            IconButton(onClick = {
                                scope.launch {
                                    world.deleteRecursively()
                                    worlds = listWorlds(instance)
                                }
                            }, interactionSource = sd, modifier = pd) {
                                Icon(Icons.Filled.Delete, "删除世界", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 列出实例中的世界目录(含 level.dat 或 data/ 的目录) */
private fun listWorlds(instance: ServerInstance): List<File> =
    instance.dir(InstanceStore.instancesDir).listFiles()
        ?.filter { it.isDirectory && it.name != "plugins" && it.name != "mods" && it.name != "world_import_tmp" &&
            (File(it, "level.dat").exists() || File(it, "data").exists()) }
        ?.sortedBy { it.name } ?: emptyList()
