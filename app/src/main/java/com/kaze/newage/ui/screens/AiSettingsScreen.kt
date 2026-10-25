package com.kaze.newage.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import com.kaze.newage.core.ai.AiCommandPolicy
import com.kaze.newage.core.ai.AiToolPolicy
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.ai.AiProfileStore
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3EStatusChip
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing

/**
 * AI 设置页（全屏）：模型配置档案 + 联网搜索源。
 *
 * 档案卡片单击 = 「对话」功能分配（参照 Operit 的 FunctionType→configId，卡片右侧
 * 会挂「对话使用」徽标）；联网搜索源三选一（本机浏览器源零 Key）。编辑/新增是整页
 * 表单；删除当前使用的档案会自动回退到剩余第一个。Key 只存应用私有目录。
 */
@Composable
fun AiSettingsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val prefs = viewModel.uiPrefs
    // null = 档案列表 + 搜索源；非 null = 正在编辑/新增该档案
    var editing by remember { mutableStateOf<AiProfile?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            // 表单页必须避让键盘：本项目是 adjustNothing + edge-to-edge，
            // 不补的话聚焦下半屏输入框时键盘直接盖住内容且滚不出来
            .imePadding()
    ) {
        // 头部钉住不随内容滚走：长内容滚下去后返回键也得在
        M3EScreenHeader(
            title = "AI 设置",
            subtitle = "模型配置 · 联网搜索",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
        val editingProfile = editing
        if (editingProfile != null) {
            if (prefs.aiSecretStorageError.value) {
                Text(
                    "系统密钥库不可用或写入失败，API Key 未保存。请检查设备安全组件后重试；应用不会降级为明文存储。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = M3Spacing.screenMargin, vertical = 8.dp),
                )
            }
            AiProfileEditForm(
                initial = editingProfile,
                isNew = prefs.aiProfiles.value.none { it.id == editingProfile.id },
                onSave = { p ->
                    if (viewModel.saveAiProfile(p)) editing = null
                },
                onDelete = { id ->
                    viewModel.deleteAiProfile(id)
                    editing = null
                },
                onCancel = { editing = null },
            )
        } else {
            ProfileSection(viewModel, onEdit = { editing = it })
            Spacer(Modifier.height(8.dp))
            CommandModeSection(viewModel)
    ToolPolicySection(viewModel)
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = M3Spacing.screenMargin, vertical = 8.dp),
    )
}

@Composable
private fun SectionNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = M3Spacing.screenMargin),
    )
}

// ── 模型配置档案 ──

@Composable
private fun ProfileSection(viewModel: AppViewModel, onEdit: (AiProfile) -> Unit) {
    val prefs = viewModel.uiPrefs
    val profiles = prefs.aiProfiles.value

    SectionTitle("模型配置")
    // 档案 JSON 损坏：配置看起来"全没了"，但原文已被保留 —— 必须明说，并给一个收尾入口。
    // 沉默的话，用户会以为是自己删的，或者以为启动器把他的 Key 弄丢了。
    if (prefs.aiProfilesCorrupt.value) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin, vertical = 4.dp),
            shape = M3Shape.largeIncreased,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("配置文件读取失败", style = MaterialTheme.typography.titleSmall)
                Text(
                    "已保存的模型配置（含 API Key）格式损坏，本次没能读出来 —— " +
                        "原始内容已原样保留，没有被删除。请重新填写一次 API Key；" +
                        "确认不再需要那份原文后，点下面的按钮清除。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { viewModel.clearCorruptAiProfiles() }) {
                    Text("我已处理，清除提示")
                }
            }
        }
    }
    SectionNote(
        "可建多份配置（不同服务商 / Key），单击卡片选择用于对话；点「编辑」修改模型与 Key。"
    )
    Spacer(Modifier.height(8.dp))

    profiles.forEach { p ->
        val assigned = p.id == prefs.aiChatProfileId.value
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin, vertical = 4.dp),
            shape = M3Shape.largeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            onClick = { viewModel.setChatAiProfile(p.id) },
        ) {
            Row(
                Modifier.padding(start = 4.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 选择交给整卡点击；单选钮只负责展示当前分配
                RadioButton(selected = assigned, onClick = null)
                Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            p.name.ifBlank { "未命名" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (assigned) {
                            M3EStatusChip(
                                text = "对话使用",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        p.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onEdit(p) }) {
                    Text("编辑")
                }
            }
        }
    }

    OutlinedButton(
        onClick = { onEdit(AiProfile(name = "DeepSeek")) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = M3Spacing.screenMargin, vertical = 6.dp),
        shape = M3Shape.large,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("新增配置")
    }
    if (profiles.isEmpty()) {
        SectionNote("还没有配置 —— 点「新增配置」已预填 DeepSeek 默认值，填上你的 Key 即可用。")
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
    var extraBody by remember(initial.id) { mutableStateOf(initial.extraBody) }

    SectionTitle(if (isNew) "新增配置" else "编辑配置")
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("配置名称") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = M3Spacing.screenMargin),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text("服务地址") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = M3Spacing.screenMargin),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = models,
        onValueChange = { models = it },
        label = { Text("模型名（可多个，逗号分隔）") },
        placeholder = { Text("deepseek-flash") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = M3Spacing.screenMargin),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("API Key") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = M3Spacing.screenMargin),
        visualTransformation = PasswordVisualTransformation(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = extraBody,
        onValueChange = { extraBody = it },
        label = { Text("附加请求参数（JSON，可选）") },
        placeholder = { Text("""{"enable_thinking":true}""") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = M3Spacing.screenMargin),
        minLines = 2,
    )
    Spacer(Modifier.height(6.dp))
    SectionNote(
        "模型名留空回退 DeepSeek 默认；旧名（deepseek-chat / deepseek-reasoner）保存时自动归一。\n" +
            "「深度思考」：DeepSeek 走原生 thinking 参数；其它服务商若思考=换模型名，" +
            "填第二个模型名即可，或把显式参数写进附加参数。"
    )
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.padding(horizontal = M3Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = {
            onSave(
                initial.copy(
                    name = name.trim().ifBlank { "未命名" },
                    baseUrl = baseUrl.trim(),
                    models = AiProfileStore.parseModelList(models),
                    apiKey = key.trim(),
                    extraBody = extraBody.trim(),
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
}

// ── 命令执行档位（借鉴 Harness 的权限分层）──

@Composable
private fun CommandModeSection(viewModel: AppViewModel) {
    val prefs = viewModel.uiPrefs
    val current = AiCommandPolicy.modeById(prefs.aiCommandMode.value)

    SectionTitle("控制台命令执行")
    SectionNote("决定 AI 能在多大范围内直接执行服务端命令。无论哪档，执行记录都会写入审计日志。")
    Spacer(Modifier.height(8.dp))

    AiCommandPolicy.Mode.entries.forEach { mode ->
        val selected = mode.id == current.id
        val danger = mode == AiCommandPolicy.Mode.ALL
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin, vertical = 4.dp),
            shape = M3Shape.largeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            onClick = { viewModel.setAiCommandMode(mode.id) },
        ) {
            Row(
                Modifier.padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(mode.label, style = MaterialTheme.typography.titleSmall)
                        if (selected) {
                            M3EStatusChip(text = "当前", color = MaterialTheme.colorScheme.primary)
                        }
                        if (danger) {
                            M3EStatusChip(text = "慎用", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(
                        mode.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (current == AiCommandPolicy.Mode.ALL) {
        SectionNote(
            "已放开全部命令：AI 可能自行执行 stop 等影响服务端的命令（仍会先说明意图）。" +
                "随时可切回「仅建议」。"
        )
    }
    Spacer(Modifier.height(4.dp))
}

// ── 每个工具的权限（借鉴 Operit：Allow automatically / Ask every time / Deny）──

@Composable
private fun ToolPolicySection(viewModel: AppViewModel) {
    val prefs = viewModel.uiPrefs
    SectionTitle("工具权限")
    SectionNote(
        "逐个工具决定 AI 能不能自己动手。自动＝需要时直接执行；每次询问＝执行前弹确认卡；" +
            "禁止＝永不执行（并会告诉模型它被禁用了，免得反复重试）。" +
            "读取敏感文件（凭据、ops.json、开了 rcon 的 server.properties）始终要确认，" +
            "不受这里的「自动」放宽。控制台命令由上面的「控制台命令执行」单独管。"
    )
    Spacer(Modifier.height(8.dp))

    AiToolPolicy.TOOLS.forEach { tool ->
        val current = prefs.aiToolMode(tool.id)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin, vertical = 4.dp),
            shape = M3Shape.largeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(tool.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    tool.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AiToolPolicy.Mode.entries.forEach { mode ->
                        FilterChip(
                            selected = mode == current,
                            onClick = { prefs.setAiToolPolicy(tool.id, mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
