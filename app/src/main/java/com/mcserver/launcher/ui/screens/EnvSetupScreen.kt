package com.mcserver.launcher.ui.screens

import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.server.DownloadItem
import com.mcserver.launcher.server.DownloadItemState
import com.mcserver.launcher.server.LinuxEnvState
import com.mcserver.launcher.server.LinuxEnvironmentManager
import com.mcserver.launcher.server.MirrorTestResult
import kotlinx.coroutines.launch

/**
 * 首屏全自动环境初始化页面。
 *
 * 4 段式流程：
 *   1. Linux 环境（proot + Ubuntu 24.04 + 可选的 JDK 版本）
 *   2. 服务器核心（Paper/Purpur/Fabric/Forge/NeoForge/Vanilla/Spigot）
 *   3. 配置编辑（server.properties + .sh 脚本）
 *   4. 模组/插件（Modrinth + CurseForge）
 *
 * 用户确认后进入主界面。
 */
@Composable
fun EnvSetupScreen(
    onSetupComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val envState by LinuxEnvironmentManager.envState.collectAsState()
    val downloadItems by LinuxEnvironmentManager.downloadItems.collectAsState()
    val mirrorResults by LinuxEnvironmentManager.mirrorResults.collectAsState()
    val isTestingMirrors by LinuxEnvironmentManager.isTestingMirrors.collectAsState()

    var currentStep by remember { mutableStateOf(1) }
    var step1Confirmed by remember { mutableStateOf(envState == LinuxEnvState.READY || envState == LinuxEnvState.JAVA_MISSING) }
    var step2Confirmed by remember { mutableStateOf(false) }
    var step3Confirmed by remember { mutableStateOf(false) }
    var step4Confirmed by remember { mutableStateOf(false) }

    var startSetup by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    // 用户选择安装的 Java 版本（默认全选，可取消不需要的）
    var selectedJdks by remember { mutableStateOf(setOf(8, 11, 17, 21)) }
    val logs = remember { mutableStateListOf<String>() }

    // 收集日志
    LaunchedEffect(Unit) {
        LinuxEnvironmentManager.setupLog.collect { line ->
            logs.add(line)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 40.dp)
        ) {
            // ── 标题 ──
            Text(
                text = "环境初始化",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "首次启动需要配置运行环境，请保持网络连接",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))

            // ── 4 步指示器 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                val steps = listOf(
                    Triple(1, "环境", step1Confirmed),
                    Triple(2, "核心", step2Confirmed),
                    Triple(3, "配置", step3Confirmed),
                    Triple(4, "模组", step4Confirmed)
                )
                steps.forEachIndexed { index, (num, label, done) ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    when {
                                        done -> MaterialTheme.colorScheme.primary
                                        num == currentStep -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surfaceDim
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (done) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(
                                    text = "$num",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        color = if (num == currentStep)
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (num == currentStep || done)
                                MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                    if (index < steps.size - 1) {
                        Box(
                            modifier = Modifier
                                .weight(0.3f)
                                .height(2.dp)
                                .align(Alignment.CenterVertically)
                                .offset(y = (-14).dp)
                                .background(
                                    if (done) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── 步骤内容区域 ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp)
            ) {
                when (currentStep) {
                    1 -> Step1Environment(
                        envState = envState,
                        downloadItems = downloadItems,
                        startSetup = startSetup,
                        confirmed = step1Confirmed,
                        logs = logs,
                        mirrorResults = mirrorResults,
                        isTestingMirrors = isTestingMirrors,
                        selectedJdks = selectedJdks,
                        onToggleJdk = { v ->
                            selectedJdks = if (v in selectedJdks) selectedJdks - v else selectedJdks + v
                        },
                        onStartSetup = {
                            startSetup = true
                            scope.launch {
                                LinuxEnvironmentManager.runFullSetup(selectedJdks.toList())
                            }
                        },
                        onConfirm = {
                            step1Confirmed = true
                            currentStep = 2
                        }
                    )

                    2 -> Step2ServerCore(
                        confirmed = step2Confirmed,
                        onConfirm = {
                            step2Confirmed = true
                            currentStep = 3
                        }
                    )

                    3 -> Step3Config(
                        confirmed = step3Confirmed,
                        onConfirm = {
                            step3Confirmed = true
                            currentStep = 4
                        }
                    )

                    4 -> Step4Mods(
                        confirmed = step4Confirmed,
                        onConfirm = {
                            step4Confirmed = true
                            showConfirmDialog = true
                        }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── 底部导航 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 上一步（仅在第 2-4 步显示）
                if (currentStep > 1) {
                    TextButton(onClick = { currentStep-- }) {
                        Icon(Icons.Filled.ArrowBack, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上一步")
                    }
                } else {
                    Spacer(Modifier.width(1.dp)) // 占位保持布局
                }

                // 跳过按钮（仅在第 2-4 步显示）
                if (currentStep > 1) {
                    TextButton(onClick = {
                        when (currentStep) {
                            2 -> { step2Confirmed = true; currentStep++ }
                            3 -> { step3Confirmed = true; currentStep++ }
                            4 -> {
                                step4Confirmed = true
                                showConfirmDialog = true
                            }
                        }
                    }) {
                        Text("跳过")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.SkipNext, null, Modifier.size(16.dp))
                    }
                }
            }
        }

        // 确认对话框
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = {
                    Text(
                        "完成设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "环境已配置完成。你可以随时在设置中修改这些选项。\n\n" +
                        "• Linux 环境：${if (envState == LinuxEnvState.READY || envState == LinuxEnvState.JAVA_MISSING) "✓ 就绪" else "⚠ 跳过"}\n" +
                        "• 服务器核心：${if (step2Confirmed) "✓ 已选" else "⚠ 跳过"}\n" +
                        "• 配置文件：${if (step3Confirmed) "✓ 已配置" else "⚠ 跳过"}\n" +
                        "• 模组/插件：${if (step4Confirmed) "✓ 已选" else "⚠ 跳过"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(onClick = { onSetupComplete() }) {
                        Text("进入主页")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDialog = false }) {
                        Text("返回")
                    }
                }
            )
        }
    }
}

// ═══════════════════════════════════════════
// 步骤 1：Linux 环境
// ═══════════════════════════════════════════

/** 可选的 Java 版本（勾选安装，不必全部） */
private val jdkOptions = listOf(
    8 to "Java 8（Minecraft 1.8-1.12）",
    11 to "Java 11（Minecraft 1.13-1.16）",
    17 to "Java 17（Minecraft 1.17-1.20.4）",
    21 to "Java 21（Minecraft 1.20.5+）"
)

@Composable
private fun Step1Environment(
    envState: LinuxEnvState,
    downloadItems: List<DownloadItem>,
    startSetup: Boolean,
    confirmed: Boolean,
    logs: List<String>,
    mirrorResults: List<MirrorTestResult>,
    isTestingMirrors: Boolean,
    selectedJdks: Set<Int>,
    onToggleJdk: (Int) -> Unit,
    onStartSetup: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "第 1 步：准备 Linux 环境",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            "下载 proot 运行时和 Ubuntu 24.04，并安装你选择的 Java 版本",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        if (!startSetup) {
            // 未开始 — 显示说明和测速按钮（内容较多，支持滚动）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "我们将自动完成以下操作：",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    "提取 proot 运行时（必需 · 内置，不消耗流量）",
                    "提取 Ubuntu 24.04（必需 · 内置，不消耗流量）",
                    "安装你勾选的 Java 版本（可选 · 需网络下载，未勾选的不安装）"
                ).forEach { item ->
                    Text(
                        "• $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                // ── 必需项（内置，锁定不可取消） ──
                Text(
                    "必需组件（已内置，无需下载）：",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                listOf(
                    "proot 运行时（解压即用）",
                    "Ubuntu 24.04（解压即用，约 30 MB）"
                ).forEach { label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = true, onCheckedChange = null, enabled = false)
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // ── Java 版本多选（可选） ──
                Text(
                    "选择需要安装的 Java 版本（可选，不勾选则跳过）：",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "不同 Minecraft 版本需要不同 Java，只选你需要的可节省下载时间与空间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                jdkOptions.forEach { (version, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleJdk(version) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = version in selectedJdks,
                            onCheckedChange = { onToggleJdk(version) }
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val estMb = selectedJdks.size * 80
                Text(
                    if (selectedJdks.isEmpty())
                        "不勾选任何 Java 版本也可以完成部署（之后可在「设置」中随时补装）"
                    else
                        "预计需下载 ~$estMb MB（每个 Java 约 80 MB），请确保 Wi-Fi 已连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedJdks.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ── 镜像测速状态 ──
                if (isTestingMirrors) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在测速镜像源...", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (mirrorResults.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("镜像延迟测试", style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            mirrorResults.take(5).forEach { r ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (r.isBest) {
                                            Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(4.dp))
                                        } else {
                                            Spacer(Modifier.width(18.dp))
                                        }
                                        Text(r.name, style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (r.isBest) FontWeight.Medium else FontWeight.Normal)
                                    }
                                    Text(
                                        if (r.error != null) "超时" else "${r.latencyMs}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (r.isBest) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                // 允许不勾选任何 Java：只部署必需组件（proot + Ubuntu），之后可在设置页补装 Java
                Button(onClick = onStartSetup) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("开始下载")
                }
            }
        } else {
            // 已开始 — 显示测速状态和下载进度
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── 镜像测速状态（下载过程中也会实时更新） ──
                if (isTestingMirrors || mirrorResults.isNotEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isTestingMirrors) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        if (isTestingMirrors) "正在测速镜像源..." else "镜像延迟测试",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (!isTestingMirrors && mirrorResults.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    mirrorResults.take(5).forEach { r ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (r.isBest) {
                                                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp),
                                                        tint = MaterialTheme.colorScheme.primary)
                                                    Spacer(Modifier.width(4.dp))
                                                } else {
                                                    Spacer(Modifier.width(18.dp))
                                                }
                                                Text(r.name, style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = if (r.isBest) FontWeight.Medium else FontWeight.Normal)
                                            }
                                            Text(
                                                if (r.error != null) "超时" else "${r.latencyMs}ms",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (r.isBest) MaterialTheme.colorScheme.primary
                                                       else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                items(downloadItems) { item ->
                    DownloadItemCard(item)
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    // 日志区域
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceDim
                    ) {
                        LazyColumn(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(logs.reversed().take(15)) { log ->
                                Text(
                                    log,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (envState == LinuxEnvState.READY || envState == LinuxEnvState.JAVA_MISSING) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("确认，下一步")
                }
            } else if (envState == LinuxEnvState.ERROR) {
                OutlinedButton(
                    onClick = { onStartSetup() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("重试")
                }
            }
        }
    }
}

@Composable
private fun DownloadItemCard(item: DownloadItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态图标
        when (item.state) {
            DownloadItemState.PENDING -> Icon(
                Icons.Filled.RadioButtonUnchecked, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            DownloadItemState.DOWNLOADING -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                progress = { item.progress },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            DownloadItemState.COMPLETED -> Icon(
                Icons.Filled.CheckCircle, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            DownloadItemState.FAILED -> Icon(
                Icons.Filled.Error, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error
            )
            DownloadItemState.EXTRACTING -> LinearProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (item.state == DownloadItemState.DOWNLOADING) FontWeight.Medium else FontWeight.Normal
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    item.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.state == DownloadItemState.DOWNLOADING ||
                item.state == DownloadItemState.EXTRACTING
            ) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Row {
                    Text(
                        formatSize(item.downloadedBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        " / ${formatSize(item.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        formatSpeed(item.speedBytesPerSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// 步骤 2：服务器核心
// ═══════════════════════════════════════════

@Composable
private fun Step2ServerCore(
    confirmed: Boolean,
    onConfirm: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "第 2 步：选择服务器核心",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            "选择你需要的服务器核心类型和 Minecraft 版本",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(
                Icons.Filled.Widgets,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "可以稍后在主页下载服务器核心",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "支持 Paper / Purpur / Fabric / Forge / NeoForge / Vanilla / Spigot",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onConfirm) {
                Icon(Icons.Filled.ArrowForward, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("下一步")
            }
        }
    }
}

// ═══════════════════════════════════════════
// 步骤 3：配置编辑
// ═══════════════════════════════════════════

@Composable
private fun Step3Config(
    confirmed: Boolean,
    onConfirm: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "第 3 步：服务器配置",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            "编辑 server.properties 和启动脚本，支持表单+原文双模式",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "可以稍后在配置页面编辑",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "支持端口、模式、难度、PVP、最大玩家等常用配置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onConfirm) {
                Icon(Icons.Filled.ArrowForward, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("下一步")
            }
        }
    }
}

// ═══════════════════════════════════════════
// 步骤 4：模组/插件
// ═══════════════════════════════════════════

@Composable
private fun Step4Mods(
    confirmed: Boolean,
    onConfirm: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "第 4 步：模组和插件",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            "从 Modrinth 和 CurseForge 下载模组/插件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "可以稍后从模组页面下载",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "支持 Modrinth + CurseForge 双源，可选版本和加载器",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onConfirm) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("完成设置")
            }
        }
    }
}

// ═══════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════

private fun formatSize(bytes: Long): String {
    return when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec <= 0 -> ""
        bytesPerSec < 1024 -> "${bytesPerSec} B/s"
        bytesPerSec < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024))
    }
}