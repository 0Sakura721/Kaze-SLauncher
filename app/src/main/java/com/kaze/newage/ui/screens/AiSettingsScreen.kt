package com.kaze.newage.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.ai.AiProfileStore
import com.kaze.newage.core.ai.AiSearch
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
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
    ) {
        M3EScreenHeader(
            title = "AI 设置",
            subtitle = "模型配置 · 联网搜索",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        val editingProfile = editing
        if (editingProfile != null) {
            AiProfileEditForm(
                initial = editingProfile,
                isNew = prefs.aiProfiles.value.none { it.id == editingProfile.id },
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
        } else {
            ProfileSection(viewModel, onEdit = { editing = it })
            Spacer(Modifier.height(8.dp))
            SearchSection(viewModel)
        }
        Spacer(Modifier.height(32.dp))
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
    SectionNote(
        "可建多份配置（不同服务商 / Key），单击卡片选择用于对话。DeepSeek 官方推荐用 " +
            "deepseek-flash（思考模式由「深度思考」开关控制，无需填两个模型名）；" +
            "其它服务商若“思考 = 换模型名”，可把两个名字都填进模型名（逗号分隔）。"
    )
    // 损坏提示必须排在列表之前：这里的"看不到配置"和"从没配过"是两件事，
    // 不加区分的话用户会照着下面的引导新建配置，把损坏的原串（含 Key）直接覆盖掉
    if (prefs.aiProfilesCorrupt.value) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin, vertical = 6.dp),
            shape = M3Shape.largeIncreased,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("模型配置数据损坏，未覆盖", style = MaterialTheme.typography.titleSmall)
                Text(
                    "已保存的模型配置无法解析（可能写入中断或被外部改动），启动器**没有动这份数据**，" +
                        "所以暂时看不到那些配置。原文会在你保存新配置或点下面按钮时另存为损坏备份，不会丢掉。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { viewModel.clearCorruptAiProfiles() }) {
                    Text("清除损坏数据并重新开始")
                }
            }
        }
    }
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
    if (profiles.isEmpty() && !prefs.aiProfilesCorrupt.value) {
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
        "提示：模型名留空回退 DeepSeek 默认（deepseek-flash）；旧名 deepseek-chat / " +
            "deepseek-reasoner 已于 2026-07-24 停用，保存时自动归一到 deepseek-flash。\n" +
            "「深度思考」：DeepSeek 走原生 thinking 参数；其它服务商若思考=换模型名，" +
            "填第二个模型名即可（如 qwen3 的 thinking 版）；要显式参数（如 {\"enable_thinking\":true}）" +
            "写进附加参数。删除当前使用的配置会自动回退到剩余第一个。"
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

// ── 联网搜索 ──

@Composable
private fun SearchSection(viewModel: AppViewModel) {
    val prefs = viewModel.uiPrefs
    var providerId by remember { mutableStateOf(prefs.aiSearchProviderId.value) }
    // 输入框初值 = **当前源自己的** Key（每个源分槽存，换源后不会带过来别家的 Key）
    var searchKey by remember { mutableStateOf(prefs.aiSearchKey(prefs.aiSearchProviderId.value)) }
    var keySaved by remember { mutableStateOf(false) }
    val provider = AiSearch.Provider.byId(providerId)

    SectionTitle("联网搜索")
    SectionNote(
        "开启对话页的「联网」后，AI 会先生成搜索词、把结果带入回答；搜索失败不影响回答。" +
            "选一个搜索源（每个源的 Key 分开保存，换源后要为当前源单独填写）："
    )
    Spacer(Modifier.height(8.dp))

    AiSearch.Provider.entries.forEach { prov ->
        val selected = prov.id == providerId
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin, vertical = 4.dp),
            shape = M3Shape.largeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            onClick = {
                providerId = prov.id
                // 换源立即生效；Key 输入框回读**该源自己的**已保存值（没填过就是空）。
                // 绝不能把上一个源的 Key 原样带过来 —— 那等于把 A 家的密钥发给 B 家。
                viewModel.setAiSearch(prov.id, prefs.aiSearchKey(prov.id))
                searchKey = prefs.aiSearchKey(prov.id)
                keySaved = false
            },
        ) {
            Row(
                Modifier.padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Icon(
                    Icons.Filled.TravelExplore,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .width(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(prov.displayName, style = MaterialTheme.typography.titleSmall)
                        if (!prov.needsKey) {
                            M3EStatusChip(
                                text = "免 Key",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        prov.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (provider.needsKey) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchKey,
                onValueChange = {
                    searchKey = it
                    keySaved = false
                },
                label = { Text("${provider.displayName} API Key") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(onClick = {
                viewModel.setAiSearch(providerId, searchKey)
                keySaved = true
            }) {
                Text("保存")
            }
        }
        LaunchedEffect(keySaved) {
            if (keySaved) {
                delay(2000)
                keySaved = false
            }
        }
        if (keySaved) {
            Text(
                "已保存 ✓",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = M3Spacing.screenMargin),
            )
        }
    } else {
        SectionNote("该搜索源无需 Key、不注册任何服务：由手机直接加载搜索结果页解析。")
    }

    // 本机浏览器源：引导授予悬浮窗权限，让 WebView 挂真窗口（渲染器全优先级、不被 ROM 冻结）。
    //
    // 授权状态存在系统侧，不是 Compose 状态：从系统设置页返回时必须重新读一次，
    // 否则用户授权完回来卡片还写着"建议授予" —— 看起来就是没生效（这正是它以前的样子）。
    var overlayGranted by remember { mutableStateOf(viewModel.canDrawOverlays()) }
    var overlayError by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) overlayGranted = viewModel.canDrawOverlays()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (provider.id == AiSearch.Provider.BING_LOCAL.id && !overlayGranted) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin, vertical = 4.dp),
            shape = M3Shape.largeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            onClick = { overlayError = viewModel.requestOverlayPermission() },
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text("建议授予悬浮窗权限", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "「显示在其他应用上层」只用于本机搜索：搜索结果页由一个 1 像素、不可触摸、" +
                            "不可聚焦的隐藏窗口加载，拿到真窗口后系统才不会把它的渲染限流（否则页面加载不出来）。" +
                            "窗口看不见也点不到，也不加载任何本应用之外的可执行内容；" +
                            "不授予也能用（自动回退无头模式），只是成功率低一些。点此前往系统设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 拉不起系统设置页时给出可照做的说明，而不是点了毫无反应
                    overlayError?.let { msg ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
