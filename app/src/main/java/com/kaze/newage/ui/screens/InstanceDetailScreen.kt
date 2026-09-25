package com.kaze.newage.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.server.BackupManager
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.CheckChip
import com.kaze.newage.ui.components.ExpressiveLoadingIndicator
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EConnectedList
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EMetric
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3EStatusChip
import com.kaze.newage.ui.components.StatusTone
import com.kaze.newage.ui.components.WavyLinearProgress
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.theme.statusPalette
import com.kaze.newage.ui.toLabel
import com.kaze.newage.ui.toTone
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 实例详情 —— 一个服务端的全部控制项，按「运行 / 配置 / 世界 / 附加」四个分区组织。
 *
 * 版式来自 m3e-canvas 生成的 `docs/m3e/prompt-实例详情.md`：
 *   实例名（右上角改名）→ 状态胶囊 + 主行动 → 主标签页 → 分区内容
 *   「运行」= 运行状态卡（运行时长 / 内存上限）+ 快捷入口 + 玩家管理
 *   「配置」= server.properties 可视化编辑
 *   「世界」= 备份（创建 / 导入 / 恢复 / 导出 / 删除）
 *   「附加」= 插件 / 模组入口
 *
 * 与旧版的差别：
 *  - 一屏到底的长滚动改成「固定头部 + 四个标签页」，每页各自滚动
 *  - 状态与启停按钮放在标签页**之外**：启停是这一屏的主行动，而「启动中」是必须马上
 *    能中止的过渡状态（部署 / 装 Java / Forge 安装可能几分钟），藏进「运行」页会让用户
 *    在别的页里无从下手；启动失败同理，失败条也常驻
 *  - 颜色全部走 MaterialTheme.colorScheme，卡片与列表改用 M3E 组件层
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceDetailScreen(
    viewModel: AppViewModel,
    instanceId: String,
    onBack: () -> Unit,
    onOpenAddons: (AddonKind) -> Unit,
    onOpenLogs: () -> Unit,
) {
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val states by viewModel.serverStates.collectAsStateWithLifecycle()
    val instance = instances.firstOrNull { it.id == instanceId }
    if (instance == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val state = states[instanceId] ?: ServerState.Idle
    // 进入详情即把该实例设为当前实例（玩家管理/控制台命令按当前实例下发）
    LaunchedEffect(instanceId) {
        viewModel.selectInstance(instance)
    }
    val tone = state.toTone()
    val palette = statusPalette()
    val stateColor = when (tone) {
        StatusTone.Running -> palette.running
        StatusTone.Busy -> palette.busy
        StatusTone.Idle -> palette.idle
        StatusTone.Error -> palette.error
    }

    // ── 备份区的状态与副作用（屏幕级持有）──
    // 放在这里而不是「世界」页内部：切标签页会让页内组合被销毁，
    // ActivityResult 启动器会跟着反复注销/注册，SAF 回调也可能丢在切页中途。
    var backupRefresh by remember { mutableIntStateOf(0) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    var exportTarget by remember { mutableStateOf<File?>(null) }
    var restoreTarget by remember { mutableStateOf<File?>(null) }
    val backups = remember(instanceId, backupRefresh) { BackupManager.list(instance) }
    val backupScope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext

    // 导出（SAF 创建文档）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val src = exportTarget
        if (uri != null && src != null) {
            backupScope.launch(Dispatchers.IO) {
                backupMsg = try {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        BackupManager.export(src, out)
                    }
                    "已导出：${src.name}"
                } catch (e: Exception) {
                    "导出失败：${e.message}"
                }
                exportTarget = null
            }
        }
    }
    // 导入（SAF 打开文档）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupScope.launch(Dispatchers.IO) {
                backupMsg = try {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.zip"
                    appContext.contentResolver.openInputStream(uri)?.use { ins ->
                        val f = BackupManager.import(instance, ins, name)
                        "已导入：${f.name}"
                    } ?: "导入失败：无法读取文件"
                } catch (e: Exception) {
                    "导入失败：${e.message}"
                }
                backupRefresh++
            }
        }
    }

    // ── 重命名对话框 ──
    var showRenameDialog by remember(instanceId) { mutableStateOf(false) }
    if (showRenameDialog) {
        RenameInstanceDialog(
            instance = instance,
            onDismiss = { showRenameDialog = false },
            onRename = { viewModel.renameInstance(instanceId, it) },
        )
    }

    // ── 标签页 ──
    var tab by rememberSaveable(instanceId) { mutableStateOf(DetailTab.Run) }
    val scrollState = rememberScrollState()
    // 四页高度差别很大，沿用上一页的滚动位置会落在空白处，切页一律回到顶部
    LaunchedEffect(tab) { scrollState.scrollTo(0) }
    // 标签页离开组合时保住页内的 rememberSaveable：server.properties 的未保存修改、
    // 「已保存」标记、玩家名输入都靠它，否则切一次页就被静默回滚
    val tabStateHolder = rememberSaveableStateHolder()

    Column(
        Modifier
            .fillMaxSize()
            // 玩家名/server.properties 的输入框在 adjustNothing 下会被键盘盖住，这里主动避让
            .imePadding()
    ) {
        // ── 顶部：返回 + 实例名（右上角改名）+ 概要 ──
        M3EScreenHeader(
            title = instance.name,
            subtitle = "${instance.coreType.displayName} · MC ${instance.mcVersion.ifBlank { "自定义" }} · Java ${instance.javaMajor} · ${instance.memoryMb} MB",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            trailing = {
                // 改名：只改软件里显示的名字，与服务器 MOTD（server.properties）独立
                IconButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "重命名实例")
                }
            },
        )

        // ── 常驻状态条：状态胶囊 + 主行动（不随标签页切换而消失）──
        InstanceActionRow(
            state = state,
            stateColor = stateColor,
            onStart = { viewModel.startInstance(instance) },
            onStop = { viewModel.stopInstance(instance) },
        )

        // 启动失败必须被看到，也要有出路：常驻失败条 + 直达启动日志
        if (state == ServerState.Error) {
            M3EListItem(
                headline = "服务端启动失败",
                supporting = "启动日志里有退出码与完整报错，先看日志再决定改配置还是换 Java 版本",
                leadingIcon = Icons.Filled.ErrorOutline,
                iconContainer = MaterialTheme.colorScheme.errorContainer,
                shape = M3Shape.listSingle,
                trailing = { TextButton(onClick = onOpenLogs) { Text("查看日志") } },
                modifier = Modifier.padding(
                    horizontal = M3Spacing.screenMargin,
                    vertical = M3Spacing.betweenParts,
                ),
            )
        }

        // 主标签页（M3 标准主标签页：选中项 primary + 3dp 指示条 + outlineVariant 分割线）。
        // 容器透明：本应用的背景由 AppBackground 绘制，用 surface 会盖出一条色带
        PrimaryTabRow(
            selectedTabIndex = tab.ordinal,
            modifier = Modifier.fillMaxWidth(),
            containerColor = Color.Transparent,
        ) {
            DetailTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    text = { Text(entry.label) },
                )
            }
        }

        // ── 分区内容：每页自己滚动，底部留出常驻底栏的高度 ──
        Box(Modifier.weight(1f).fillMaxWidth()) {
            tabStateHolder.SaveableStateProvider(tab.name) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = M3Spacing.screenMargin)
                        .padding(top = M3Spacing.betweenGroups, bottom = M3Spacing.bottomBarSpace),
                    verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenGroups),
                ) {
                    when (tab) {
                        DetailTab.Run -> RunTab(
                            viewModel = viewModel,
                            instance = instance,
                            state = state,
                            stateColor = stateColor,
                            backups = backups,
                            onOpenLogs = onOpenLogs,
                            onOpenWorld = { tab = DetailTab.World },
                        )

                        DetailTab.Config -> PropertiesEditor(
                            dir = instance.dir,
                            instanceName = instance.name,
                            isRunning = state == ServerState.Running,
                            onSave = { props -> ServerProperties.save(instance.dir, props) },
                        )

                        DetailTab.World -> WorldTab(
                            backups = backups,
                            busy = backupBusy,
                            message = backupMsg,
                            onBackup = {
                                if (!backupBusy) {
                                    backupBusy = true
                                    backupScope.launch(Dispatchers.IO) {
                                        backupMsg = try {
                                            // 运行中备份：先让服务端把世界数据完整落盘（MC 标准做法），
                                            // 否则直接拷 region 文件可能备出损坏世界
                                            val wasRunning = state == ServerState.Running
                                            if (wasRunning) {
                                                viewModel.serverManager.sendCommand(instance, "save-off")
                                                viewModel.serverManager.sendCommand(instance, "save-all flush")
                                                delay(1500)
                                            }
                                            try {
                                                val f = BackupManager.backup(instance)
                                                "已备份：${f.name}"
                                            } finally {
                                                if (wasRunning) {
                                                    viewModel.serverManager.sendCommand(instance, "save-on")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            "备份失败：${e.message}"
                                        }
                                        backupRefresh++
                                        backupBusy = false
                                    }
                                }
                            },
                            onImport = {
                                importLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream", "*/*")
                                )
                            },
                            onRestoreRequest = { f ->
                                // 任何"目录可能正被占用"的状态都不允许恢复：
                                // 补上 FirstRun / AcceptingEula —— 首启探测期间服务端进程是活着的
                                // （正在生成世界、稍后会被改写 eula 再重启），此时把实例目录整体
                                // 换掉，运行中的进程会继续往已被改名的旧目录写，恢复结果不可预期。
                                if (state == ServerState.Running ||
                                    state == ServerState.Starting ||
                                    state == ServerState.Stopping ||
                                    state == ServerState.FirstRun ||
                                    state == ServerState.AcceptingEula
                                ) {
                                    backupMsg = "请先停止服务端再恢复备份"
                                } else {
                                    restoreTarget = f
                                }
                            },
                            onExport = { f ->
                                exportTarget = f
                                exportLauncher.launch(f.name)
                            },
                            onDelete = { f ->
                                BackupManager.delete(f)
                                backupRefresh++
                            },
                        )

                        DetailTab.Addons -> AddonsTab(
                            instance = instance,
                            onOpenAddons = onOpenAddons,
                            onOpenLogs = onOpenLogs,
                        )
                    }
                }
            }
        }
    }

    // 恢复确认弹窗：恢复会用备份整体替换当前实例目录（模态，不属于任何一页）
    restoreTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("恢复「${target.name.removeSuffix(".zip")}」？") },
            text = {
                Text("当前实例目录（世界存档、配置、插件/模组）将被备份内容完整覆盖。此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val f = target
                        restoreTarget = null
                        backupScope.launch(Dispatchers.IO) {
                            backupMsg = try {
                                BackupManager.restore(instance, f)
                                "已恢复：${f.name}"
                            } catch (e: Exception) {
                                "恢复失败：${e.message}"
                            }
                        }
                    }
                ) { Text("恢复", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 实例详情的四个分区（草图：运行 / 配置 / 世界 / 附加） */
private enum class DetailTab(val label: String) {
    Run("运行"),
    Config("配置"),
    World("世界"),
    Addons("附加"),
}

/**
 * 常驻状态条：状态胶囊 + 主行动按钮。
 *
 * 刻意放在标签页之外：启停是这一屏的主行动；启动中（部署 / 装 Java / Forge 安装可能几分钟）
 * 是必须马上能中止的过渡状态，藏进「运行」页会让用户在其它页里找不到中止入口。
 */
@Composable
private fun InstanceActionRow(
    state: ServerState,
    stateColor: Color,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val busy = state.isBusy()
    val running = state == ServerState.Running
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = M3Spacing.screenMargin, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        M3EStatusChip(text = state.shortLabel(), color = stateColor)
        Spacer(Modifier.weight(1f))
        if (busy) {
            // 过渡中给一个「生命体征」：与主页同一个加载指示器，加速表示正在忙
            ExpressiveLoadingIndicator(size = 28.dp, color = stateColor, speed = 1.8f)
        }
        Button(
            onClick = {
                // 运行中或启动中（部署/装 Java/下载核心/Forge 安装）都走停止。
                // 旧版 busy 时按钮被禁用，用户在长达几分钟的启动过程里没有任何中止手段。
                if (running || busy) onStop() else onStart()
            },
            modifier = Modifier.height(48.dp),
        ) {
            Icon(
                if (running || busy) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    running -> "停止服务端"
                    busy -> "取消启动"
                    else -> "启动服务端"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** 「运行」页：运行状态卡 + 快捷入口 + 玩家管理 */
@Composable
private fun RunTab(
    viewModel: AppViewModel,
    instance: ServerInstance,
    state: ServerState,
    stateColor: Color,
    backups: List<File>,
    onOpenLogs: () -> Unit,
    onOpenWorld: () -> Unit,
) {
    val uptime by viewModel.uptimeSec.collectAsStateWithLifecycle()
    val onlinePlayers by viewModel.onlinePlayers.collectAsStateWithLifecycle()
    val running = state == ServerState.Running
    val busy = state.isBusy()
    // 端口取 server.properties 里的真实值；切回本页时会重新读一次（配置页保存过就会刷新）
    val port = remember(instance.id) { ServerProperties.load(instance.dir)["server-port"] ?: "—" }
    val latestBackup = backups.firstOrNull()

    M3ECard(
        variant = M3ECardVariant.Elevated,
        title = "运行状态",
        supporting = buildString {
            append(instance.coreType.displayName)
            append(" · MC ")
            append(instance.mcVersion.ifBlank { "自定义" })
            append(" · Java ")
            append(instance.javaMajor)
            append(" · 端口 ")
            append(port)
            if (running) {
                append(" · 在线 ")
                append(onlinePlayers.size)
                append(" 人")
            }
        },
        trailing = { M3EStatusChip(text = state.shortLabel(), color = stateColor) },
        content = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                M3EMetric(
                    value = if (running) formatClock(uptime) else "未运行",
                    label = "运行时长",
                    modifier = Modifier.weight(1.25f),
                )
                M3EMetric(
                    value = "${instance.memoryMb} MB",
                    label = "内存上限",
                    modifier = Modifier.weight(1f),
                )
            }
            // 过渡状态用不定态波浪进度条：后端只暴露 -Xmx 上限，没有 JVM 实时占用读数，
            // 画一个百分比会是在编数据；「正在进行」这一点是真实的
            if (busy) {
                Text(
                    "正在处理：${state.toLabel()}，可随时取消",
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor,
                    modifier = Modifier.padding(top = 12.dp),
                )
                WavyLinearProgress(
                    progress = null,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        },
    )

    // 快捷入口：草图里的「自动备份 / 查看启动日志」两条。
    // 备份入口显示真实的最近备份与份数（后端没有自动备份，标签如实写「备份与恢复」），点了切到「世界」页
    M3EConnectedList(count = 2) { index, shape ->
        if (index == 0) {
            M3EListItem(
                headline = "备份与恢复",
                supporting = if (latestBackup == null) {
                    "还没有备份 · 世界数据珍贵，建议定期备份"
                } else {
                    "最近 ${backupStamp(latestBackup)} · 共 ${backups.size} 份"
                },
                leadingIcon = Icons.Filled.History,
                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                onClick = onOpenWorld,
                shape = shape,
            )
        } else {
            M3EListItem(
                headline = "查看启动日志",
                supporting = "每次启动的完整输出与退出码（含崩溃报告）",
                leadingIcon = Icons.AutoMirrored.Filled.ReceiptLong,
                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                onClick = onOpenLogs,
                shape = shape,
            )
        }
    }

    PlayerManageCard(
        viewModel = viewModel,
        instanceId = instance.id,
        running = running,
    )
}

/** 「世界」页：备份的创建 / 导入 / 恢复 / 导出 / 删除 */
@Composable
private fun WorldTab(
    backups: List<File>,
    busy: Boolean,
    message: String?,
    onBackup: () -> Unit,
    onImport: () -> Unit,
    onRestoreRequest: (File) -> Unit,
    onExport: (File) -> Unit,
    onDelete: (File) -> Unit,
) {
    M3ECard(
        variant = M3ECardVariant.Filled,
        title = "备份",
        supporting = "世界 + 配置全量打包；运行中会先让服务端落盘（save-off → save-all flush）再打包",
        trailing = {
            Button(onClick = onBackup, enabled = !busy, modifier = Modifier.height(40.dp)) {
                Text(if (busy) "备份中…" else "立即备份")
            }
        },
        content = {
            if (backups.isEmpty()) {
                Text(
                    "还没有备份。世界数据珍贵，建议开服前/后定期备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("导入备份") }

            if (backups.isNotEmpty()) {
                M3EConnectedList(count = backups.size, modifier = Modifier.padding(top = 12.dp)) { index, shape ->
                    val f = backups[index]
                    M3EListItem(
                        headline = f.name.removeSuffix(".zip"),
                        supporting = "${f.length() / 1024 / 1024} MB · ${backupStamp(f)}",
                        leadingIcon = Icons.Filled.Archive,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onRestoreRequest(f) }) { Text("恢复") }
                                TextButton(onClick = { onExport(f) }) { Text("导出") }
                                IconButton(onClick = { onDelete(f) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除备份",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("已")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
    )
}

/** 「附加」页：插件 / 模组入口（支持与否直接写在行里，不点进去也知道） */
@Composable
private fun AddonsTab(
    instance: ServerInstance,
    onOpenAddons: (AddonKind) -> Unit,
    onOpenLogs: () -> Unit,
) {
    val pluginOk = AddonManager.supports(instance, AddonKind.PLUGIN)
    val modOk = AddonManager.supports(instance, AddonKind.MOD)
    // 不支持时行内不可点（和旧版的 enabled=false 一致），原因写在辅助文本里
    val pluginClick: (() -> Unit)? = if (pluginOk) ({ onOpenAddons(AddonKind.PLUGIN) }) else null
    val modClick: (() -> Unit)? = if (modOk) ({ onOpenAddons(AddonKind.MOD) }) else null

    M3EConnectedList(count = 3) { index, shape ->
        when (index) {
            0 -> M3EListItem(
                headline = "插件",
                supporting = if (pluginOk) "下载/启停插件" else "需 Paper 类核心",
                leadingIcon = Icons.Filled.Extension,
                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                onClick = pluginClick,
                shape = shape,
            )

            1 -> M3EListItem(
                headline = "模组",
                supporting = if (modOk) "下载/启停模组" else "需 Fabric/Forge 类核心",
                leadingIcon = Icons.Filled.ViewInAr,
                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                onClick = modClick,
                shape = shape,
            )

            else -> M3EListItem(
                headline = "日志",
                supporting = "崩溃报告 · latest.log",
                leadingIcon = Icons.AutoMirrored.Filled.ReceiptLong,
                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                onClick = onOpenLogs,
                shape = shape,
            )
        }
    }

    M3ECard(
        variant = M3ECardVariant.Filled,
        title = "改完要重启",
        supporting = "插件放在 plugins/、模组放在 mods/，停用是给文件加 .disabled 后缀；" +
            "增删或改启用状态后都要重启服务端才会生效。",
    )
}

/** 玩家管理：在线列表（list 命令解析 + 进出事件跟踪）+ OP/白名单/踢出快捷操作 */
@Composable
private fun PlayerManageCard(
    viewModel: AppViewModel,
    instanceId: String,
    running: Boolean,
) {
    val players by viewModel.onlinePlayers.collectAsStateWithLifecycle()
    var name by remember(instanceId) { mutableStateOf("") }

    fun doCmd(cmd: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModel.sendCommand("$cmd $n")
        name = ""
    }

    M3ECard(
        variant = M3ECardVariant.Filled,
        title = "玩家管理",
        supporting = if (running) null
        else "服务端运行后可管理玩家：查看在线列表、设置 OP、白名单、踢出。",
        trailing = {
            if (running) {
                IconButton(onClick = { viewModel.refreshPlayers() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新在线列表")
                }
            }
        },
        content = {
            if (running) {
                Text(
                    if (players.isEmpty()) "在线玩家：暂无（点右上角刷新）" else "在线玩家：${players.joinToString("、")}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("玩家名") },
                    placeholder = { Text("如 Steve") },
                    singleLine = true,
                    shape = M3Shape.large,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { doCmd("op") }) { Text("设为 OP") }
                    OutlinedButton(onClick = { doCmd("deop") }) { Text("取消 OP") }
                    OutlinedButton(onClick = { doCmd("kick") }) { Text("踢出") }
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { doCmd("whitelist add") }) { Text("白名单 +") }
                    OutlinedButton(onClick = { doCmd("whitelist remove") }) { Text("白名单 −") }
                }
            }
        },
    )
}

/** 「配置」页：server.properties 可视化编辑器（三个分组卡 + 保存） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropertiesEditor(
    dir: File,
    instanceName: String,
    isRunning: Boolean,
    onSave: (Map<String, String>) -> Unit,
) {
    // rememberSaveable：普通 remember 不进 SaveableStateHolder，导航到
    // 插件/日志页再返回时会重新 ServerProperties.load(dir)，
    // 用户没保存的修改与「已保存」标记会被静默回滚
    var props by rememberSaveable(dir, stateSaver = propsSaver) {
        mutableStateOf(ServerProperties.load(dir))
    }
    var saved by rememberSaveable(dir) { mutableStateOf(false) }
    val p = props

    fun value(key: String, default: String): String = p[key] ?: default
    fun set(key: String, v: String) {
        val next = LinkedHashMap(p)
        next[key] = v
        props = next
        saved = false
    }

    val motd = value("motd", instanceName)
    val port = value("server-port", "25565")
    val maxPlayers = value("max-players", "20").toIntOrNull() ?: 20
    val gamemode = value("gamemode", "survival")
    val difficulty = value("difficulty", "easy")
    val pvp = value("pvp", "true").toBoolean()
    val onlineMode = value("online-mode", "true").toBoolean()
    val whiteList = value("white-list", "false").toBoolean()
    val allowFlight = value("allow-flight", "false").toBoolean()
    val commandBlock = value("enable-command-block", "false").toBoolean()
    val hardcore = value("hardcore", "false").toBoolean()
    val viewDistance = value("view-distance", "10").toIntOrNull() ?: 10
    val simDistance = value("simulation-distance", "10").toIntOrNull() ?: 10
    val spawnProtection = value("spawn-protection", "16").toIntOrNull() ?: 16
    val pauseSeconds = value("pause-when-empty-seconds", "-1").toIntOrNull() ?: -1

    // ── 服务器 ──
    M3ECard(
        variant = M3ECardVariant.Filled,
        title = "服务器",
        supporting = "写回实例目录里的 server.properties（未知键原样保留）",
        content = {
            OutlinedTextField(
                value = motd,
                onValueChange = { set("motd", it) },
                label = { Text("服务器标题（MOTD）") },
                singleLine = true,
                shape = M3Shape.large,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> if (v.all { it.isDigit() }) set("server-port", v) },
                    label = { Text("端口") },
                    singleLine = true,
                    shape = M3Shape.large,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = maxPlayers.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { set("max-players", it.toString()) } },
                    label = { Text("最大人数") },
                    singleLine = true,
                    shape = M3Shape.large,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )

    // ── 玩法 ──
    M3ECard(
        variant = M3ECardVariant.Filled,
        title = "玩法",
        content = {
            Text("游戏模式", style = MaterialTheme.typography.labelLarge)
            // FlowRow 而不是 Row：普通 Row 里靠后的 chip 只能用剩余宽度测量，
            // 系统字体放大后会被压到 0 宽或裁字（同文件「空服自动暂停」已用 FlowRow）
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("survival" to "生存", "creative" to "创造", "adventure" to "冒险", "spectator" to "旁观").forEach { (id, label) ->
                    CheckChip(selected = gamemode == id, label = label, onClick = { set("gamemode", id) })
                }
            }

            Text(
                "难度",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("peaceful" to "和平", "easy" to "简单", "normal" to "普通", "hard" to "困难").forEach { (id, label) ->
                    CheckChip(selected = difficulty == id, label = label, onClick = { set("difficulty", id) })
                }
            }

            Text(
                "规则开关",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            SettingSwitchRow("允许 PVP", pvp) { set("pvp", it.toString()) }
            SettingSwitchRow("正版验证（online-mode）", onlineMode) { set("online-mode", it.toString()) }
            SettingSwitchRow("白名单", whiteList) { set("white-list", it.toString()) }
            SettingSwitchRow("允许飞行", allowFlight) { set("allow-flight", it.toString()) }
            SettingSwitchRow("命令方块", commandBlock) { set("enable-command-block", it.toString()) }
            SettingSwitchRow("极限模式（hardcore）", hardcore) { set("hardcore", it.toString()) }
        },
    )

    // ── 性能与暂停 ──
    M3ECard(
        variant = M3ECardVariant.Filled,
        title = "性能与暂停",
        content = {
            SettingSlider(
                label = "视距：$viewDistance 区块",
                value = viewDistance,
                range = 3f..32f,
                onChange = { set("view-distance", it.toString()) },
            )
            SettingSlider(
                label = "模拟距离：$simDistance 区块",
                value = simDistance,
                range = 3f..32f,
                onChange = { set("simulation-distance", it.toString()) },
            )
            SettingSlider(
                label = "出生点保护：$spawnProtection 格",
                value = spawnProtection,
                range = 0f..64f,
                onChange = { set("spawn-protection", it.toString()) },
            )

            // 空服自动暂停（MC 1.21.2+；-1 = 关闭）
            Text(
                "空服自动暂停",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "没有玩家在线时自动暂停服务器以节省资源（属性 pause-when-empty-seconds）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(-1 to "关闭", 30 to "30 秒", 60 to "60 秒", 120 to "2 分钟", 300 to "5 分钟").forEach { (sec, label) ->
                    CheckChip(
                        selected = pauseSeconds == sec,
                        label = label,
                        onClick = { set("pause-when-empty-seconds", sec.toString()) },
                    )
                }
            }
        },
    )

    // ── 保存 ──
    Button(
        onClick = {
            onSave(p)
            saved = true
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (saved) "已保存" else "保存配置", style = MaterialTheme.typography.labelLarge)
    }
    if (saved && isRunning) {
        Text(
            "配置已保存；运行中的服务器将在重启后生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 开关行：标签在左、开关靠右（草图「开关」样式）。标签给 weight，长标签不会把开关挤出屏幕 */
@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 滑块行：标签带当前值，下面是 M3 标准滑块 */
@Composable
private fun SettingSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range,
        )
    }
}

/** 重命名对话框：只改软件内显示名，与 MOTD 独立 */
@Composable
private fun RenameInstanceDialog(
    instance: ServerInstance,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var newName by remember(instance.id) { mutableStateOf(instance.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名实例") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("实例名称") },
                    singleLine = true,
                    shape = M3Shape.large,
                )
                Text(
                    "仅修改软件内显示名；服务器 MOTD 独立保存在 server.properties 中，不受影响。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isNotBlank() && newName.trim() != instance.name) {
                        onRename(newName)
                    }
                    onDismiss()
                },
                enabled = newName.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 秒 → 「02:14:37」：比「2 小时 14 分」更紧凑，适合放进指标块 */
private fun formatClock(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}

/**
 * 状态胶囊里的短标签。
 *
 * 完整说法（「首次启动 · 生成 eula.txt」）太长，会把常驻状态条上的主行动按钮挤到换行；
 * 完整说法在运行状态卡的「正在处理…」一行里给全。
 */
private fun ServerState.shortLabel(): String = when (this) {
    ServerState.FirstRun -> "首次启动"
    ServerState.AcceptingEula -> "重启中"
    else -> toLabel()
}

/** 备份时间：今天/昨天用相对说法（草图「最近 今天 12:00」），更早给「MM-dd HH:mm」 */
private fun backupStamp(f: File): String {
    val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(f.lastModified()))
    val then = Calendar.getInstance().apply { timeInMillis = f.lastModified() }
    val today = Calendar.getInstance()
    val sameDay = today.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        today.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "今天 $clock"
    today.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = today.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        today.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return "昨天 $clock"
    return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))
}

/**
 * `Map<String, String>` 的 Saver（详情页 server.properties 的编辑态）。
 *
 * `rememberSaveable` 只接受能进 Bundle 的类型，Map 不在其中，所以摊平成 String 列表存取。
 * 用它是因为普通 `remember` 不进 SaveableStateHolder：切标签页或导航到插件/日志页再返回时
 * 会重新 `ServerProperties.load(dir)`，用户未保存的修改与「已保存」标记会被静默回滚。
 */
private val propsSaver: Saver<LinkedHashMap<String, String>, ArrayList<String>> = Saver(
    save = { map: LinkedHashMap<String, String> ->
        ArrayList<String>(map.size * 2).apply {
            map.forEach { (k, v) ->
                add(k)
                add(v)
            }
        }
    },
    restore = { flat: ArrayList<String> ->
        LinkedHashMap<String, String>().apply {
            var i = 0
            while (i + 1 < flat.size) {
                put(flat[i], flat[i + 1])
                i += 2
            }
        }
    },
)
