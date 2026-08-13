package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.download.DownloadManager
import com.mcserver.launcher.core.instance.CoreType
import com.mcserver.launcher.data.DownloadState
import com.mcserver.launcher.ui.AppViewModel
import com.mcserver.launcher.ui.design.GlassCard
import com.mcserver.launcher.ui.design.GradientButton
import com.mcserver.launcher.ui.theme.LocalKazeTokens

@Composable
fun DownloadScreen(
    vm: AppViewModel,
    onOpenInstance: () -> Unit,
) {
    val tokens = LocalKazeTokens.current
    val dlState by DownloadManager.state.collectAsState()

    LaunchedEffect(vm.coreType.value) {
        vm.loadVersions(vm.coreType.value)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text("核心市场", style = MaterialTheme.typography.headlineMedium, color = tokens.onBackground)
        Text(
            "下载服务端核心，一键创建实例",
            fontSize = 12.sp,
            color = tokens.onBackground.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(16.dp))

        // ── 类型横向大卡片流 ──
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(CoreType.entries) { type ->
                CoreTypeCard(
                    type = type,
                    selected = type == vm.coreType.value,
                    onClick = { vm.loadVersions(type) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 版本竖排单选 ──
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (vm.versionsLoading.value) {
                Text(
                    "加载版本列表中…",
                    fontSize = 13.sp,
                    color = tokens.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (vm.versions.value.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (vm.coreType.value in listOf(CoreType.SPIGOT, CoreType.FORGE)) "该核心需自备 jar" else "暂无可用版本",
                        fontSize = 14.sp,
                        color = tokens.onSurface.copy(alpha = 0.6f),
                    )
                    if (vm.coreType.value in listOf(CoreType.SPIGOT, CoreType.FORGE)) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Spigot 需 BuildTools 构建；Forge 请从官网下载安装器。\n下载后可在实例页填入文件名。",
                            fontSize = 12.sp,
                            color = tokens.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(vm.versions.value) { ver ->
                        VersionRow(
                            version = ver,
                            selected = ver == vm.mcVersion.value,
                            onClick = { vm.mcVersion.value = ver },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 下载按钮 / 进度 ──
        when (val st = dlState) {
            is DownloadState.Progress -> {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "下载中",
                            fontSize = 13.sp,
                            color = tokens.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        val pct = if (st.totalBytes > 0) st.doneBytes * 100 / st.totalBytes else 0
                        Text(
                            "$pct%",
                            fontSize = 13.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = tokens.primary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (st.totalBytes > 0) st.doneBytes.toFloat() / st.totalBytes else 0f
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = tokens.primary,
                        trackColor = tokens.surfaceVariant,
                    )
                }
            }

            is DownloadState.Done -> {
                GlassCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("✓ 下载完成", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.accent)
                            Text(
                                st.filePath.substringAfterLast('/'),
                                fontSize = 12.sp,
                                color = tokens.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        GradientButton(
                            text = "一键创建实例",
                            onClick = {
                                val ok = vm.createInstanceFromDownload(st.filePath.substringAfterLast('/'))
                                if (ok) onOpenInstance()
                            },
                        )
                    }
                }
            }

            else -> {
                GradientButton(
                    text = if (vm.coreType.value in listOf(CoreType.SPIGOT, CoreType.FORGE)) "需自备 jar（去设置导入）" else "下载 ${vm.coreType.value.label} ${vm.mcVersion.value}",
                    enabled = vm.mcVersion.value.isNotBlank() && vm.coreType.value !in listOf(CoreType.SPIGOT, CoreType.FORGE),
                    onClick = { vm.downloadSelectedCore() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CoreTypeCard(
    type: CoreType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalKazeTokens.current
    Box(
        Modifier
            .width(132.dp)
            .height(104.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerMedium))
            .then(
                if (selected) {
                    Modifier.glassBgSel(tokens)
                } else {
                    Modifier.glassBg(tokens)
                }
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Column {
            // 渐变首字母徽标
            Box(
                Modifier
                    .size(26.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerSmall))
                    .background(Brush.linearGradient(listOf(tokens.primary, tokens.accent))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    type.label.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                type.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) tokens.primary else tokens.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                type.desc,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = tokens.onSurface.copy(alpha = 0.5f),
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun VersionRow(
    version: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalKazeTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerSmall))
            .then(if (selected) Modifier.glassBgSel(tokens) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            version,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = tokens.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(tokens.primary, tokens.secondary)))
            )
        }
    }
}

private fun Modifier.glassBg(tokens: com.mcserver.launcher.ui.theme.StyleTokens): Modifier =
    com.mcserver.launcher.ui.theme.GlassEffects.glassSurface(this, tokens, tokens.cornerMedium, elevation = 6.dp)

private fun Modifier.glassBgSel(tokens: com.mcserver.launcher.ui.theme.StyleTokens): Modifier =
    this
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerMedium))
        .background(tokens.primary.copy(alpha = 0.14f))