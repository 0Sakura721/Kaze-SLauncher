package com.mcserver.launcher.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.mcserver.launcher.core.backup.BackupManager
import com.mcserver.launcher.core.engine.JreManager
import com.mcserver.launcher.core.engine.JreStatus
import com.mcserver.launcher.data.AppPaths
import com.mcserver.launcher.data.SettingsStore
import com.mcserver.launcher.ui.AppViewModel
import com.mcserver.launcher.ui.design.GradientButton
import com.mcserver.launcher.ui.design.SegmentRail
import com.mcserver.launcher.ui.theme.LocalKazeTokens
import com.mcserver.launcher.ui.theme.StyleKeys
import com.mcserver.launcher.ui.theme.Styles
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val tokens = LocalKazeTokens.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val styleKey by SettingsStore.themeStyle.collectAsState(initial = StyleKeys.LIQUID)
    val themeMode by SettingsStore.themeMode.collectAsState(initial = 0)
    val customColor by SettingsStore.customColor.collectAsState(initial = 0)
    val memPreset by SettingsStore.memoryPresetMb.collectAsState(initial = 2048)
    val jreStatus by JreManager.status.collectAsState()
    val jreProgress by JreManager.progress.collectAsState()
    val jreVersion by JreManager.versionText.collectAsState()
    val backups = remember { mutableStateOf(BackupManager.list()) }
    var showCustomMem by remember { mutableStateOf(false) }
    var customMemInput by remember { mutableStateOf("2048") }

    // SAF 目录选择（导入 JRE）
    val jrePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { u ->
            scope.launch {
                try {
                    val doc = DocumentFile.fromTreeUri(ctx, u)
                    val tmp = File(AppPaths.runtimeDir, "jre-import-tmp")
                    tmp.deleteRecursively()
                    tmp.mkdirs()
                    doc?.let { copyDocTree(ctx, it, tmp) }
                    val r = JreManager.importFromDir(tmp)
                    tmp.deleteRecursively()
                    vm.showToast(if (r.isSuccess) "JRE 导入成功" else "导入失败: ${r.exceptionOrNull()?.message}")
                } catch (e: Exception) {
                    vm.showToast("导入失败: ${e.message}")
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(22.dp))
        Text("设置", style = MaterialTheme.typography.headlineMedium, color = tokens.onBackground)
        Text(
            "风格 · 环境 · 数据",
            fontSize = 12.sp,
            color = tokens.onBackground.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(18.dp))

        // ── 风格选择器（交错色卡） ──
        Text("外观风格", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.onSurface)
        Spacer(Modifier.height(10.dp))
        StyleKeys.ALL.forEachIndexed { i, key ->
            val sel = key == styleKey
            Row(
                Modifier
                    .fillMaxWidth()
                    .offset(x = (i % 2 * 14).dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerMedium))
                    .then(Modifier.glassBg(tokens))
                    .clickable { scope.launch { SettingsStore.setThemeStyle(key) } }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 配色小样
                val sample = Styles.forKey(key, false)
                Row {
                    listOf(sample.primary, sample.secondary, sample.accent).forEach { c ->
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(c)
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Styles.labelOf(key),
                        fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        color = if (sel) tokens.primary else tokens.onSurface,
                    )
                }
                if (sel) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(tokens.primary, tokens.secondary)))
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))

        // ── 主题模式 ──
        Text("主题模式", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.onSurface)
        Spacer(Modifier.height(8.dp))
        SegmentRail(
            options = listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2),
            selected = themeMode,
            onSelect = { scope.launch { SettingsStore.setThemeMode(it) } },
        )

        Spacer(Modifier.height(16.dp))

        // ── 自定义颜色 ──
        Text("自定义颜色", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.onSurface)
        Text(
            "选择一个主色，整体配色自动生成",
            fontSize = 11.sp,
            color = tokens.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 默认（不自定义）
            ColorDot(
                color = Color.Transparent,
                label = "默认",
                selected = customColor == 0,
                onClick = { scope.launch { SettingsStore.setCustomColor(0) } },
            )
            com.mcserver.launcher.ui.theme.Styles.CustomPresets.forEach { c ->
                ColorDot(
                    color = c,
                    label = null,
                    selected = customColor == c.toArgb(),
                    onClick = { scope.launch { SettingsStore.setCustomColor(c.toArgb()) } },
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // ── 内置 Linux 环境（已内置 APK，一键部署） ──
        Text("内置 Linux 环境", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.onSurface)
        Text(
            "proot + Alpine 已内置，部署即可用 · JDK 通过包管理在线安装",
            fontSize = 11.sp,
            color = tokens.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(10.dp))
        val linuxStatus by com.mcserver.launcher.core.linux.LinuxEnv.status.collectAsState()
        val linuxProgress by com.mcserver.launcher.core.linux.LinuxEnv.progress.collectAsState()
        val linuxDetail by com.mcserver.launcher.core.linux.LinuxEnv.detail.collectAsState()
        Row(
            Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerMedium))
                .then(Modifier.glassBg(tokens))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (linuxStatus) {
                        com.mcserver.launcher.core.linux.LinuxStatus.NONE -> "未部署"
                        com.mcserver.launcher.core.linux.LinuxStatus.UNPACKING -> "部署中 ${(linuxProgress * 100).toInt()}%"
                        com.mcserver.launcher.core.linux.LinuxStatus.READY -> "已就绪"
                        com.mcserver.launcher.core.linux.LinuxStatus.ERROR -> "部署失败"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.onSurface,
                )
                Text(
                    if (linuxDetail.isNotBlank()) linuxDetail
                    else "内置约 4.5MB，解包到应用目录（秒级）",
                    fontSize = 11.sp,
                    color = tokens.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                )
                if (linuxStatus == com.mcserver.launcher.core.linux.LinuxStatus.UNPACKING) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { linuxProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape),
                        color = tokens.primary,
                        trackColor = tokens.surfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        GradientButton(
            text = if (linuxStatus == com.mcserver.launcher.core.linux.LinuxStatus.READY) "重新部署" else "一键部署",
            enabled = linuxStatus != com.mcserver.launcher.core.linux.LinuxStatus.UNPACKING,
            onClick = { vm.installLinuxEnv() },
            modifier = Modifier.fillMaxWidth(),
        )

        // ── JDK 多版本管理 ──
        Spacer(Modifier.height(14.dp))
        Text("JDK 多版本管理", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.onSurface)
        Text(
            "1.8-1.12 用 JDK8 · 1.13-1.16 用 JDK11 · 1.17-1.20 用 JDK17 · 1.20.5+ 用 JDK21",
            fontSize = 11.sp,
            color = tokens.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(8.dp))
        val jdks by com.mcserver.launcher.core.linux.JdkManager.jdks.collectAsState()
        jdks.forEach { info ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerSmall))
                    .then(Modifier.glassBg(tokens))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(
                            if (info.installed) tokens.accent else tokens.onSurface.copy(alpha = 0.3f)
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "JDK ${info.feature}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (info.busy) {
                    Text("处理中…", fontSize = 12.sp, color = tokens.primary)
                } else if (info.installed) {
                    Text(
                        "卸载",
                        fontSize = 12.sp,
                        color = tokens.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { vm.uninstallJdk(info.feature) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "装 JRE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(tokens.primary.copy(alpha = 0.14f))
                                .clickable { vm.installJdk(info.feature, jdk = false) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                        Text(
                            "装 JDK",
                            fontSize = 12.sp,
                            color = tokens.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(tokens.surfaceVariant)
                                .clickable { vm.installJdk(info.feature, jdk = true) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(18.dp))

        // ── JRE 管理（回退环境） ──
        Text("Java 运行时（JRE · 回退）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.onSurface)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.cornerMedium))
                .then(Modifier.glassBg(tokens))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JreBadge(jreStatus, tokens)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (jreStatus) {
                        JreStatus.NONE -> "未安装"
                        JreStatus.DOWNLOADING -> "下载中 ${(jreProgress * 100).toInt()}%"
                        JreStatus.EXTRACTING -> "解压中…"
                        JreStatus.READY -> "已就绪"
                        JreStatus.ERROR -> "安装失败"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.onSurface,
                )
                Text(
                    jreVersion ?: "Java 21 · ARM64（约 45MB）",
                    fontSize = 11.sp,
                    color = tokens.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                )
                if (jreStatus == JreStatus.DOWNLOADING || jreStatus == JreStatus.EXTRACTING) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { jreProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape),
                        color = tokens.primary,
                        trackColor = tokens.surfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton(
                text = "在线下载",
                enabled = jreStatus == JreStatus.NONE || jreStatus == JreStatus.ERROR,
                onClick = {
                    scope.launch {
                        val r = JreManager.downloadAndInstall()
                        vm.showToast(if (r.isSuccess) "JRE 安装完成" else "下载失败: ${r.exceptionOrNull()?.message}")
                    }
                },
                modifier = Modifier.weight(1f),
            )
            GradientButton(
                text = "本地导入",
                enabled = jreStatus == JreStatus.NONE || jreStatus == JreStatus.READY,
                onClick = { jrePicker.launch(null) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(18.dp))

        // ── 默认内存（FCL 式档位） ──
        Text("默认内存预设", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.onSurface)
        Text(
            "自动档按设备内存推荐 · 支持自定义 MB",
            fontSize = 11.sp,
            color = tokens.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(10.dp))
        val memChips = listOf(
            "自动" to SettingsStore.MEM_AUTO,
            "512M" to 512,
            "1G" to 1024,
            "2G" to 2048,
            "3G" to 3072,
            "4G" to 4096,
            "6G" to 6144,
            "8G" to 8192,
            "自定" to 0,
        )
        val memCustom = memPreset != SettingsStore.MEM_AUTO && memChips.none { it.second == memPreset }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            memChips.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value) ->
                        val selected = if (value == 0) memCustom else memPreset == value
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                .background(
                                    if (selected) Brush.linearGradient(listOf(tokens.primary, tokens.secondary))
                                    else SolidColor(tokens.surfaceVariant)
                                )
                                .clickable {
                                    scope.launch {
                                        if (value == 0) {
                                            // 打开自定义输入
                                            customMemInput = memPreset.takeIf { it > 0 }?.toString() ?: "2048"
                                            showCustomMem = true
                                        } else {
                                            SettingsStore.setMemoryPreset(value)
                                        }
                                    }
                                }
                                .padding(vertical = 9.dp),
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
                    // 补齐剩余空位
                    repeat(5 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── 备份区 ──
        Text("备份", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tokens.onSurface)
        Spacer(Modifier.height(10.dp))
        GradientButton(
            text = "备份当前实例",
            onClick = {
                vm.backupCurrent()
                backups.value = BackupManager.list()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        val list = backups.value
        if (list.isEmpty()) {
            Text(
                "暂无备份",
                fontSize = 12.sp,
                color = tokens.onSurface.copy(alpha = 0.4f),
            )
        } else {
            list.take(10).forEach { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.file.name,
                        fontSize = 12.sp,
                        color = tokens.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.time)) + " · ${entry.sizeMb}MB",
                        fontSize = 11.sp,
                        color = tokens.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Kaze SLauncher 2.0 · 由 Operit 构建\nMinecraft 是 Mojang Studios 的注册商标",
            fontSize = 11.sp,
            color = tokens.onSurface.copy(alpha = 0.35f),
        )
        Spacer(Modifier.height(24.dp))
    }

    // ── 自定义内存弹窗 ──
    if (showCustomMem) {
        AlertDialog(
            onDismissRequest = { showCustomMem = false },
            containerColor = tokens.surface,
            title = {
                Text("自定义内存", color = tokens.onSurface, fontWeight = FontWeight.Bold)
            },
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
                        scope.launch { SettingsStore.setMemoryPreset(mb) }
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

@Composable
private fun JreBadge(status: JreStatus, tokens: com.mcserver.launcher.ui.theme.StyleTokens) {
    val color = when (status) {
        JreStatus.READY -> tokens.accent
        JreStatus.DOWNLOADING, JreStatus.EXTRACTING -> tokens.primary
        JreStatus.ERROR -> Color(0xFFE53935)
        else -> tokens.onSurface.copy(alpha = 0.3f)
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

private fun copyDocTree(ctx: Context, doc: DocumentFile, dest: File) {
    if (doc.isDirectory) {
        dest.mkdirs()
        doc.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            copyDocTree(ctx, child, File(dest, name))
        }
    } else {
        val name = doc.name ?: return
        ctx.contentResolver.openInputStream(doc.uri)?.use { ins ->
            FileOutputStream(File(dest, name)).use { out -> ins.copyTo(out) }
        }
    }
}

private fun Modifier.glassBg(tokens: com.mcserver.launcher.ui.theme.StyleTokens): Modifier =
    com.mcserver.launcher.ui.theme.GlassEffects.glassSurface(this, tokens, tokens.cornerMedium, elevation = 6.dp)

@Composable
private fun ColorDot(
    color: Color,
    label: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalKazeTokens.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .then(
                    if (color == Color.Transparent) {
                        Modifier.background(Brush.linearGradient(listOf(Color(0xFF7C6FF0), Color(0xFF2F9BF4), Color(0xFF22B8A0))))
                    } else {
                        Modifier.background(color)
                    }
                )
                .then(
                    if (selected) {
                        Modifier.border(
                            width = androidx.compose.ui.unit.Dp(2f),
                            color = tokens.onSurface,
                            shape = CircleShape,
                        )
                    } else {
                        Modifier.border(
                            width = androidx.compose.ui.unit.Dp(1f),
                            color = tokens.outline,
                            shape = CircleShape,
                        )
                    }
                )
                .clickable { onClick() }
        )
        if (label != null) {
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = 10.sp, color = tokens.onSurface.copy(alpha = 0.6f))
        }
    }
}