package com.kaze.newage.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.BackgroundCard
import com.kaze.newage.ui.components.CardTitleLayout

/**
 * 插件/模组管理页：Modrinth 搜索下载 + 已安装列表（启用/禁用/删除）。
 * 数据源：Modrinth v2 API（开放 API）；文件惯例：plugins/、mods/、*.jar.disabled。
 */
@Composable
fun AddonsScreen(
    viewModel: AppViewModel,
    instanceId: String,
    kind: AddonKind,
    onBack: () -> Unit,
) {
    val instances by viewModel.instances.collectAsState()
    val instance = instances.firstOrNull { it.id == instanceId }
    if (instance == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }

    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val installed = remember(instanceId, refresh) { AddonManager.installed(instance, kind) }
    val results by viewModel.addonResults.collectAsState()
    val searching by viewModel.addonSearching.collectAsState()
    val installState by viewModel.addonInstall.collectAsState()

    val kindLabel = if (kind == AddonKind.PLUGIN) "插件" else "模组"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 顶部栏 ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("${kindLabel}管理", style = MaterialTheme.typography.titleLarge)
                Text(instance.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (!AddonManager.supports(instance, kind)) {
            BackgroundCard(Modifier.fillMaxWidth()) {
                CardTitleLayout("提示") {
                    Text(
                        if (kind == AddonKind.PLUGIN) "当前核心（${instance.coreType.displayName}）不支持插件。插件适用于 Paper / Purpur / Spigot 类服务端。"
                        else "当前核心（${instance.coreType.displayName}）不支持模组。模组适用于 Fabric / Forge / NeoForge 类服务端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 搜索下载 ──
        BackgroundCard(Modifier.fillMaxWidth()) {
            CardTitleLayout("下载${kindLabel}（Modrinth）") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (kind == AddonKind.PLUGIN) "搜索插件（如 EssentialsX、LuckPerms）" else "搜索模组（如 lithium、fabric-api）") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            searched = true
                            viewModel.searchAddons(query.trim(), kind)
                        },
                        enabled = query.isNotBlank() && !searching,
                    ) {
                        Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                        Text("搜索", Modifier.padding(start = 4.dp))
                    }
                }
                if (searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                installState.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (installState.running) {
                    LinearProgressIndicator(
                        progress = { installState.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    installState.message.let {
                        if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (installState.done) {
                    Text(
                        installState.message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // 搜索结果
                results.forEach { hit ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(hit.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                hit.description.ifBlank { "（无描述）" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                            Text(
                                "${hit.downloads} 次下载",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.installAddon(instance, kind, hit)
                                refresh++
                            },
                            enabled = !installState.running,
                        ) { Text("安装") }
                    }
                }
                if (searched && !searching && results.isEmpty() && installState.error == null) {
                    Text(
                        "没有找到相关${kindLabel}，换个关键词试试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        // ── 已安装 ──
        BackgroundCard(Modifier.fillMaxWidth()) {
            CardTitleLayout("已安装（${installed.size}）") {
                if (installed.isEmpty()) {
                    Text(
                        "还没有安装${kindLabel}。搜索并安装后在此管理启用状态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                installed.forEach { file ->
                    val enabled = AddonManager.isEnabled(file)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.name.removeSuffix(".disabled"),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            Text(
                                "${file.length() / 1024} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                AddonManager.toggleEnabled(file)
                                refresh++
                            },
                        )
                        IconButton(onClick = {
                            AddonManager.delete(file)
                            refresh++
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (installed.isNotEmpty()) {
                    Text(
                        "更改启用状态或增删后，重启服务端生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
