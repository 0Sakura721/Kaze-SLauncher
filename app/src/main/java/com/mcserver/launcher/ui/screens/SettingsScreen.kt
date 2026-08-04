package com.mcserver.launcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.env.EnvState
import com.mcserver.launcher.core.server.JreInstaller
import com.mcserver.launcher.data.SettingsStore
import com.mcserver.launcher.util.FileImporter
import java.io.File
import kotlinx.coroutines.launch

/** 递归查找包含 bin/java 的目录(JDK 根) */
private fun findJdkRoot(dir: File): File? {
    if (File(dir, "bin/java").exists()) return dir
    dir.listFiles()?.forEach { child ->
        if (child.isDirectory) {
            val found = findJdkRoot(child)
            if (found != null) return found
        }
    }
    return null
}

/** 设置页:环境状态 / Java 按需管理 / 主题 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val envState by EnvManager.state.collectAsState()
    val busyVersion by JreInstaller.busy.collectAsState()
    val message by JreInstaller.message.collectAsState()
    val dark by SettingsStore.themeDark.collectAsState()

    var installing by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }
    var showEnvSetup by remember { mutableStateOf(false) }

    // SAF:选择本地 JDK 目录进行导入(本地优先,不消耗流量)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null && importing != null) {
            val version = importing!!
            importing = null
            scope.launch {
                val importDir = File(context.filesDir, "import_tmp")
                if (importDir.exists()) importDir.deleteRecursively()
                FileImporter.copyTree(context, uri, importDir)
                    .onSuccess { count ->
                        if (count == 0) {
                            JreInstaller.notifyMessage("所选目录为空或不可读")
                            importDir.deleteRecursively()
                        } else {
                            val jdkRoot = findJdkRoot(importDir)
                            if (jdkRoot == null) {
                                JreInstaller.notifyMessage("所选目录不是有效的 JDK(缺少 bin/java)")
                                importDir.deleteRecursively()
                            } else {
                                JreInstaller.importJdk(jdkRoot, version).onFailure {
                                    importDir.deleteRecursively()
                                }
                            }
                        }
                    }
                    .onFailure { err -> JreInstaller.notifyMessage("导入失败:${err.message}") }
            }
        }
    }

    if (showEnvSetup) {
        EnvSetupScreen(onSetupComplete = { showEnvSetup = false })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(16.dp))

        // ── 环境 ──
        SectionCard("运行环境") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Linux 环境(proot + Ubuntu)", Modifier.weight(1f))
                val (label, color) = when (envState) {
                    EnvState.READY -> "已就绪" to Color(0xFF4CAF50)
                    EnvState.SETTING_UP -> "部署中" to Color(0xFFFFA726)
                    EnvState.ERROR -> "出错" to MaterialTheme.colorScheme.error
                    else -> "未部署" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(label, color = color, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(6.dp))
            Text("已安装 Java:${EnvManager.installedJdkVersions().ifEmpty { listOf("无") }.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { showEnvSetup = true }) { Text("重新部署环境") }
        }
        Spacer(Modifier.height(14.dp))

        // ── Java 管理 ──
        SectionCard("Java 运行时(按需安装)") {
            Text("不同 MC 版本需要不同 Java,按需安装,不强制全部。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            listOf(8, 11, 17, 21).forEach { version ->
                val installed = EnvManager.isJdkInstalled(version)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("Java $version", Modifier.weight(1f))
                    Text(
                        if (installed) "已安装" else "未安装",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (installed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (busyVersion == version.toString()) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else if (installed) {
                        IconButton(onClick = {
                            scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { JreInstaller.delete(version.toString()) } }
                        }) { Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp)) }
                    } else {
                        // 本地导入优先(不消耗流量),下载为备选
                        IconButton(
                            onClick = {
                                importing = version.toString()
                                importLauncher.launch(null)
                            },
                            enabled = busyVersion == null
                        ) { Icon(Icons.Filled.FolderOpen, "从本地导入", Modifier.size(18.dp)) }
                        IconButton(
                            onClick = {
                                installing = version.toString()
                                scope.launch {
                                    JreInstaller.install(version.toString()).onFailure { }
                                    installing = null
                                }
                            },
                            enabled = busyVersion == null
                        ) { Icon(Icons.Filled.Download, "下载", Modifier.size(18.dp)) }
                    }
                }
            }
            if (message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (installing != null && busyVersion == null) {
                Text("正在下载 Java $installing...", style = MaterialTheme.typography.bodySmall)
            }
            if (importing != null) {
                Text("请选择包含 bin/java 的 JDK 目录(本地导入,不消耗流量)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── 外观 ──
        SectionCard("外观") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("深色主题", Modifier.weight(1f))
                Switch(checked = dark, onCheckedChange = { SettingsStore.setThemeDark(it) })
            }
        }
        Spacer(Modifier.height(14.dp))

        SectionCard("关于") {
            Text("Kaze SLauncher v2.0", style = MaterialTheme.typography.bodyMedium)
            Text("在 Android 上运行 Minecraft Java 服务端",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
