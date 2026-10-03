package com.kaze.newage.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.ai.AiSanitize
import com.kaze.newage.core.ai.AiSuggestion
import com.kaze.newage.core.console.LineType
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.ExpressiveLoadingIndicator
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3EStatusChip
import com.kaze.newage.ui.components.StatusTone
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.theme.consoleBackgroundColor
import com.kaze.newage.ui.theme.consoleLineColor
import com.kaze.newage.ui.theme.statusPalette
import com.kaze.newage.ui.toLabel
import com.kaze.newage.ui.toTone
import kotlinx.coroutines.launch

/** 首次进入的快捷提问（一键发送，省得打字） */
private val QUICK_QUESTIONS = listOf(
    "现在什么情况？",
    "谁在线？",
    "内存够不够？",
    "为什么卡住了 / 起不来？",
)

/**
 * AI 助手页（全屏聊天）。
 *
 * 版式刻意贴近聊天应用而不是"设置弹窗"：
 *   屏幕头（返回 + 标题 + 实例状态胶囊 + 设置入口）
 *   → 消息流（助手带头像在左，用户在右；建议命令是终端风格卡片）
 *   → 输入坞（思考强度 / 联网开关 + 输入框 + 发送）。
 *
 * 安全线不变：模型只能"说"，命令必须用户点「执行」才真正写进服务端 stdin。
 * 输入草稿与对话记录都存在 ViewModel，切页面/转屏不丢。
 */
@Composable
fun AiScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val messages by viewModel.aiMessages.collectAsStateWithLifecycle()
    val busy by viewModel.aiBusy.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val currentId by viewModel.currentInstanceId.collectAsStateWithLifecycle()
    val current = instances.firstOrNull { it.id == currentId }
    val prefs = viewModel.uiPrefs
    val thinking = prefs.aiThinking.value
    val searchOn = prefs.aiSearchOn.value
    val serverStates by viewModel.serverStates.collectAsStateWithLifecycle()
    val live by viewModel.aiLiveStream.collectAsStateWithLifecycle()

    // 离开本页 = 软停止本轮工具循环：循环继续跑的话，用户已经在看不到进度的情况下被读文件、
    // 抓网页、起不可见 WebView。唯一例外是"去 AI 设置"—— 那是同一个功能区域，通常马上回来，
    // 所以用一个标记把它排除掉（remember 的 MutableState 在 onDispose 里读到的是最新值）。
    var leftToSettings by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (!leftToSettings) viewModel.onAiScreenLeft() }
    }

    // 消息按生成它的实例判定运行状态（用户可能已切到别的实例）
    fun runningFor(msg: AppViewModel.AiChatMessage): Boolean {
        val id = msg.instanceId ?: currentId
        return id != null && serverStates[id] == com.kaze.newage.core.server.ServerState.Running
    }

    val palette = statusPalette()
    val stateColor = when (serverState.toTone()) {
        StatusTone.Running -> palette.running
        StatusTone.Busy -> palette.busy
        StatusTone.Idle -> palette.idle
        StatusTone.Error -> palette.error
    }

    // 底部 insets 用 safeDrawing 的 bottom 分量：键盘弹出 = 键盘顶，收起 = 底栏高，
    // 一处搞定（root 的 imePadding + dock 的 navigationBarsPadding 会把底栏高度算两次）
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
    ) {
        M3EScreenHeader(
            title = "AI 助手",
            subtitle = current?.let { inst ->
                buildString {
                    append(inst.name)
                    append(" · ")
                    append(inst.coreType.displayName)
                    append(" ")
                    append(inst.mcVersion)
                }
            } ?: "未选择实例",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            trailing = {
                M3EStatusChip(text = serverState.toLabel(), color = stateColor)
                // 删除会话历史：清空当前对话（本地记录一并清除，不可恢复所以给确认）
                if (messages.isNotEmpty()) {
                    var confirmClear by remember { mutableStateOf(false) }
                    IconButton(onClick = { confirmClear = true }, enabled = !busy) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空对话")
                    }
                    if (confirmClear) {
                        AlertDialog(
                            onDismissRequest = { confirmClear = false },
                            title = { Text("删除会话历史？") },
                            text = { Text("将清空 AI 助手的全部对话记录（${messages.size} 条），且不可恢复。") },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.clearAiChat()
                                    confirmClear = false
                                }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmClear = false }) { Text("取消") }
                            },
                        )
                    }
                }
                IconButton(onClick = {
                    // 去设置不算"离开 AI 页"（见上方的软停止说明）
                    leftToSettings = true
                    onOpenSettings()
                }) {
                    Icon(Icons.Filled.Tune, contentDescription = "AI 设置")
                }
            },
        )

        // ── 消息流 ──
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // 底部跟随：用户往上翻时不再抢滚动（距离 > 3 条就不自动跟），只浮出「回到底部」
        val liveFocusLen = (live?.content?.length ?: 0) + (live?.reasoning?.length ?: 0)
        suspend fun scrollToBottom(animated: Boolean) {
            val total = listState.layoutInfo.totalItemsCount
            if (total <= 0) return
            if (animated) listState.animateScrollToItem(total - 1)
            else listState.scrollToItem(total - 1)
        }
        LaunchedEffect(messages.size, busy, liveFocusLen) {
            if (messages.isEmpty() && live == null) return@LaunchedEffect
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
            val total = info.totalItemsCount
            if (total <= 0) return@LaunchedEffect
            // 差得少（3 条以内）自动跟随；差得多说明用户在翻历史，不打断（点「回到底部」恢复）
            val distance = total - 1 - last
            if (distance in 0..3) scrollToBottom(animated = distance <= 1)
        }
        val showJumpToBottom by remember {
            derivedStateOf { listState.canScrollForward && (messages.size > 3 || live != null) }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = M3Spacing.screenMargin,
                vertical = 8.dp,
            ),
        ) {
            if (messages.isEmpty()) {
                item(key = "onboarding") { OnboardingContent(viewModel) }
            }
            items(messages, key = { it.id }) { msg ->
                when {
                    // 错误消息（含写入被策略拦截的原因）永远是完整气泡：
                    // toolNote 行只有一行小字，会把"为什么被拦"这种关键信息吞掉
                    msg.isError -> ChatMessageRow(
                        msg = msg,
                        serverRunning = runningFor(msg),
                        onExecute = { viewModel.executeAiSuggestion(msg.id) },
                    )
                    msg.toolNote != null -> ToolNoteRow(msg)
                    msg.writeRequest != null -> WriteRequestRow(
                        msg = msg,
                        busy = busy,
                        onApprove = { viewModel.approveAiWrite(msg.id) },
                        onDeny = { viewModel.denyAiWrite(msg.id) },
                    )
                    else -> ChatMessageRow(
                        msg = msg,
                        serverRunning = runningFor(msg),
                        onExecute = { viewModel.executeAiSuggestion(msg.id) },
                    )
                }
            }
            // 流式实况：当前正在生成的回复（思考/正文逐 token 更新）
            live?.let { stream ->
                item(key = "live-stream") { LiveStreamRow(stream) }
            }
        }

            // 「回到底部」：用户翻历史或流式输出追不上时浮出，一键滑到最新。
            // 写成顶层私有组件：外层是 Column —— Box 里的 AnimatedVisibility 会被解析成
            // ColumnScope 的那个重载编译不过（控制台页踩过同一个坑）
            JumpToBottomPill(
                visible = showJumpToBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                scope.launch { scrollToBottom(animated = true) }
            }
        }

        // ── 思考中指示 + 取消 ──
        if (busy) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = M3Spacing.screenMargin, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExpressiveLoadingIndicator(
                    size = 20.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (thinking) "深度思考中…（先出推理再出答案，可能要一两分钟）"
                    else "正在思考…（首次响应可能要十几秒）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.cancelAiTurn() }) {
                    Text("取消")
                }
            }
        }

        // ── 输入坞 ──
        InputDock(
            viewModel = viewModel,
            thinking = thinking,
            searchOn = searchOn,
            busy = busy,
                    onToggleSearch = {
                        // API 源需要 Key、SearXNG 需要实例地址 —— 没配就带去设置页；本机浏览器零配置直接开
                        val prov = com.kaze.newage.core.ai.AiSearch.Provider.byId(prefs.aiSearchProviderId.value)
                        val needsSetup = (prov.needsKey || prov.needsUrl) &&
                            prefs.searchKeyFor(prov.id).isBlank()
                        if (needsSetup) onOpenSettings()
                        else viewModel.setAiSearchOn(!prefs.aiSearchOn.value)
                    },
        )
    }
}

/** 空状态：能力一览 + 快捷提问 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingContent(viewModel: AppViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("问问你的服务器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "AI 能看状态、读日志、改配置（写入需你确认）、查资料。\n命令与文件写入都必须经你确认才会执行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapabilityCard(Icons.Filled.Dns, "运行状态", "卡不卡、谁在线", Modifier.weight(1f))
            CapabilityCard(Icons.Filled.Terminal, "日志报错", "为什么起不来", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapabilityCard(Icons.Filled.People, "玩家内存", "内存够不够", Modifier.weight(1f))
            CapabilityCard(Icons.Filled.TravelExplore, "联网查资料", "搜攻略与报错", Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        Text("试试这样问", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK_QUESTIONS.forEach { q ->
                SuggestionChip(onClick = { viewModel.askAi(q) }, label = { Text(q) })
            }
        }
    }
}

@Composable
private fun CapabilityCard(icon: ImageVector, title: String, desc: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = M3Shape.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(
                    desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 一条对话：助手在左带头像，用户在右；建议命令是终端风格卡片 */
@Composable
private fun ChatMessageRow(
    msg: AppViewModel.AiChatMessage,
    serverRunning: Boolean,
    onExecute: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!msg.isUser) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            shape = if (msg.isUser) USER_BUBBLE_SHAPE else AI_BUBBLE_SHAPE,
            color = when {
                msg.isUser -> scheme.primaryContainer
                msg.isError -> scheme.errorContainer
                else -> scheme.surfaceContainerHigh
            },
            contentColor = when {
                msg.isUser -> scheme.onPrimaryContainer
                msg.isError -> scheme.onErrorContainer
                else -> scheme.onSurface
            },
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 思考过程：默认折叠，点开可看模型的完整推理（Operit 式）
                msg.reasoning?.let { reasoning ->
                    ThinkingPanel(reasoning)
                }
                msg.thinkingNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                // 联网搜索的情况先交代：搜到了什么词 / 为什么没搜成
                if (msg.searchError != null) {
                    Text(
                        "联网搜索失败：${msg.searchError}（按本地信息回答）",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                } else if (msg.searchQuery != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.TravelExplore,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                        Text(
                            "已搜索：${msg.searchQuery}（${msg.searchCount} 条结果）",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                msg.usageSummary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                msg.command?.let { cmd ->
                    AiSuggestionCard(
                        command = cmd,
                        sent = msg.commandSent,
                        serverRunning = serverRunning,
                        onExecute = onExecute,
                    )
                }
            }
        }
    }
}

/** 用户气泡：右下角收口；助手气泡：左下角收口 */
private val USER_BUBBLE_SHAPE = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
private val AI_BUBBLE_SHAPE = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)

/**
 * 流式实况气泡：思考与正文逐 token 增长（思考自动展开，完成后正式气泡默认折叠）。
 * 完成/取消/超时后 [_aiLiveStream 置空]，此行随 key 消失。
 */
@Composable
private fun LiveStreamRow(stream: AppViewModel.AiLiveStream) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.SmartToy,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = AI_BUBBLE_SHAPE,
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (stream.reasoning.isNotEmpty()) {
                    Text(
                        if (stream.thinking) "思考中（${stream.reasoning.length} 字）…"
                        else "推理中（${stream.reasoning.length} 字）…",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        stream.reasoning.takeLast(600),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                    )
                }
                if (stream.content.isNotEmpty()) {
                    Text(stream.content, style = MaterialTheme.typography.bodyMedium)
                }
                Text("▍", color = scheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * 思考过程面板：默认折叠的一块"草稿纸"（Operit 式）。
 * 深度思考模型的推理可能很长 —— 展开后限高滚动，不撑爆气泡。
 */
@Composable
private fun ThinkingPanel(reasoning: String) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = M3Shape.small,
        color = scheme.surfaceContainerHighest.copy(alpha = 0.5f),
        contentColor = scheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = scheme.onSurfaceVariant,
                )
                Text(
                    "思考过程（${reasoning.length} 字）",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Column(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 10.dp)
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(reasoning, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 工具动作行：轻量居中提示（读取/列出/写入了哪个文件），不占气泡 */
@Composable
private fun ToolNoteRow(msg: AppViewModel.AiChatMessage) {
    Text(
        "· ${msg.toolNote} ·",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 文件写入确认卡片：目标实例 + 绝对路径 + 完整新内容预览（终端深底、可滚动）+ 允许/拒绝。
 * 安全底线：AI 永远不能直接落盘 —— 每次写入都必须人工批准；
 * 批准后由 AiFileTools 执行（自动留备份），结果回喂给模型继续回答。
 *
 * 卡片要对"同名文件"这件事负责：每个实例都有一份 server.properties，
 * 只给相对路径的话，用户根本分不清这次要改的是哪个实例的哪份文件，
 * 所以实例名与解析后的绝对路径必须上卡；脚本类文件另加红字警示。
 */
@Composable
private fun WriteRequestRow(
    msg: AppViewModel.AiChatMessage,
    busy: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val req = msg.writeRequest ?: return
    val scheme = MaterialTheme.colorScheme
    // 查看全文：内容上限 256KB，全量组合会把重组卡死，所以默认只预览一段；
    // 但"看不到的部分"不能让用户靠猜 —— 给入口，点了才渲染全文。
    var showFull by remember(req) { mutableStateOf(false) }
    val previewLimit = 4000
    val truncated = req.content.length > previewLimit
    val shown = if (showFull || !truncated) req.content else req.content.take(previewLimit)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = AI_BUBBLE_SHAPE,
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = scheme.primary,
                    )
                    Text("AI 请求写入文件", style = MaterialTheme.typography.titleSmall)
                }
                // 目标实例 + 解析后的绝对路径：路径里可能带模型/网页给的控制符，
                // 上屏前统一过滤（终端转义与 bidi 反转都能伪造出"看起来是另一个文件"）
                Text(
                    "实例：${AiSanitize.displayOneLine(req.instanceName.ifBlank { msg.instanceId ?: "（未知）" })}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    AiSanitize.displayOneLine(req.absPath.ifBlank { req.path }),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                if (req.absPath.isNotBlank() && req.absPath != req.path) {
                    Text(
                        "（相对路径：${AiSanitize.displayOneLine(req.path)}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                req.warning?.let { warning ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = scheme.error,
                        )
                        Text(
                            AiSanitize.display(warning),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.error,
                        )
                    }
                }
                Text(
                    "${req.bytes} 字节 · 共 ${req.content.lines().size} 行 · 覆盖已有文件时会自动留备份",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Surface(
                    shape = M3Shape.medium,
                    color = consoleBackgroundColor(),
                    contentColor = consoleLineColor(LineType.Info),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier
                            .padding(10.dp)
                            .heightIn(max = if (showFull) 320.dp else 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            AiSanitize.display(shown),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (truncated) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (showFull) "已显示全文（${req.content.length} 字符）"
                            else "预览已截断：仅显示前 $previewLimit 字符，共 ${req.content.length} 字符",
                            style = MaterialTheme.typography.labelSmall,
                            color = consoleLineColor(LineType.Warn),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showFull = !showFull }) {
                            Text(if (showFull) "只看预览" else "查看全文")
                        }
                    }
                }
                when (msg.writeState) {
                    1 -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = scheme.primary,
                        )
                        Text("已写入", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    }
                    2 -> Text(
                        "已拒绝（未写入任何内容）",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                    )
                    3 -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 成功才置 1：失败必须如实显示，并且允许重试（否则用户被卡死在"待确认"）
                        Text(
                            "写入失败，未改动文件",
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.error,
                        )
                        Button(onClick = onApprove, enabled = !busy) { Text("重试") }
                        OutlinedButton(onClick = onDeny, enabled = !busy) { Text("放弃") }
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onApprove, enabled = !busy) {
                            Text("允许写入")
                        }
                        OutlinedButton(onClick = onDeny, enabled = !busy) {
                            Text("拒绝")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 建议命令卡片：用控制台同款终端深底 —— AI 的话最终要落回终端执行，视觉上也接回去。
 * 危险命令（stop/op/ban…）红字提示；执行可用性与服务端运行状态同源。
 */
@Composable
private fun AiSuggestionCard(
    command: String,
    sent: Boolean,
    serverRunning: Boolean,
    onExecute: () -> Unit,
) {
    val dangerous = AiSuggestion.isDangerous(command)
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = M3Shape.medium,
        color = consoleBackgroundColor(),
        contentColor = consoleLineColor(LineType.Info),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = consoleLineColor(LineType.System),
                )
                Text(
                    "建议命令",
                    style = MaterialTheme.typography.labelSmall,
                    color = consoleLineColor(LineType.System),
                )
                Spacer(Modifier.weight(1f))
                if (sent) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = consoleLineColor(LineType.System),
                    )
                    Text(
                        "已发送到控制台",
                        style = MaterialTheme.typography.labelSmall,
                        color = consoleLineColor(LineType.System),
                    )
                }
            }
            if (dangerous) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = consoleLineColor(LineType.Error),
                    )
                    Text(
                        "危险命令：会影响玩家或服务端，请确认后再执行",
                        style = MaterialTheme.typography.labelMedium,
                        color = consoleLineColor(LineType.Error),
                    )
                }
            }
            Text(
                command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = consoleLineColor(LineType.Command),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onExecute, enabled = !sent && serverRunning) {
                    Text(if (sent) "已发送" else "执行")
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
                    Text("复制")
                }
                if (!sent && !serverRunning) {
                    Text(
                        "服务端未运行",
                        style = MaterialTheme.typography.labelSmall,
                        color = consoleLineColor(LineType.Warn),
                    )
                }
            }
        }
    }
}

/** 输入坞：开关行（当前使用的模型配置名在右侧提示）+ 输入行 */
@Composable
private fun InputDock(
    viewModel: AppViewModel,
    thinking: Boolean,
    searchOn: Boolean,
    busy: Boolean,
    onToggleSearch: () -> Unit,
) {
    val prefs = viewModel.uiPrefs
    val input by viewModel.aiDraft.collectAsStateWithLifecycle()
    val profileName = prefs.aiProfiles.value
        .firstOrNull { it.id == prefs.aiChatProfileId.value }
        ?.name?.ifBlank { null } ?: prefs.aiProfiles.value.firstOrNull()?.name?.ifBlank { null }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = thinking,
                    onClick = { viewModel.setAiThinking(!thinking) },
                    label = { Text("深度思考") },
                )
                FilterChip(
                    selected = searchOn,
                    onClick = onToggleSearch,
                    label = { Text("联网") },
                )
                Spacer(Modifier.weight(1f))
                profileName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { viewModel.setAiDraft(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("问问 AI：现在什么情况？") },
                    singleLine = true,
                    enabled = !busy,
                    shape = M3Shape.groupSingle(52f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (!busy && input.isNotBlank()) {
                            viewModel.askAi(input)
                            viewModel.setAiDraft("")
                        }
                    }),
                )
                Surface(
                    modifier = Modifier.size(52.dp).clip(CircleShape),
                    shape = CircleShape,
                    color = if (!busy && input.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (!busy && input.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    enabled = !busy && input.isNotBlank(),
                    onClick = {
                        viewModel.askAi(input)
                        viewModel.setAiDraft("")
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「回到底部」浮动胶囊（顶层私有组件，见调用点的说明：ColumnScope 重载冲突）。
 */
@Composable
private fun JumpToBottomPill(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.clip(M3Shape.groupSingle(36f)),
            shape = M3Shape.groupSingle(36f),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onClick,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("回到底部", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
