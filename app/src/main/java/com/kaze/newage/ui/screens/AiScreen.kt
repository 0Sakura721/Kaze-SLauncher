package com.kaze.newage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    val serverRunning = serverState == com.kaze.newage.core.server.ServerState.Running

    val palette = statusPalette()
    val stateColor = when (serverState.toTone()) {
        StatusTone.Running -> palette.running
        StatusTone.Busy -> palette.busy
        StatusTone.Idle -> palette.idle
        StatusTone.Error -> palette.error
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
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
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Tune, contentDescription = "AI 设置")
                }
            },
        )

        // ── 消息流 ──
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size, busy) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
                ChatMessageRow(
                    msg = msg,
                    serverRunning = serverRunning,
                    onExecute = { viewModel.executeAiSuggestion(msg.id) },
                )
            }
        }

        // ── 思考中指示 ──
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
                )
            }
        }

        // ── 输入坞 ──
        InputDock(
            viewModel = viewModel,
            thinking = thinking,
            searchOn = searchOn,
            busy = busy,
            onToggleSearch = {
                // API 源需要 Key（没配就带去设置页）；本机浏览器源零 Key，直接开
                val prov = com.kaze.newage.core.ai.AiSearch.Provider.byId(prefs.aiSearchProviderId.value)
                if (prov.needsKey && prefs.aiSearchKey.value.isBlank()) onOpenSettings()
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
            "AI 能看状态、读日志、查资料。\n它只给建议 —— 命令要点「执行」才会真正发送。",
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
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
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
