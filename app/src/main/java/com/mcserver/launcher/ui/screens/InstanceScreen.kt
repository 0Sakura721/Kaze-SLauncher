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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.mcserver.launcher.core.instance.CoreType
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.ui.AppViewModel
import com.mcserver.launcher.ui.design.GradientButton
import com.mcserver.launcher.ui.design.InnerInput
import com.mcserver.launcher.ui.design.SideRail
import com.mcserver.launcher.ui.theme.LocalKazeTokens

@Composable
fun InstanceScreen(vm: AppViewModel, onBack: () -> Unit) {
    val tokens = LocalKazeTokens.current

    var name by remember { mutableStateOf("新服务器") }
    var coreKey by remember { mutableStateOf(CoreType.PAPER.urlKey) }
    var mcVer by remember { mutableStateOf("") }
    var coreFile by remember { mutableStateOf("") }
    var memMb by remember { mutableStateOf(com.mcserver.launcher.data.SettingsStore.MEM_AUTO) }
    var jvmArgs by remember { mutableStateOf("") }
    var eula by remember { mutableStateOf(true) }
    var showCustomMem by remember { mutableStateOf(false) }
    var customMemInput by remember { mutableStateOf("2048") }

    fun applyMem(value: Int) {
        memMb = value
        jvmArgs = when {
            value == com.mcserver.launcher.data.SettingsStore.MEM_AUTO -> ""   // 引擎按全局预设自动分配
            value % 1024 == 0 -> "-Xmx${value / 1024}G"
            else -> "-Xmx${value}M"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .then(Modifier.glassBg(tokens))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = tokens.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("创建服务器", style = MaterialTheme.typography.headlineMedium, color = tokens.onBackground)
        }
        Spacer(Modifier.height(20.dp))

        // ── 左竖排核心类型 + 右侧表单 ──
        Row {
            SideRail(
                options = CoreType.entries.map { it.label to it.urlKey },
                selected = coreKey,
                onSelect = { coreKey = it },
            )
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                InnerInput(
                    value = name,
                    onChange = { name = it },
                    hint = "服务器名称",
                    fontSize = 18,
                )
                InnerInput(
                    value = mcVer,
                    onChange = { mcVer = it },
                    hint = "MC 版本（如 1.21.1）",
                )
                InnerInput(
                    value = coreFile,
                    onChange = { coreFile = it },
                    hint = "核心文件名（如 paper-1.21.1.jar）",
                )
                Text(
                    "内存分配（FCL 式档位）",
                    fontSize = 13.sp,
                    color = tokens.onSurface.copy(alpha = 0.65f),
                )
                val memChips = listOf(
                    "自动" to com.mcserver.launcher.data.SettingsStore.MEM_AUTO,
                    "512M" to 512,
                    "1G" to 1024,
                    "2G" to 2048,
                    "3G" to 3072,
                    "4G" to 4096,
                    "6G" to 6144,
                    "8G" to 8192,
                    "自定" to 0,
                )
                val memCustom = memMb != com.mcserver.launcher.data.SettingsStore.MEM_AUTO && memChips.none { it.second == memMb }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    memChips.chunked(5).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (label, value) ->
                                val selected = if (value == 0) memCustom else memMb == value
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                        .background(
                                            if (selected) Brush.linearGradient(listOf(tokens.primary, tokens.secondary))
                                            else SolidColor(tokens.surfaceVariant)
                                        )
                                        .clickable {
                                            if (value == 0) {
                                                customMemInput = memMb.takeIf { it > 0 }?.toString() ?: "2048"
                                                showCustomMem = true
                                            } else {
                                                applyMem(value)
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        label,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) Color.White else tokens.onSurface.copy(alpha = 0.75f),
                                    )
                                }
                            }
                            repeat(5 - row.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                InnerInput(
                    value = jvmArgs,
                    onChange = { jvmArgs = it },
                    hint = "JVM 参数（留空自动用全局内存预设，如 -XX:+UseG1GC）",
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── EULA 签署条（点按翻转） ──
        Row(
            Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerMedium))
                .then(Modifier.glassBg(tokens))
                .clickable { eula = !eula }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (eula) Brush.linearGradient(listOf(tokens.primary, tokens.secondary))
                        else Brush.linearGradient(listOf(tokens.outline, tokens.outline))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (eula) "✓" else "", color = Color.White, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "我已阅读并同意 Mojang EULA",
                fontSize = 13.sp,
                fontWeight = if (eula) FontWeight.Bold else FontWeight.Normal,
                color = tokens.onSurface,
            )
        }

        Spacer(Modifier.height(18.dp))

        GradientButton(
            text = "保存并创建",
            enabled = name.isNotBlank() && eula,
            onClick = {
                val inst = ServerInstance(
                    name = name.trim(),
                    coreType = CoreType.fromKey(coreKey),
                    mcVersion = mcVer.trim(),
                    coreFileName = coreFile.trim(),
                    jvmArgs = jvmArgs.trim(),
                    agreeEula = eula,
                )
                if (vm.saveInstance(inst)) onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }

    // ── 自定义内存弹窗 ──
    if (showCustomMem) {
        AlertDialog(
            onDismissRequest = { showCustomMem = false },
            containerColor = tokens.surface,
            title = { Text("自定义内存", color = tokens.onSurface, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "输入分配内存（MB），例如 2560",
                        fontSize = 12.sp,
                        color = tokens.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customMemInput,
                        onValueChange = { v -> customMemInput = v.filter { it.isDigit() }.take(5) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = tokens.onSurface),
                        placeholder = { Text("2048", color = tokens.onSurface.copy(alpha = 0.4f)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val mb = customMemInput.toIntOrNull()
                    if (mb != null && mb >= 256) {
                        applyMem(mb)
                        showCustomMem = false
                    } else {
                        vm.showToast("请输入不小于 256 的数值")
                    }
                }) {
                    Text("确定", color = tokens.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomMem = false }) {
                    Text("取消", color = tokens.onSurface.copy(alpha = 0.6f))
                }
            },
        )
    }
}

private fun Modifier.glassBg(tokens: com.mcserver.launcher.ui.theme.StyleTokens): Modifier =
    com.mcserver.launcher.ui.theme.GlassEffects.glassSurface(this, tokens, tokens.cornerMedium, elevation = 6.dp)