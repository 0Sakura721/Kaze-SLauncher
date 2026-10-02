package com.kaze.newage.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.ai.AiSuggestion
import com.kaze.newage.core.console.LineType
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.theme.consoleLineColor

/** 首次进入面板时的快捷提问（一键发送，省得打字） */
private val QUICK_QUESTIONS = listOf(
    "现在什么情况？",
    "谁在线？",
    "内存够不够？",
    "为什么卡住了 / 起不来？",
)

/**
 * AI 助手面板（P0：只读诊断 + 建议命令）。
 *
 * 安全线：模型只能"说"，不能"做" —— 它产出的命令先过本地清洗（[AiSuggestion]），
 * 再以卡片呈现，用户点「执行」才真正写进服务端 stdin；面板自身没有任何直接
 * 改变服务端状态的能力。API Key 未配置时先显示配置表单（也常驻一个配置入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantSheet(
    viewModel: AppViewModel,
    instanceName: String,
    serverRunning: Boolean,
    onDismiss: () -> Unit,
) {
    val messages by viewModel.aiMessages.collectAsStateWithLifecycle()
    val busy by viewModel.aiBusy.collectAsStateWithLifecycle()
    val prefs = viewModel.uiPrefs
    // SettingsPrefs 里是 Compose State：读取即观察，保存后"保存并开始"立即生效
    val thinking = prefs.aiThinking.value
    var showConfig by remember { mutableStateOf(prefs.aiApiKey.value.isBlank()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = M3Spacing.screenMargin)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text("AI 助手", style = MaterialTheme.typography.titleMedium)
                    Text(
                        instanceName.ifBlank { "未选择实例" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                // 思考强度开关：聊天中随时可切（DeepSeek = chat/reasoner 模型切换；
                // 模型名在配置表单里可自行编辑以适配其他服务商）
                TextButton(onClick = { viewModel.setAiThinking(!thinking) }) {
                    Icon(
                        Icons.Filled.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (thinking) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "深度思考",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (thinking) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showConfig = !showConfig }) {
                    Icon(Icons.Filled.Tune, contentDescription = "AI 接口配置")
                }
                if (messages.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearAiChat() }, enabled = !busy) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空对话")
                    }
                }
            }

            if (showConfig) {
                AiConfigForm(viewModel = viewModel, onDone = { showConfig = false })
            } else {
                AiChatArea(
                    viewModel = viewModel,
                    messages = messages,
                    busy = busy,
                    serverRunning = serverRunning,
                    thinking = thinking,
                )
            }
        }
    }
}

/** 对话区：消息列表 + 快捷提问 + 输入行（输入草稿存在 ViewModel，切页面/关面板不丢） */
@Composable
private fun AiChatArea(
    viewModel: AppViewModel,
    messages: List<AppViewModel.AiChatMessage>,
    busy: Boolean,
    serverRunning: Boolean,
    thinking: Boolean,
) {
    val input by viewModel.aiDraft.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 新消息（含错误提示）自动滚到最新一条
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column {
                        Text(
                            "问问当前实例的情况，例如：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        QUICK_QUESTIONS.forEach { q ->
                            SuggestionChip(onClick = { viewModel.askAi(q) }, label = { Text(q) })
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { msg ->
                AiMessageRow(
                    msg = msg,
                    serverRunning = serverRunning,
                    onExecute = { viewModel.executeAiSuggestion(msg.id) },
                )
            }
        }

        if (busy) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    if (thinking) "深度思考中…（先出推理再出答案，可能要一两分钟）"
                    else "正在思考…（首次响应可能要十几秒）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
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

/** 一条对话气泡：用户右侧主色，助手左侧中性，错误红色容器 */
@Composable
private fun AiMessageRow(
    msg: AppViewModel.AiChatMessage,
    serverRunning: Boolean,
    onExecute: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = M3Shape.medium,
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
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

/**
 * 建议命令卡片：等宽字体展示 + 「执行」/「复制」。
 *
 * 执行可用性与服务端运行状态同源；危险命令（stop/op/ban…）给出醒目提示，
 * 但决定权始终在用户 —— 这是 P0 的安全底线。
 */
@Composable
private fun AiSuggestionCard(
    command: String,
    sent: Boolean,
    serverRunning: Boolean,
    onExecute: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dangerous = AiSuggestion.isDangerous(command)
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = M3Shape.small,
        color = scheme.surface,
        contentColor = scheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (dangerous) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = scheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "危险命令：会影响玩家或服务端，请确认后再执行",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.error,
                    )
                }
            }
            Text(
                command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = consoleLineColor(LineType.Command),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onExecute, enabled = !sent && serverRunning) {
                    Text(if (sent) "已发送" else "执行")
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
                    Text("复制")
                }
                when {
                    sent -> Text(
                        "已发送到控制台",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    !serverRunning -> Text(
                        "服务端未运行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * AI 接口配置表单：OpenAI 兼容端点（服务地址 / 标准与思考模型名 / Key / 思考强度）。
 *
 * 「思考强度」的适配逻辑**以 DeepSeek 优先**：深度思考就是换 reasoner 模型，
 * 默认值就是 DeepSeek 的两个官方模型名；其他 OpenAI 兼容服务由玩家自行编辑
 * 两个模型名（或只填标准名、留空思考名 = 永远回退标准模型）。
 */
@Composable
private fun AiConfigForm(viewModel: AppViewModel, onDone: () -> Unit) {
    val prefs = viewModel.uiPrefs
    var baseUrl by remember { mutableStateOf(prefs.aiBaseUrl.value) }
    var modelStandard by remember { mutableStateOf(prefs.aiModelStandard.value) }
    var modelThinking by remember { mutableStateOf(prefs.aiModelThinking.value) }
    var key by remember { mutableStateOf(prefs.aiApiKey.value) }
    var thinking by remember { mutableStateOf(prefs.aiThinking.value) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("AI 接口配置（OpenAI 兼容）", style = MaterialTheme.typography.titleSmall)
        Text(
            "默认适配 DeepSeek：标准 = deepseek-chat，深度思考 = deepseek-reasoner（更聪明也更慢）。" +
                "换其他 OpenAI 兼容服务（硅基流动 / OpenRouter / 本地推理等）时，两个模型名可自行编辑。" +
                "API Key 只保存在应用私有目录，不上传、不进日志。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("服务地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = modelStandard,
            onValueChange = { modelStandard = it },
            label = { Text("标准模式模型名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = modelThinking,
            onValueChange = { modelThinking = it },
            label = { Text("深度思考模式模型名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        // 思考强度：面板头部也能随时切，这里是同一份状态的另一个入口
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "思考强度",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilterChip(
                selected = !thinking,
                onClick = { thinking = false },
                label = { Text("标准") },
            )
            FilterChip(
                selected = thinking,
                onClick = { thinking = true },
                label = { Text("深度思考") },
            )
        }
        Row {
            Button(onClick = {
                viewModel.saveAiConfig(baseUrl, modelStandard, modelThinking, key, thinking)
                onDone()
            }) {
                Text(if (key.isBlank()) "保存" else "保存并开始")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
