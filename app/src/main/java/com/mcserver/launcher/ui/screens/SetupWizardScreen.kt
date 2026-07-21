package com.mcserver.launcher.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.server.ProotServerManager
import com.mcserver.launcher.server.LinuxEnvState2
import com.mcserver.launcher.server.LinuxEnvironmentManager
import kotlinx.coroutines.launch

/**
 * 首次启动引导向导。
 * 借鉴 Pterodactyl 和 MCSManager 的安装向导设计，分步引导用户完成：
 * 1. Linux 环境检测
 * 2. Java 安装
 * 3. 服务器核心选择/下载
 * 4. 内存分配
 * 5. 基础配置确认
 * 6. 完成
 */
@Composable
fun SetupWizardScreen(
    config: ServerConfig,
    onComplete: (ServerConfig) -> Unit,
    onNavigateToCoreDownload: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }
    val totalSteps = 4
    val context = androidx.compose.ui.platform.LocalContext.current

    val prootManager = remember { ProotServerManager() }
    var linuxState by remember { mutableStateOf(prootManager.checkState()) }
    val coroutineScope = rememberCoroutineScope()

    // 配置编辑状态
    var jarPath by remember { mutableStateOf(config.jarPath) }
    var allocatedMemory by remember { mutableIntStateOf(config.allocatedMemoryMB) }
    var serverName by remember { mutableStateOf(config.name) }
    var serverPort by remember { mutableStateOf(config.serverPort.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 进度指示器
        Spacer(Modifier.height(24.dp))
        Text(
            text = "初始设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (currentStep) {
                0 -> "第 1 步：初始化 Linux 环境"
                1 -> "第 2 步：Java 运行时"
                2 -> "第 3 步：选择服务器核心"
                3 -> "第 4 步：完成配置"
                else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        // 步骤点
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(totalSteps) { i ->
                Surface(
                    modifier = Modifier.size(if (i == currentStep) 12.dp else 8.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = when {
                        i < currentStep -> MaterialTheme.colorScheme.primary
                        i == currentStep -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {}
            }
        }

        Spacer(Modifier.height(32.dp))

        // 步骤内容
        when (currentStep) {
            0 -> StepLinuxEnv(linuxState, onCheck = {
                linuxState = prootManager.checkState()
            }, onInstall = {
                coroutineScope.launch {
                    LinuxEnvironmentManager.runFullSetup()
                    linuxState = prootManager.checkState()
                }
            })

            1 -> StepJava(linuxState, onInstallJava = {
                coroutineScope.launch {
                    LinuxEnvironmentManager.runFullSetup()
                    linuxState = prootManager.checkState()
                }
            }, onCheck = {
                linuxState = prootManager.checkState()
            })

            2 -> StepServerCore(
                jarPath = jarPath,
                onNavigateToCoreDownload = onNavigateToCoreDownload,
                onJarPathChange = { jarPath = it }
            )

            3 -> StepFinalConfig(
                serverName = serverName,
                onServerNameChange = { serverName = it },
                allocatedMemory = allocatedMemory,
                onMemoryChange = { allocatedMemory = it },
                serverPort = serverPort,
                onPortChange = { serverPort = it }
            )
        }

        Spacer(Modifier.height(32.dp))

        // 导航按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (currentStep > 0) {
                OutlinedButton(onClick = { currentStep-- }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("上一步")
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            Button(
                onClick = {
                    if (currentStep < totalSteps - 1) {
                        currentStep++
                    } else {
                        onComplete(
                            config.copy(
                                name = serverName,
                                jarPath = jarPath,
                                allocatedMemoryMB = allocatedMemory,
                                serverPort = serverPort.toIntOrNull() ?: 25565
                            )
                        )
                    }
                },
                enabled = when (currentStep) {
                    0 -> linuxState == LinuxEnvState2.READY || linuxState == LinuxEnvState2.JAVA_MISSING
                    1 -> linuxState == LinuxEnvState2.READY
                    2 -> true
                    3 -> true
                    else -> true
                }
            ) {
                Text(if (currentStep < totalSteps - 1) "下一步" else "完成设置")
                if (currentStep < totalSteps - 1) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            }
        }

        // 跳过按钮
        if (currentStep < totalSteps - 1) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = {
                onComplete(config)
            }) {
                Text("跳过设置，直接进入", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepLinuxEnv(
    state: LinuxEnvState2,
    onCheck: () -> Unit,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Terminal,
                null,
                Modifier.size(64.dp),
                tint = when (state) {
                    LinuxEnvState2.READY -> MaterialTheme.colorScheme.primary
                    LinuxEnvState2.JAVA_MISSING -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "初始化 Linux 环境",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "MCServer Launcher 使用内置的 proot+Ubuntu 环境运行 Java 和 Minecraft 服务器。\n\n点击下方按钮初始化 Linux 环境。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            // 状态指示
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = when (state) {
                    LinuxEnvState2.READY -> MaterialTheme.colorScheme.primaryContainer
                    LinuxEnvState2.NOT_READY -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (state) {
                            LinuxEnvState2.READY -> Icons.Filled.CheckCircle
                            LinuxEnvState2.NOT_READY -> Icons.Filled.Cancel
                            else -> Icons.Filled.Warning
                        },
                        null,
                        Modifier.size(20.dp),
                        tint = when (state) {
                            LinuxEnvState2.READY -> MaterialTheme.colorScheme.primary
                            LinuxEnvState2.NOT_READY -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.tertiary
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (state) {
                            LinuxEnvState2.NOT_READY -> "Linux 环境未初始化"
                            LinuxEnvState2.JAVA_MISSING -> "Linux 环境已就绪，Java 未安装"
                            LinuxEnvState2.READY -> "Linux 环境已就绪"
                        },
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (state == LinuxEnvState2.NOT_READY) {
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.OpenInBrowser, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("初始化 Linux 环境")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "安装后点击「重新检测」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("重新检测")
            }
        }
    }
}

@Composable
private fun StepJava(
    state: LinuxEnvState2,
    onInstallJava: () -> Unit,
    onCheck: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.IntegrationInstructions,
                null,
                Modifier.size(64.dp),
                tint = when (state) {
                    LinuxEnvState2.READY -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.tertiary
                }
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "安装 Java 运行时",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Minecraft 服务器需要 Java 21 或更高版本。\n点击下方按钮自动安装 JDK 21。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            if (state == LinuxEnvState2.JAVA_MISSING) {
                Button(
                    onClick = onInstallJava,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("安装 JDK 21")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "安装后请点击「重新检测」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state == LinuxEnvState2.READY) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Java 已安装，环境就绪", fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("重新检测")
            }
        }
    }
}

@Composable
private fun StepServerCore(
    jarPath: String,
    onNavigateToCoreDownload: () -> Unit,
    onJarPathChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.CloudDownload,
                null,
                Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "选择服务器核心",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "你需要一个 Minecraft 服务器 JAR 文件。\n可以从以下来源获取：\n\n• 使用内置下载器下载 Paper/Purpur/Fabric/Vanilla\n• 手动选择已有的 JAR 文件",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            if (jarPath.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已选择：${jarPath.substringAfterLast("/")}",
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = onNavigateToCoreDownload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("下载服务器核心")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "也可以先跳过，稍后在「管理 → 下载核心」中下载",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepFinalConfig(
    serverName: String,
    onServerNameChange: (String) -> Unit,
    allocatedMemory: Int,
    onMemoryChange: (Int) -> Unit,
    serverPort: String,
    onPortChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Icon(
                Icons.Filled.Tune,
                null,
                Modifier.size(64.dp).align(Alignment.CenterHorizontally),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "基本配置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = serverName,
                onValueChange = onServerNameChange,
                label = { Text("服务器名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = serverPort,
                onValueChange = { onPortChange(it.filter { c -> c.isDigit() }) },
                label = { Text("服务器端口") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("默认 25565，如有冲突请修改") }
            )
            Spacer(Modifier.height(12.dp))

            Text("内存分配：${allocatedMemory}MB (${"%.1f".format(allocatedMemory / 1024f)}GB)",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Slider(
                value = allocatedMemory.toFloat(),
                onValueChange = { onMemoryChange(it.toInt()) },
                valueRange = 512f..8192f,
                steps = 14 // 512MB increments
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("512MB", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("8GB", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
