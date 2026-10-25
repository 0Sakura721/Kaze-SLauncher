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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.server.BackupManager
import com.kaze.newage.core.server.WhitelistFile
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.LanIp
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.ForceStopConfirmDialog
import com.kaze.newage.core.server.MemoryLimits
import com.kaze.newage.ui.components.MemorySliderRow
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
import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface

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
    // 配置保存的版本号：状态卡里的端口要跟着它重读（见 RunTab），否则改完端口仍显示旧值
    var propsRevision by remember { mutableIntStateOf(0) }
    // 删除备份：不可逆，且紧邻「恢复 / 导出」，必须二次确认（同页其它破坏性操作都有）
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    // 传入全部实例：旧版平铺备份只写显示名，遇到同名实例时无法区分归属（见 BackupManager.isLegacyBackupOf）
    val backups = remember(instanceId, backupRefresh, instances) { BackupManager.list(instance, instances) }
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
            // 导入也要占住 backupBusy：导入期间实例目录里会多出一个 zip，
            // 而"删除全部备份/立即备份"都在同一目录上操作
            if (backupBusy) return@rememberLauncherForActivityResult
            backupBusy = true
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
                backupBusy = false
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

    // 四个分区（运行 / 配置 / 世界 / 附加）同在一页，共用一个滚动位置。
    // 原来这里有 tab 状态、切页回顶的 LaunchedEffect 和 per-tab 的 SaveableStateProvider；
    // 没有标签栏之后都不需要了 —— 分区一直在组合里，页内的 rememberSaveable 自然保住。
    val scrollState = rememberScrollState()

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
        // 「强制停止」必须先弹确认（真机要求）；框开着时服务器自己停稳 → 框自动收起。
        var askForceStop by remember { mutableStateOf(false) }
        InstanceActionRow(
            state = state,
            stateColor = stateColor,
            onStart = { viewModel.startInstance(instance) },
            onStop = {
                // 停止中再点一次 = 请求强制停止 → 先问一句；其余情况是优雅停止
                if (state == ServerState.Stopping) askForceStop = true
                else viewModel.stopInstance(instance)
            },
            onRestart = { viewModel.restartInstance(instance) },
        )
        ForceStopConfirmDialog(
            visible = askForceStop,
            instanceName = instance.name,
            stillStopping = state == ServerState.Stopping,
            onConfirm = {
                askForceStop = false
                viewModel.forceStopInstance(instance)
            },
            onDismiss = { askForceStop = false },
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

        // ── 分区内容：四块按顺序铺在同一页 ──
        // 原来上面有一条 运行 / 配置 / 世界 / 附加 的主标签栏，真机反馈要求去掉。
        // 仍然是一个 Column + verticalScroll。本页是不显示底栏的子路由
        // （AppRoot 只在四个主 Tab 显示底栏），底部只留导航栏避让 + 一点缓冲，
        // 预留 bottomBarSpace(96dp) 的话最长页的末尾会多出近一屏的死滚动行程。
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = M3Spacing.screenMargin)
                        .padding(top = M3Spacing.betweenGroups, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenGroups),
                ) {
                    run {
                        // 备份动作提到这里：「运行」分区的快捷入口与「世界」分区的按钮要走同一条路径，
                        // 否则两处的行为会各自漂移
                        val doBackup: () -> Unit = {
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
                        }
                        RunTab(
                            viewModel = viewModel,
                            instance = instance,
                            state = state,
                            stateColor = stateColor,
                            backups = backups,
                            propsRevision = propsRevision,
                            onBackup = doBackup,
                            onOpenLogs = onOpenLogs,
                        )

                        PropertiesEditor(
                            dir = instance.dir,
                            instanceName = instance.name,
                            isRunning = state == ServerState.Running,
                            onSave = { props ->
                                ServerProperties.save(instance.dir, props)
                                // 让状态卡的端口重新读一次文件（remember 的 key 变了才会重算）
                                propsRevision++
                            },
                        )

                        AdvancedSettingsCard(
                            viewModel = viewModel,
                            instance = instance,
                            isRunning = state == ServerState.Running,
                        )

                        WorldTab(
                            backups = backups,
                            busy = backupBusy,
                            message = backupMsg,
                            autoBackup = instance.autoBackup,
                            onAutoBackup = { viewModel.setInstanceAutoBackup(instance, it) },
                            onBackup = doBackup,
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
                                deleteTarget = f
                                backupRefresh++
                            },
                        )

                        AddonsTab(
                            instance = instance,
                            onOpenAddons = onOpenAddons,
                            onOpenLogs = onOpenLogs,
                        )
                    }
                }
        }
    }

    // 恢复确认弹窗：恢复会用备份整体替换当前实例目录（模态，不属于任何一页）
    // 删除确认弹窗：不可逆，明确写清后果
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${target.name.removeSuffix(".zip")}」？") },
            text = { Text("该备份文件将被永久删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val f = target
                        deleteTarget = null
                        BackupManager.delete(f)
                        backupMsg = "已删除备份：${f.name.removeSuffix(".zip")}"
                        backupRefresh++
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

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
                        // 恢复期间必须占住 backupBusy：恢复是把实例目录整体换掉
                        //（解压到 restore_tmp_ → 旧目录改名 → 新目录换入），若同时还能点
                        //「立即备份 / 导入 / 删除备份」，就会一边换目录一边打包/删文件，
                        // 轻则备份里混进半截 tmp 内容，重则两边互相删对方的中间目录。
                        if (backupBusy) {
                            backupMsg = "有其它备份任务正在进行，请稍后再恢复"
                            return@TextButton
                        }
                        backupBusy = true
                        backupScope.launch(Dispatchers.IO) {
                            backupMsg = try {
                                BackupManager.restore(instance, f)
                                "已恢复：${f.name}"
                            } catch (e: Exception) {
                                "恢复失败：${e.message}"
                            }
                            backupRefresh++
                            backupBusy = false
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
