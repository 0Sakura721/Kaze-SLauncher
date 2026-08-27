package com.kaze.newage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale

/**
 * 插件/模组管理页：Modrinth 搜索下载 + 已安装列表（启用/禁用/删除）。
 * 数据源：Modrinth v2 API（开放 API）；文件惯例：plugins/、mods/、*.jar.disabled。
 * 版式：FCL 列表风——搜索行带项目图标 + 下载数；已安装行带图标 + 开关；内容直铺背景。
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
            Text(
                if (kind == AddonKind.PLUGIN) "当前核心（${instance.coreType.displayName}）不支持插件。插件适用于 Paper / Purpur / Spigot 类服务端。"
                else "当前核心（${instance.coreType.displayName}）不支持模组。模组适用于 Fabric / Forge / NeoForge 类服务端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 搜索下载 ──
        Text("下载${kindLabel}（Modrinth）", style = MaterialTheme.typography.titleMedium)
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
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        installState.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (installState.running) {
            LinearProgressIndicator(
                progress = { installState.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            installState.message.let {
                if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (installState.done) {
            Text(
                installState.message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // 搜索结果（FCL 列表行：图标 + 标题 + 描述 + 下载数 + 安装）
        results.forEach { hit ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteIcon(hit.icon_url, 42.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(hit.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        hit.description.ifBlank { "（无描述）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    Text(
                        "${formatDownloads(hit.downloads)} 次下载",
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
            )
        }
        HorizontalDivider()

        // ── 已安装 ──
        Text("已安装（${installed.size}）", style = MaterialTheme.typography.titleMedium)
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 文件图标位：启用=主色底，禁用=灰底
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        file.name.removeSuffix(".disabled").take(1).uppercase(Locale.US),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            )
        }
    }
}

/** 远程图标内存缓存（LRU；Modrinth 图标重复进入页面不重新下载） */
private val remoteIconCache = androidx.collection.LruCache<String, ImageBitmap>(48)

/** 远程图标（Modrinth icon_url）异步加载；失败/空显示灰色占位块 */
@Composable
private fun RemoteIcon(url: String, size: androidx.compose.ui.unit.Dp) {
    var bmp by remember(url) { mutableStateOf(remoteIconCache.get(url)) }
    LaunchedEffect(url) {
        if (url.isBlank() || bmp != null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection().apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                conn.getInputStream().use { ins ->
                    // 降采样两段式：先读边界，再按显示尺寸（~96px 上限）取 inSampleSize，
                    // 避免 512px 图标整图解码占内存
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeStream(ins, null, bounds)
                    val target = 96
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= target ||
                        bounds.outHeight / (sample * 2) >= target
                    ) sample *= 2
                    // 边界读完后流已消费，重新开连接拿完整数据
                    val conn2 = URL(url).openConnection().apply {
                        connectTimeout = 6000
                        readTimeout = 6000
                    }
                    conn2.getInputStream().use { ins2 ->
                        val opts = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = sample
                        }
                        android.graphics.BitmapFactory.decodeStream(ins2, null, opts)
                            ?.asImageBitmap()
                    }
                }?.also { remoteIconCache.put(url, it) }
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val current = bmp
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 占位：默认方块色 + 首字母
            Box(
                Modifier.fillMaxSize().background(Color(0xFF7CB342).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("M", style = MaterialTheme.typography.titleMedium, color = Color(0xFF4E7A2A))
            }
        }
    }
}

/** 下载数格式化：1.2K / 3.4M */
private fun formatDownloads(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000f)
    else -> n.toString()
}
