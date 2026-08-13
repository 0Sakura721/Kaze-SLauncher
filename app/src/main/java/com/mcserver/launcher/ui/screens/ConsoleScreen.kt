package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.engine.ServerEngine
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.ui.AppViewModel
import com.mcserver.launcher.ui.theme.LocalKazeTokens

@Composable
fun ConsoleScreen(vm: AppViewModel) {
    val tokens = LocalKazeTokens.current
    val state by ServerEngine.state.collectAsState()
    val inst = vm.currentInstance()

    // 日志：回放历史 + 订阅新行
    val lines = remember { mutableStateListOf<String>().apply { addAll(ServerEngine.logHistory()) } }
    LaunchedEffect(Unit) {
        ServerEngine.logs.collect { lines.add(it) }
    }

    var input by remember { mutableStateOf(TextFieldValue("")) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "控制台",
                    style = MaterialTheme.typography.headlineMedium,
                    color = tokens.onBackground,
                )
                Text(
                    inst?.name ?: "无实例",
                    fontSize = 12.sp,
                    color = tokens.onBackground.copy(alpha = 0.5f),
                )
            }
            // 运行状态徽章
            Box(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(
                        when (state) {
                            is ServerState.Running -> tokens.accent.copy(alpha = 0.16f)
                            is ServerState.Starting -> tokens.primary.copy(alpha = 0.16f)
                            is ServerState.Crashed -> Color(0xFFE53935).copy(alpha = 0.16f)
                            else -> tokens.surfaceVariant
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    when (state) {
                        is ServerState.Running -> "● 运行中"
                        is ServerState.Starting -> "● 启动中"
                        is ServerState.Stopping -> "● 停止中"
                        is ServerState.Crashed -> "● 已崩溃"
                        else -> "○ 已停止"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (state) {
                        is ServerState.Running -> tokens.accent
                        is ServerState.Starting -> tokens.primary
                        is ServerState.Crashed -> Color(0xFFE53935)
                        else -> tokens.onSurface.copy(alpha = 0.55f)
                    },
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── 终端窗 ──
        val termBg = if (tokens.glassEnabled) Color(0xE60D1014) else Color(0xFF0D1014)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerLarge))
                .background(termBg)
        ) {
            Column(Modifier.fillMaxSize()) {
                // 窗框条
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(Color(0xFFFF5F57), Color(0xFFFFBD2E), Color(0xFF28C840)).forEach {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(it)
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        inst?.name ?: "kaze-console",
                        fontSize = 12.sp,
                        color = Color(0xFF9AA0AA),
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "清空",
                        fontSize = 11.sp,
                        color = Color(0xFF9AA0AA),
                        modifier = Modifier.clickable { lines.clear() },
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )
                // 日志区（reverseLayout：新行自然置顶）
                if (lines.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Text(
                            if (state is ServerState.Running) "等待输出…" else "服务端未运行，点击启动后这里会显示日志",
                            fontSize = 13.sp,
                            color = Color(0xFF5A6069),
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        reverseLayout = true,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    ) {
                        items(lines) { line ->
                            Text(
                                line,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                color = lineColor(line),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── 悬浮命令胶囊 + 快捷命令 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .then(
                        Modifier.glassBg(tokens, 50.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        textStyle = TextStyle(color = tokens.onSurface, fontSize = 14.sp),
                        cursorBrush = Brush.verticalGradient(listOf(tokens.primary, tokens.secondary)),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (input.text.isEmpty()) {
                                Text("输入命令，如 stop / list / op 玩家名", fontSize = 13.sp, color = tokens.onSurface.copy(alpha = 0.35f))
                            }
                            inner()
                        },
                    )
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "发送",
                        tint = if (state is ServerState.Running) tokens.primary else tokens.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(enabled = state is ServerState.Running) {
                                val cmd = input.text.trim()
                                if (cmd.isNotEmpty()) {
                                    vm.sendCommand(cmd)
                                    input = TextFieldValue("")
                                }
                            },
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // 快捷命令纵列
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("stop", "list", "tps").forEach { cmd ->
                    Box(
                        Modifier
                            .clip(RoundedCircle)
                            .background(tokens.surfaceVariant)
                            .clickable { vm.sendCommand(cmd) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(cmd, fontSize = 11.sp, color = tokens.onSurface.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

private fun lineColor(line: String): Color {
    val l = line.lowercase()
    return when {
        l.contains("error") || l.contains("exception") || l.contains("fail") && !l.contains("failed to bind") -> Color(0xFFFF6B6B)
        l.contains("warn") -> Color(0xFFFFB84D)
        l.contains("[引擎]") -> Color(0xFF0AC8B9)
        l.contains("done") || l.contains("started") || l.contains("joining") -> Color(0xFF52D273)
        else -> Color(0xFFB8BEC8)
    }
}

private val RoundedCircle = androidx.compose.foundation.shape.RoundedCornerShape(50)

private fun Modifier.glassBg(tokens: com.mcserver.launcher.ui.theme.StyleTokens, corner: androidx.compose.ui.unit.Dp): Modifier =
    com.mcserver.launcher.ui.theme.GlassEffects.glassSurface(this, tokens, corner, elevation = 8.dp)