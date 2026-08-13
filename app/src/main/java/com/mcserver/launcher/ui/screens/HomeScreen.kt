package com.mcserver.launcher.ui.screens

import android.app.ActivityManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.engine.ServerEngine
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.ui.AppViewModel
import com.mcserver.launcher.ui.design.GlassCard
import com.mcserver.launcher.ui.design.LiquidCapsule
import com.mcserver.launcher.ui.design.MetricRing
import com.mcserver.launcher.ui.design.RunButton
import com.mcserver.launcher.ui.design.stateLabel
import com.mcserver.launcher.ui.theme.LocalKazeTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenInstances: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tokens = LocalKazeTokens.current
    val state by ServerEngine.state.collectAsState()
    val stats by ServerEngine.stats.collectAsState()
    val inst = vm.currentInstance()
    val ctx = LocalContext.current
    val totalMemMb = remember(ctx) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.totalMem / 1024 / 1024)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        // ── 大标题 + 风格快捷键 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    "Kaze",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        brush = Brush.linearGradient(listOf(tokens.primary, tokens.secondary, tokens.accent))
                    ),
                )
                Text(
                    SimpleDateFormat("M月d日 · EEEE", Locale.getDefault()).format(Date()),
                    fontSize = 12.sp,
                    color = tokens.onBackground.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .then(Modifier.glassBg(tokens))
                    .then(
                        Modifier.clickableNoIndication { onOpenSettings() }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("🎨", fontSize = 18.sp)
            }
        }

        Spacer(Modifier.height(22.dp))

        if (inst == null) {
            EmptyHome(vm, onOpenInstances)
            return@Column
        }

        // ── 环境状态卡：内置 Linux 环境 / EULA 引导 ──
        val linuxStatus by com.mcserver.launcher.core.linux.LinuxEnv.status.collectAsState()
        val eulaExists by vm.eulaExists
        val eulaAgreed by vm.eulaAgreed
        if (linuxStatus == com.mcserver.launcher.core.linux.LinuxStatus.NONE ||
            linuxStatus == com.mcserver.launcher.core.linux.LinuxStatus.ERROR ||
            (eulaExists && !eulaAgreed)
        ) {
            GlassCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    if (linuxStatus == com.mcserver.launcher.core.linux.LinuxStatus.NONE ||
                        linuxStatus == com.mcserver.launcher.core.linux.LinuxStatus.ERROR
                    ) {
                        Text(
                            "内置 Linux 环境未就绪",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.onSurface,
                        )
                        Text(
                            "proot + Alpine 已内置 APK，部署后经 apk 装 JDK 即可驱动全部 MC 服务端",
                            fontSize = 11.sp,
                            color = tokens.onSurface.copy(alpha = 0.55f),
                        )
                        Spacer(Modifier.height(8.dp))
                        com.mcserver.launcher.ui.design.GradientButton(
                            text = if (linuxStatus == com.mcserver.launcher.core.linux.LinuxStatus.ERROR) "重试安装" else "一键安装",
                            onClick = { vm.installLinuxEnv() },
                        )
                    }
                    if (eulaExists && !eulaAgreed) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Minecraft EULA 待同意",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.onSurface,
                        )
                        Text(
                            "服务端已生成 eula.txt，同意后即可正常启动",
                            fontSize = 11.sp,
                            color = tokens.onSurface.copy(alpha = 0.55f),
                        )
                        Spacer(Modifier.height(8.dp))
                        com.mcserver.launcher.ui.design.GradientButton(
                            text = "同意 EULA",
                            onClick = { vm.acceptEula() },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── 三环交叠仪表组（液态玻璃圆盘 + 错位破界） ──
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            // 大玻璃圆盘底：液态表面承载仪表
            if (tokens.glassEnabled) {
                Box(
                    Modifier
                        .size(252.dp)
                        .align(Alignment.Center)
                        .offset(y = 10.dp)
                        .clip(CircleShape)
                        .background(tokens.surface.copy(alpha = 0.30f))
                        .background(
                            Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.08f), Color.Transparent),
                                radius = 1100f,
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.05f)),
                                start = Offset.Zero,
                                end = Offset(0f, 480f),
                            ),
                            shape = CircleShape,
                        )
                )
            }
            val memPercent = if (totalMemMb > 0) stats.memMb.toFloat() / totalMemMb else 0f
            val tpsPercent = stats.tps.coerceIn(0f, 20f) / 20f
            MetricRing(
                value = stats.cpuPercent / 100f,
                label = "CPU",
                valueText = "${stats.cpuPercent.toInt()}%",
                color = tokens.primary,
                size = 108.dp,
                glassDisc = true,
                modifier = Modifier.offset(x = 6.dp, y = 30.dp),
            )
            MetricRing(
                value = memPercent,
                label = "内存",
                valueText = "${stats.memMb}MB",
                color = tokens.secondary,
                size = 108.dp,
                glassDisc = true,
                modifier = Modifier.offset(x = 116.dp, y = 88.dp),
            )
            MetricRing(
                value = tpsPercent,
                label = "TPS",
                valueText = if (stats.tps > 0) "%.1f".format(stats.tps) else "--",
                color = tokens.accent,
                size = 108.dp,
                glassDisc = true,
                modifier = Modifier.offset(x = 226.dp, y = 26.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        // ── 贯穿式液体胶囊 + 运行主按钮 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiquidCapsule(
                title = inst.name,
                subtitle = "${stateLabel(state)} · ${stats.playerCount} 人在线 · ${inst.coreType.label} ${inst.mcVersion}",
                state = state,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            RunButton(state = state, onClick = vm::toggleRun, size = 58.dp)
        }

        Spacer(Modifier.height(22.dp))

        // ── 实例横向卡片轮播 ──
        Text(
            "服务器",
            style = MaterialTheme.typography.titleMedium,
            color = tokens.onBackground.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.instances, key = { it.id }) { it2 ->
                InstanceWheelCard(
                    inst = it2,
                    selected = it2.id == vm.selectedId.value,
                    onClick = { vm.selectInstance(it2.id) },
                    onLongClick = { vm.deleteInstance(it2.id) },
                )
            }
            item {
                Box(
                    Modifier
                        .size(width = 84.dp, height = 96.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerMedium))
                        .then(Modifier.glassBg(tokens))
                        .clickableNoIndication { onOpenInstances() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "新建",
                        tint = tokens.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstanceWheelCard(
    inst: ServerInstance,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val tokens = LocalKazeTokens.current
    Box(
        Modifier
            .width(150.dp)
            .height(96.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerMedium))
            .then(Modifier.glassBg(tokens))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
    ) {
        Column {
            Text(
                inst.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) tokens.primary else tokens.onSurface,
                maxLines = 1,
            )
            Text(
                "${inst.coreType.label} · ${inst.mcVersion.ifBlank { "?" }}",
                fontSize = 11.sp,
                color = tokens.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (selected) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .align(Alignment.End)
                        .backgroundSolid(tokens.secondary)
                )
            }
        }
    }
}

@Composable
private fun EmptyHome(vm: AppViewModel, onOpenInstances: () -> Unit) {
    val tokens = LocalKazeTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .then(Modifier.glassBg(tokens)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .backgroundSolid(tokens.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = tokens.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "还没有服务器",
                style = MaterialTheme.typography.titleLarge,
                color = tokens.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "创建第一个实例，开始你的冒险之旅",
                fontSize = 13.sp,
                color = tokens.onSurface.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(18.dp))
            com.mcserver.launcher.ui.design.GradientButton(text = "创建服务器") {
                onOpenInstances()
            }
        }
    }
}

// 小工具：无波纹点击 / 玻璃背景 / 实色背景
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    )

private fun Modifier.glassBg(tokens: com.mcserver.launcher.ui.theme.StyleTokens): Modifier =
    com.mcserver.launcher.ui.theme.GlassEffects.glassSurface(this, tokens, tokens.cornerMedium, elevation = 8.dp)

private fun Modifier.backgroundSolid(color: Color): Modifier =
    this.background(color, CircleShape)