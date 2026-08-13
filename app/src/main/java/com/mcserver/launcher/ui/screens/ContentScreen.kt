package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.content.ContentKind
import com.mcserver.launcher.core.content.ContentItem
import com.mcserver.launcher.core.content.ModrinthApi
import com.mcserver.launcher.core.content.VersionKind
import com.mcserver.launcher.ui.AppViewModel
import com.mcserver.launcher.ui.design.GlassCard
import com.mcserver.launcher.ui.design.GradientButton
import com.mcserver.launcher.ui.theme.LocalKazeTokens
import kotlinx.coroutines.launch

/** 内容中心（对标 Fold Craft Launcher）：分类浏览 + 版本类型过滤 + 搜索 + 一键安装 */
@Composable
fun ContentScreen(vm: AppViewModel) {
    val tokens = LocalKazeTokens.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(ContentKind.MOD) }
    var versionKind by remember { mutableStateOf(VersionKind.ALL) }
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var firstLoad by remember { mutableStateOf(true) }

    fun load(reset: Boolean) {
        scope.launch {
            loading = true
            val nextPage = if (reset) 0 else page + 1
            val result = ModrinthApi.search(kind, versionKind, query, offset = nextPage * 20)
            items = if (reset) result else items + result
            page = nextPage
            loading = false
            firstLoad = false
        }
    }

    LaunchedEffect(kind, versionKind) {
        load(reset = true)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text("内容中心", style = MaterialTheme.typography.headlineMedium, color = tokens.onBackground)
        Text(
            "模组 · 插件 · 资源包 · 数据包 · 整合包（Modrinth）",
            fontSize = 12.sp,
            color = tokens.onBackground.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(14.dp))

        // ── 搜索框 ──
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(tokens.cornerMedium))
                .then(Modifier.glassBg(tokens))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔍", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = tokens.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(tokens.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            "搜索 ${kind.label}…",
                            color = tokens.onSurface.copy(alpha = 0.35f),
                            fontSize = 14.sp,
                        )
                    }
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                Text(
                    "✕",
                    color = tokens.onSurface.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { query = "" },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 分类 tabs ──
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ContentKind.entries.toList()) { k ->
                KindChip(
                    label = k.label,
                    selected = k == kind,
                    tokens = tokens,
                    onClick = { if (k != kind) { kind = k; items = emptyList(); page = 0 } },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── 版本类型 chips ──
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(VersionKind.entries.toList()) { vk ->
                VersionChip(
                    label = vk.label,
                    selected = vk == versionKind,
                    tokens = tokens,
                    onClick = { if (vk != versionKind) { versionKind = vk; items = emptyList(); page = 0 } },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 列表 ──
        if (firstLoad && loading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("加载中…", color = tokens.onSurface.copy(alpha = 0.5f), fontSize = 13.sp)
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("没有找到内容，换个分类或关键词试试", color = tokens.onSurface.copy(alpha = 0.5f), fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.slug }) { item ->
                    ContentRow(item = item, tokens = tokens, onInstall = { vm.installContent(item) })
                }
                item {
                    if (loading) {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            Text("加载更多…", color = tokens.onSurface.copy(alpha = 0.4f), fontSize = 12.sp)
                        }
                    } else {
                        GradientButton(
                            text = "加载更多",
                            onClick = { load(reset = false) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun KindChip(
    label: String,
    selected: Boolean,
    tokens: com.mcserver.launcher.ui.theme.StyleTokens,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) Brush.linearGradient(listOf(tokens.primary, tokens.secondary))
                else SolidColor(tokens.surfaceVariant)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.White else tokens.onSurface.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun VersionChip(
    label: String,
    selected: Boolean,
    tokens: com.mcserver.launcher.ui.theme.StyleTokens,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (selected) {
                    Modifier.background(tokens.primary.copy(alpha = 0.18f))
                } else {
                    Modifier.background(Color.Transparent)
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) tokens.primary else tokens.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ContentRow(
    item: ContentItem,
    tokens: com.mcserver.launcher.ui.theme.StyleTokens,
    onInstall: () -> Unit,
) {
    GlassCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 首字母色块图标
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(tokens.cornerSmall))
                    .background(
                        Brush.linearGradient(listOf(tokens.primary, tokens.accent))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.title.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.onSurface,
                    maxLines = 1,
                )
                Text(
                    "${item.author} · ${formatDownloads(item.downloads)}",
                    fontSize = 11.sp,
                    color = tokens.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                )
                if (item.description.isNotBlank()) {
                    Text(
                        item.description,
                        fontSize = 11.sp,
                        color = tokens.onSurface.copy(alpha = 0.6f),
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(tokens.primary.copy(alpha = 0.15f))
                    .clickable { onInstall() }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text("安装", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tokens.primary)
            }
        }
    }
}

private fun formatDownloads(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format("%.1fK", n / 1_000f)
    else -> "$n"
}

private fun Modifier.glassBg(tokens: com.mcserver.launcher.ui.theme.StyleTokens): Modifier =
    com.mcserver.launcher.ui.theme.GlassEffects.glassSurface(this, tokens, tokens.cornerMedium, elevation = 6.dp)