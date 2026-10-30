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
import com.kaze.newage.ui.components.M3ESegmentedRow
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
 * 常驻状态条：状态胶囊 + 主行动按钮。
 *
 * 刻意放在标签页之外：启停是这一屏的主行动；启动中（部署 / 装 Java / Forge 安装可能几分钟）
 * 是必须马上能中止的过渡状态，藏进「运行」页会让用户在其它页里找不到中止入口。
 */
@Composable
internal fun InstanceActionRow(
    state: ServerState,
    stateColor: Color,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
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
        if (running) {
            // 一键重启：原来要「停止 → 等世界存完 → 再点启动」三步。
            // 改配置/装插件后最常用的动作，降到一步（走 stop→等停稳→start 的既有链路）
            IconButton(onClick = onRestart, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = "重启服务端",
                    modifier = Modifier.size(26.dp),
                )
            }
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
                    // 停止中再点一次 = 强制停止（正常停止会一直等服务器存完世界）
                    state == ServerState.Stopping -> "强制停止"
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
internal fun RunTab(
    viewModel: AppViewModel,
    instance: ServerInstance,
    state: ServerState,
    stateColor: Color,
    backups: List<File>,
    propsRevision: Int,
    onBackup: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val uptime by viewModel.uptimeSec.collectAsStateWithLifecycle()
    val onlinePlayers by viewModel.onlinePlayers.collectAsStateWithLifecycle()
    val running = state == ServerState.Running
    val busy = state.isBusy()
    // 端口取 server.properties 里的真实值。
    // key 必须带上 propsRevision：只用 `remember(instance.id)` 的话，同一个实例内
    // 组合不会重建，用户在「服务器」分区把端口改掉并保存后这里仍显示旧端口 ——
    // 状态卡说 25565、配置卡说 25570，用户不知道该信哪个。
    val port = remember(instance.id, propsRevision) {
        ServerProperties.load(instance.dir)["server-port"] ?: "—"
    }
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
        // 状态胶囊不再重复：上方常驻状态条已经显示同一个值，同一屏两枚胶囊互相抢注意力
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
            // ── 连接地址：一键复制 ──
            // 手机开服最高频的动作就是把地址填进 MC 客户端 / 发给同 Wi-Fi 的朋友；
            // 让用户自己拼 127.0.0.1:端口、再手动去系统设置里抄局域网 IP，门槛太高。
            // 端口读不到（server.properties 还没生成）就不渲染这一段。
            if (port != "—") {
                val clipboard = LocalClipboardManager.current
                val ctx = LocalContext.current
                fun copyAddress(label: String, addr: String) {
                    clipboard.setText(AnnotatedString(addr))
                    android.widget.Toast.makeText(
                        ctx,
                        "已复制${label}地址 $addr",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                val lanIp = remember(port) { LanIp.lanIpv4() }
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(
                        "连接地址（MC 客户端「多人游戏 → 直接连接」粘贴）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AddressCopyRow("本机", "127.0.0.1:$port") { copyAddress("本机", "127.0.0.1:$port") }
                    if (lanIp != null) {
                        AddressCopyRow("局域网", "$lanIp:$port") { copyAddress("局域网", "$lanIp:$port") }
                    }
                }
            }
            // 建好之后还能改内存（真机需求："创建完实例，还是可以像创建时那样编辑内存分配"）。
            // 内存是启动参数，运行中改只会落盘，AppViewModel 会往控制台写"重启后生效"。
            var showMemoryEditor by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showMemoryEditor = true },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("修改内存分配", style = MaterialTheme.typography.labelMedium)
            }
            EditMemoryDialog(
                visible = showMemoryEditor,
                instanceName = instance.name,
                currentMb = instance.memoryMb,
                isRunning = running,
                onConfirm = { mb ->
                    showMemoryEditor = false
                    viewModel.setInstanceMemory(instance, mb)
                },
                onDismiss = { showMemoryEditor = false },
            )
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
    // 这一页的四个分区现在都在同一个滚动容器里（没有标签栏了），所以"切到世界分区"这个旧语义
    // 已经不存在 —— 原来这里传的是空的 onOpenWorld，点了完全没反应（死入口）。
    // 改为直接执行备份：这正是入口标题承诺的动作，也和「世界」分区的按钮走同一条路径。
    M3EConnectedList(count = 2) { index, shape ->
        if (index == 0) {
            M3EListItem(
                headline = "立即备份",
                supporting = if (latestBackup == null) {
                    "还没有备份 · 世界数据珍贵，建议现在就备一份"
                } else {
                    "最近 ${backupStamp(latestBackup)} · 共 ${backups.size} 份 · 点一下再备一份"
                },
                leadingIcon = Icons.Filled.History,
                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                onClick = onBackup,
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
        instance = instance,
        running = running,
    )
}

/** 「世界」页：备份的创建 / 导入 / 恢复 / 导出 / 删除 */
@Composable
internal fun WorldTab(
    backups: List<File>,
    busy: Boolean,
    message: String?,
    autoBackup: Boolean,
    onAutoBackup: (Boolean) -> Unit,
    onBackup: () -> Unit,
    onImport: () -> Unit,
    onRestoreRequest: (File) -> Unit,
    onExport: (File) -> Unit,
    onDelete: (File) -> Unit,
) {
    M3ECard(
        variant = M3ECardVariant.Filled,
        title = "备份",
        supporting = "世界 + 配置全量打包；运行中会先让服务端落盘再打包",
        trailing = {
            Button(onClick = onBackup, enabled = !busy, modifier = Modifier.height(40.dp)) {
                Text(if (busy) "备份中…" else "立即备份")
            }
        },
        content = {
            // 停服自动备份：进程退出收尾时自动打包（世界在优雅停止时已保存完，
            // 那是比任何定时器都安全的时机）。auto_ 前缀与手动备份区分，只留最近几份。
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("停服自动备份", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "服务端退出后自动打包（auto_ 前缀），保留最近 ${BackupManager.AUTO_KEEP} 份",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoBackup, onCheckedChange = onAutoBackup)
            }
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
                                // 「恢复」是最高频动作，保留为一键直达（有二次确认兜底）；
                                // 导出/删除是低频动作，收进溢出菜单 —— 原来三个控件并排
                                // 会把 360dp 屏的备份名/时间戳列挤到只剩 ~100dp。
                                TextButton(onClick = { onRestoreRequest(f) }, enabled = !busy) { Text("恢复") }
                                var backupMenu by remember { mutableStateOf(false) }
                                IconButton(onClick = { backupMenu = true }, enabled = !busy) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "更多操作（导出 / 删除）",
                                    )
                                }
                                DropdownMenu(
                                    expanded = backupMenu,
                                    onDismissRequest = { backupMenu = false },
                                    shape = M3Shape.medium,
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("导出") },
                                        onClick = {
                                            backupMenu = false
                                            onExport(f)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            backupMenu = false
                                            onDelete(f)
                                        },
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
internal fun AddonsTab(
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

/**
 * 玩家管理：在线列表 + 白名单。
 *
 * 白名单**不依赖服务端运行**：直接读写实例目录下的 whitelist.json（见 [WhitelistFile]）。
 * 旧实现只有 `whitelist add/remove` 两个按钮且整块被 `if (running)` 包住 ——
 * 于是「先把朋友加进白名单再开服」这个最正常的用法做不到，用户也看不到名单里到底有谁，
 * 只能盲加盲删。
 *
 * 在线相关（OP / 踢出 / 刷新列表）仍然需要运行中，因为它们只能通过控制台命令完成。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerManageCard(
    viewModel: AppViewModel,
    instance: ServerInstance,
    running: Boolean,
) {
    val players by viewModel.onlinePlayers.collectAsStateWithLifecycle()
    var name by remember(instance.id) { mutableStateOf("") }
    var whitelist by remember(instance.id) { mutableStateOf(WhitelistFile.read(instance.dir)) }
    var msg by remember(instance.id) { mutableStateOf<String?>(null) }
    var confirmClear by remember(instance.id) { mutableStateOf(false) }

    // server.properties 的开关：white-list 决定"名单是否生效"，enforce-whitelist 决定
    // "已在服里的人被踢出名单后是否立刻踢下线"。两个都要能改，否则开了名单却不知道有没有生效。
    var props by remember(instance.id) { mutableStateOf(ServerProperties.load(instance.dir)) }
    val whitelistOn = props["white-list"].equals("true", ignoreCase = true)
    val enforceOn = props["enforce-whitelist"].equals("true", ignoreCase = true)

    fun setProp(key: String, value: String) {
        props = props.toMutableMap().apply { this[key] = value } as LinkedHashMap
        runCatching { ServerProperties.save(instance.dir, props) }
            .onFailure { msg = "保存失败：${it.message}" }
    }

    fun reload() {
        whitelist = WhitelistFile.read(instance.dir)
        props = ServerProperties.load(instance.dir)
    }

    M3ECard(
        variant = M3ECardVariant.Filled,
        title = "玩家管理",
        supporting = "白名单直接改文件，开服前后都能用；OP / 踢出需要服务端运行中。",
        trailing = {
            IconButton(onClick = {
                reload()
                if (running) viewModel.refreshPlayers()
                msg = null
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        },
        content = {
            // ── 白名单开关 ──
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用白名单", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (whitelistOn) "只有名单内的玩家能进服"
                        else "任何人都能进服（名单不生效）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = whitelistOn, onCheckedChange = { setProp("white-list", it.toString()) })
            }
            if (whitelistOn) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("移出名单立刻踢下线", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (enforceOn) "从名单移除的在线玩家会被踢出" else "已在线的人不受名单变更影响",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enforceOn, onCheckedChange = { setProp("enforce-whitelist", it.toString()) })
                }
                if (running) {
                    Text(
                        "开关改动需要重启服务端或执行 whitelist reload 才生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            // ── 在线玩家 ──
            if (running) {
                Text(
                    if (players.isEmpty()) "在线玩家：暂无（点右上角刷新）"
                    else "在线玩家（点名字填入下方输入栏）：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (players.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        players.forEach { p ->
                            Surface(
                                shape = M3Shape.large,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier
                                    .clickable { name = p }
                                    .padding(bottom = 6.dp),
                            ) {
                                Text(
                                    p,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    "服务端未运行：在线列表与 OP/踢出不可用，白名单仍可编辑。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 白名单内容 ──
            Text(
                "白名单（${whitelist.size} 人）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (whitelist.isEmpty()) {
                Text(
                    "名单为空。${if (whitelistOn) "白名单已启用 —— 现在没人能进服！" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (whitelistOn) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                FlowRow(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    whitelist.forEach { e ->
                        // 每个名字一个 chip，点一下填进输入框，右侧 × 直接移除
                        Surface(
                            shape = M3Shape.large,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    e.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable { name = e.name }
                                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                )
                                IconButton(
                                    onClick = {
                                        val removed = WhitelistFile.remove(instance.dir, e.name)
                                        reload()
                                        msg = if (removed) "已从白名单移除 ${e.name}" else "移除失败"
                                    },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "从白名单移除 ${e.name}",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it; msg = null },
                label = { Text("玩家名") },
                placeholder = { Text("如 Steve") },
                singleLine = true,
                shape = M3Shape.large,
                isError = name.isNotBlank() && !WhitelistFile.isValidName(name),
                supportingText = if (name.isNotBlank() && !WhitelistFile.isValidName(name)) {
                    { Text("玩家名为 1~16 个字符，不能含空格或引号") }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            // 白名单操作（不需要服务端运行）
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        when (val r = WhitelistFile.add(instance.dir, name)) {
                            is WhitelistFile.AddResult.Added -> {
                                msg = "已加入白名单：${name.trim()}"
                                name = ""
                            }
                            WhitelistFile.AddResult.AlreadyThere -> msg = "已经在白名单里了"
                            WhitelistFile.AddResult.Invalid -> msg = "玩家名不合法"
                            WhitelistFile.AddResult.TooLong -> msg = "玩家名超过 16 个字符"
                        }
                        reload()
                    },
                    enabled = WhitelistFile.isValidName(name),
                ) { Text("加入白名单") }

                OutlinedButton(
                    onClick = {
                        val n = name.trim()
                        val removed = WhitelistFile.remove(instance.dir, n)
                        reload()
                        msg = if (removed) "已从白名单移除 $n" else "名单里没有 $n"
                    },
                    enabled = WhitelistFile.isValidName(name),
                ) { Text("移出白名单") }

                if (whitelist.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text("清空名单") }
                }
            }

            // 在线操作（需要服务端运行）
            if (running) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { if (name.isNotBlank()) { viewModel.sendCommand("op ${name.trim()}") } }) {
                        Text("设为 OP")
                    }
                    OutlinedButton(onClick = { if (name.isNotBlank()) { viewModel.sendCommand("deop ${name.trim()}") } }) {
                        Text("取消 OP")
                    }
                    OutlinedButton(onClick = { if (name.isNotBlank()) { viewModel.sendCommand("kick ${name.trim()}") } }) {
                        Text("踢出")
                    }
                }
            }

            msg?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空白名单？") },
            text = {
                Text(
                    "将删除全部 ${whitelist.size} 条记录。" +
                        if (whitelistOn) "白名单当前已启用 —— 清空后没人能进服。" else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    WhitelistFile.clear(instance.dir)
                    reload()
                    confirmClear = false
                    msg = "白名单已清空"
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/**
 * 「改内存分配」对话框：**与创建向导共用同一个滑块控件（[MemorySliderRow]）和同一套规则
 * （[MemoryLimits]）**，所以"建好之后再改"看到的东西与创建时一致（真机需求）。
 *
 * 内存是启动参数（-Xmx/-Xms），改完要重启才生效 —— 运行中打开时在正文里写明。
 */
@Composable
internal fun EditMemoryDialog(
    visible: Boolean,
    instanceName: String,
    currentMb: Int,
    isRunning: Boolean,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    // 设备内存：读 /proc/meminfo。创建向导里已经踩过 —— 真机实测
    // ActivityManager.getMemoryInfo().availMem 会异常地返回 totalMem，两者保持一致。
    val mem = remember {
        fun read(key: String): Long = runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith(key) }
                    ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
        read("MemTotal") to read("MemAvailable")
    }
    val totalMb = mem.first / 1024f
    val suggestMb = MemoryLimits.suggestFrom(mem.second / 1024f)

    var mb by remember(visible, currentMb) {
        mutableStateOf(currentMb.toFloat().coerceIn(MemoryLimits.MIN_MB.toFloat(), MemoryLimits.MAX_MB.toFloat()))
    }
    var showExact by remember { mutableStateOf(false) }
    var exactText by remember { mutableStateOf("") }
    val fmtGb: (Float) -> String = { String.format(java.util.Locale.US, "%.1f GB", it / 1024f) }
    val exceeded = totalMb > 0f && mb > totalMb * 0.8f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("内存分配") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "「$instanceName」当前 ${currentMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MemorySliderRow(
                    sliderMb = mb,
                    autoMemory = false,
                    exceeded = exceeded,
                    fmtGb = fmtGb,
                    onMemoryMb = { mb = it },
                )
                Text(
                    if (exceeded) "超过设备内存的 80%（设备共 ${fmtGb(totalMb)}）"
                    else "设备共 ${fmtGb(totalMb)} · 建议 ${suggestMb.toInt()} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exceeded) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { mb = suggestMb }) {
                        Text("用建议值", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(onClick = { exactText = mb.toInt().toString(); showExact = true }) {
                        Text("精确输入", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (showExact) {
                    OutlinedTextField(
                        value = exactText,
                        onValueChange = { t -> exactText = t.filter { it.isDigit() }.take(5) },
                        label = { Text("MB") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        ),
                    )
                }
                Text(
                    "范围 ${MemoryLimits.MIN_MB} ~ ${MemoryLimits.MAX_MB} MB，每 ${MemoryLimits.STEP_MB} MB 一档；" +
                        if (isRunning) "服务器运行中，重启后生效。" else "下次启动生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            val typed = if (showExact) exactText.toIntOrNull() else null
            TextButton(onClick = { onConfirm(MemoryLimits.clamp(typed ?: mb.toInt())) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 高级设置：自定义 JVM 附加参数 + nogui 开关。
 *
 * 都是**启动参数**：保存只落盘，运行中的进程不受影响（保存后往控制台写"重启后生效"）。
 * extraJvmArgs 按空格切分后追加在固定 JVM flags 之后、-jar/@args 之前
 * （见 DefaultServerManager.extraJvmArgList）；含空格的参数不支持，
 * Forge 实例可写 user_jvm_args.txt。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
/** 实例可选的 Java 大版本（已创建的实例只有确定值，没有"自动"这一档） */
internal val JAVA_MAJORS: List<Int> = listOf(8, 17, 21, 25)

internal fun AdvancedSettingsCard(
    viewModel: AppViewModel,
    instance: ServerInstance,
    isRunning: Boolean,
) {
    var args by remember(instance.id, instance.extraJvmArgs) { mutableStateOf(instance.extraJvmArgs) }
    var nogui by remember(instance.id, instance.nogui) { mutableStateOf(instance.nogui) }
    var javaMajor by remember(instance.id, instance.javaMajor) { mutableStateOf(instance.javaMajor) }

    M3ECard(
        variant = M3ECardVariant.Filled,
        title = "高级设置",
        supporting = "空格分隔，重启后生效；写错会导致启动失败（原因见控制台）。Forge 也可写 user_jvm_args.txt",
    ) {
        OutlinedTextField(
            value = args,
            onValueChange = { args = it },
            label = { Text("JVM 附加参数") },
            placeholder = { Text("-XX:+UseG1GC -Dfile.encoding=UTF-8") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        // ── Java 版本 ──
        // 允许改：同一台设备可能装了多个 Java；整合包/插件常要求特定版本；
        // 也有人想用更高版本 Java 跑旧服务端来获得更好的 GC。
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                "Java 版本",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "当前 Java ${instance.javaMajor}" +
                    (if (instance.mcVersion.isNotEmpty()) {
                        " · MC ${instance.mcVersion} 推荐 Java ${
                            com.kaze.newage.data.model.JavaVersionInference.infer(instance.mcVersion)
                        }"
                    } else {
                        ""
                    }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            M3ESegmentedRow(
                options = JAVA_MAJORS,
                selected = javaMajor,
                label = { "Java $it" },
                onSelect = { javaMajor = it },
                height = 38.dp,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "无图形界面（nogui）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = nogui, onCheckedChange = { nogui = it })
        }
        TextButton(
            onClick = {
                viewModel.setExtraJvmArgs(instance, args)
                viewModel.setNogui(instance, nogui)
                if (javaMajor != instance.javaMajor) viewModel.setInstanceJava(instance, javaMajor)
            },
            enabled = (args != instance.extraJvmArgs) || (nogui != instance.nogui) ||
                (javaMajor != instance.javaMajor),
        ) {
            Text(if (isRunning) "保存（重启后生效）" else "保存")
        }
    }
}

/** 「配置」页：server.properties 可视化编辑器（三个分组卡 + 保存） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PropertiesEditor(
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

    // 「用其他应用打开 server.properties」：真机需求 —— 不依赖应用内编辑器，
    // 交给系统或第三方编辑器，方便高级用户自由改（写权限也一起授，否则存不回去）。
    // 走 FileProvider 的 content://：targetSdk≥24 传 file:// 会抛 FileUriExposedException。
    val ctx = LocalContext.current
    val propsFile = File(dir, "server.properties")
    M3EListItem(
        headline = "用其他应用打开",
        supporting = "交给系统或第三方编辑器改 server.properties，改完保存后重启服务端生效",
        trailing = {
            TextButton(onClick = {
                // 文件还不存在（没启动过）就先按编辑器里的当前值落一份，否则对方打开的是空文件
                if (!propsFile.isFile) runCatching { ServerProperties.save(dir, props) }
                if (!com.kaze.newage.util.openFileExternally(ctx, propsFile)) {
                    android.widget.Toast.makeText(
                        ctx,
                        "无法交给外部应用打开（实例目录不在可共享范围）：${propsFile.absolutePath}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }) { Text("打开") }
        },
    )

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
                    // 必须拒绝空串：`"".all { isDigit }` 为 true，清空输入框再保存会写下 server-port=，
                    // 之后 toIntOrNull() 得到 null，端口占用统计就漏掉这个实例，新建实例会撞端口
                    //
                    // 也要拒绝越界值：`0` 与 `99999` 都不是能 bind 的端口（0 = 由系统随机分配，
                    // >65535 直接 IllegalArgumentException），存进去之后服务端每次启动都停在
                    // 绑定阶段失败，而界面里看不出哪里不对。范围与创建向导、端口占用统计一致。
                    onValueChange = { v ->
                        val n = v.toIntOrNull()
                        if (v.isNotEmpty() && v.all { it.isDigit() } && n != null && n in 1024..65535) {
                            set("server-port", v)
                        }
                    },
                    label = { Text("端口") },
                    // 越界输入根本写不进 state（输入框会"打不进字"），必须说明原因，
                    // 否则用户只会以为应用坏了
                    supportingText = { Text("1024 ~ 65535，超出范围的输入不会保存") },
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
internal fun SettingSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun SettingSlider(
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
internal fun RenameInstanceDialog(
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
internal fun formatClock(totalSec: Long): String {
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
internal fun ServerState.shortLabel(): String = when (this) {
    ServerState.FirstRun -> "首次启动"
    ServerState.AcceptingEula -> "重启中"
    else -> toLabel()
}

/** 备份时间：今天/昨天用相对说法（草图「最近 今天 12:00」），更早给「MM-dd HH:mm」 */
internal fun backupStamp(f: File): String {
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
internal val propsSaver: Saver<LinkedHashMap<String, String>, ArrayList<String>> = Saver(
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

/**
 * 连接地址行：标签 + 等宽地址 + 复制按钮（手机上抄地址是高频且易错的动作）。
 * 局域网 IP 的提取见 [com.kaze.newage.util.LanIp]。
 */
@Composable
internal fun AddressCopyRow(label: String, address: String, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            address,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "复制 $address",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
