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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.ai.AiProfileStore
import com.kaze.newage.core.ai.AiSearch
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
    // SettingsPrefs 里是 Compose State：读取即观察，保存后立即生效
    val thinking = prefs.aiThinking.value
    val searchOn = prefs.aiSearchOn.value
    var showConfig by remember { mutableStateOf(!prefs.aiConfig().isConfigured) }

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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    searchOn = searchOn,
                    onToggleSearch = {
                        // API 源需要 Key（没配就带去配置页）；本机浏览器源零 Key，直接开
                        val prov = AiSearch.Provider.byId(prefs.aiSearchProviderId.value)
                        if (prov.needsKey && prefs.aiSearchKey.value.isBlank()) showConfig = true
                        else viewModel.setAiSearchOn(!prefs.aiSearchOn.value)
                    },
                )
            }
        }
    }
}

/** 对话区：开关行（思考强度 / 联网）+ 消息列表 + 快捷提问 + 输入行（草稿存 ViewModel 不丢） */
@Composable
private fun AiChatArea(
    viewModel: AppViewModel,
    messages: List<AppViewModel.AiChatMessage>,
    busy: Boolean,
    serverRunning: Boolean,
    thinking: Boolean,
    searchOn: Boolean,
    onToggleSearch: () -> Unit,
) {
    val input by viewModel.aiDraft.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 新消息（含错误提示）自动滚到最新一条
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxWidth()) {
        // 思考强度 / 联网开关：聊天中随时可切；联网未配 Key 时点击会带去配置页
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
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
        }

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
                // 联网搜索的情况先交代：搜到了什么词 / 为什么没搜成
                if (msg.searchError != null) {
                    Text(
                        "联网搜索失败：${msg.searchError}（按本地信息回答）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (msg.searchQuery != null) {
                    Text(
                        "已搜索：${msg.searchQuery}（${msg.searchCount} 条结果）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
 * AI 配置页：模型配置档案（Operit 式「多配置 + 功能分配」轻量版）+ 联网搜索源。
 *
 * 档案 = 命名的一组（服务地址 / Key / 逗号分隔模型列表），可建多份，单选一份供对话；
 * 「思考强度」就是取档案模型列表的第几个（[0] 标准 / [1] 深度思考）。
 * 搜索源独立于模型档案：Tavily（国际免费额度）/ 博查（国内直连付费），二选一填 Key。
 * 所有 Key 只存应用私有目录，不上传、不进日志。
 */
@Composable
private fun AiConfigForm(viewModel: AppViewModel, onDone: () -> Unit) {
    val prefs = viewModel.uiPrefs
    val profiles = prefs.aiProfiles.value
    // null = 档案列表 + 搜索配置；非 null = 正在编辑/新增该档案
    var editing by remember { mutableStateOf<AiProfile?>(null) }

    val editingProfile = editing
    if (editingProfile != null) {
        AiProfileEditForm(
            initial = editingProfile,
            isNew = profiles.none { it.id == editingProfile.id },
            onSave = { p ->
                viewModel.saveAiProfile(p)
                editing = null
            },
            onDelete = { id ->
                viewModel.deleteAiProfile(id)
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── 模型配置档案 ──
        Text("模型配置", style = MaterialTheme.typography.titleSmall)
        Text(
            "可建多份配置（不同服务商 / Key），单选一份供对话使用。" +
                "配置里的模型名可填多个（逗号分隔）：第 1 个 = 标准，第 2 个 = 深度思考。" +
                "API Key 只保存在应用私有目录，不上传、不进日志。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        profiles.forEach { p ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 单选 = 功能分配：对话使用这份配置
                RadioButton(
                    selected = p.id == prefs.aiChatProfileId.value,
                    onClick = { viewModel.setChatAiProfile(p.id) },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        p.name.ifBlank { "未命名" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        p.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { editing = p }) { Text("编辑") }
            }
        }
        if (profiles.isEmpty()) {
            Text(
                "还没有配置。点「新增配置」——已预填 DeepSeek 默认值，填上你的 Key 即可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { editing = AiProfile(name = "DeepSeek") }) {
            Text("＋ 新增配置")
        }

        Spacer(Modifier.height(8.dp))

        // ── 联网搜索 ──
        Text("联网搜索", style = MaterialTheme.typography.titleSmall)
        var providerId by remember { mutableStateOf(prefs.aiSearchProviderId.value) }
        var searchKey by remember { mutableStateOf(prefs.aiSearchKey.value) }
        val provider = AiSearch.Provider.byId(providerId)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiSearch.Provider.entries.forEach { prov ->
                FilterChip(
                    selected = prov.id == providerId,
                    onClick = { providerId = prov.id },
                    label = { Text(prov.displayName) },
                )
            }
        }
        if (provider.needsKey) {
            OutlinedTextField(
                value = searchKey,
                onValueChange = { searchKey = it },
                label = { Text("搜索 API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
        } else {
            Text(
                "该搜索源无需 Key、不注册任何服务：由手机直接加载搜索结果页解析。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            provider.hint + "。开启「联网」后，AI 会先生成搜索词、把结果带入回答；搜索失败不影响回答。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row {
            Button(onClick = {
                viewModel.setAiSearch(providerId, searchKey)
                onDone()
            }) {
                Text(if (prefs.aiConfig().isConfigured) "保存" else "保存并开始")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 单个档案的编辑表单（新增与编辑共用） */
@Composable
private fun AiProfileEditForm(
    initial: AiProfile,
    isNew: Boolean,
    onSave: (AiProfile) -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var baseUrl by remember(initial.id) { mutableStateOf(initial.baseUrl) }
    var models by remember(initial.id) { mutableStateOf(initial.models.joinToString(", ")) }
    var key by remember(initial.id) { mutableStateOf(initial.apiKey) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (isNew) "新增模型配置" else "编辑模型配置", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("配置名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("服务地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = models,
            onValueChange = { models = it },
            label = { Text("模型名（逗号分隔：第 1 个标准，第 2 个深度思考）") },
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onSave(
                    initial.copy(
                        name = name.trim().ifBlank { "未命名" },
                        baseUrl = baseUrl.trim(),
                        models = AiProfileStore.parseModelList(models),
                        apiKey = key.trim(),
                    )
                )
            }) {
                Text("保存")
            }
            if (!isNew) {
                TextButton(onClick = { onDelete(initial.id) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        }
        Text(
            "提示：模型名全留空时回退 DeepSeek 默认（deepseek-chat）；只填 1 个时「深度思考」也走这同一个模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}
