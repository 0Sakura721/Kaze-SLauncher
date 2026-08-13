package com.mcserver.launcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.ui.draw.clip
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
    val installedVersions by JreInstaller.installedVersions.collectAsState()
    val themeMode by SettingsStore.themeMode.collectAsState()
    val darkAmoled by SettingsStore.darkAmoled.collectAsState()
    var showAbout by remember { mutableStateOf(false) }

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
            Text("已安装 Java:${installedVersions.ifEmpty { listOf("无") }.joinToString(", ")}",
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
            Text("主题", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            com.mcserver.launcher.data.ThemeMode.labels.forEach { (mode, label) ->
                val selected = themeMode == mode
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { SettingsStore.setThemeMode(mode) }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            com.mcserver.launcher.data.ThemeMode.descriptions[mode] ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selected) {
                        Icon(
                            androidx.compose.material.icons.Icons.Filled.Check,
                            contentDescription = "已选择",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            // 深色模式子选项:是否使用 AMOLED 纯黑
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("深色模式使用 AMOLED 纯黑", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "深色/跟随系统深色时用纯黑背景,更省电",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = darkAmoled, onCheckedChange = { SettingsStore.setDarkAmoled(it) })
            }
        }
        Spacer(Modifier.height(14.dp))

        SectionCard("关于") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.mcserver.launcher.R.drawable.ic_launcher_background),
                    contentDescription = "Kaze SLauncher",
                    modifier = Modifier.size(52.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Kaze SLauncher", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("v2.0 · 在 Android 上运行 Minecraft Java 服务端",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Kaze SLauncher 是一个在 Android 设备上运行 Minecraft Java 版服务端的启动器:基于 proot + Ubuntu 24.04 虚拟环境,支持多实例、多核心、统一下载中心与本地资源导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth()) {
                Text("查看完整关于 · 版本历史 · 许可致谢")
            }
        }
    }

    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
    }
}

/** 完整关于页(全屏可滚动,参考 FCL / HMCL / PojavLauncher 关于页结构) */
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pkg = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) { null }
    }
    val versionName = pkg?.versionName ?: "1.0.0"
    val versionCode = pkg?.longVersionCode ?: 100L

    Column(Modifier.fillMaxSize()) {
        // 顶部栏
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") }
            Text("关于 Kaze SLauncher", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        // 内容(整页可滚动,底部信息不会漏)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            item {
                Column {
                // ── 应用信息 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.mcserver.launcher.R.drawable.ic_launcher_background),
                        contentDescription = "图标",
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Kaze SLauncher", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("v2.0 (${versionName} · ${versionCode})", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("在 Android 上运行 Minecraft Java 版服务端,无需 ROOT,基于 proot 虚拟化 Ubuntu 环境。",
                    style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(14.dp))
                AboutSection("核心特性") {
                    FeatureLine("多实例管理", "每个服务端独立目录/配置/世界,互不干扰")
                    FeatureLine("多核心支持", "Vanilla / Paper / Purpur / Spigot / Fabric / Forge / NeoForge")
                    FeatureLine("统一下载中心", "全局队列、断点续传、暂停/恢复/取消")
                    FeatureLine("本地资源导入", "Java / 服务端 JAR / 插件模组 / 世界,优先本地,省流量")
                    FeatureLine("Java 按需安装", "8 / 11 / 17 / 21 可选,自动同步服务器环境")
                    FeatureLine("插件/模组管理", "Modrinth 在线搜索 + 本地导入,一键启用禁用")
                    FeatureLine("服务器控制台", "实时日志、命令输入、状态监控")
                }

                Spacer(Modifier.height(14.dp))
                AboutSection("v2.0 重写更新") {
                    Text("· 全新架构:多实例 + 版本隔离,告别单服务器时代", style = bodySmall)
                    Text("· 环境部署零下载:proot + Ubuntu 24.04 内置,开箱即用", style = bodySmall)
                    Text("· 手写 tar 解压引擎,带实时进度与速度显示", style = bodySmall)
                    Text("· 文件导入走系统文件选择器,ELF 架构自动校验", style = bodySmall)
                }

                Spacer(Modifier.height(14.dp))
                AboutSection("技术栈") {
                    Text("Kotlin · Jetpack Compose · Material 3 · proot · Ubuntu 24.04", style = bodySmall)
                }

                Spacer(Modifier.height(14.dp))
                AboutSection("开源许可") {
                    Text("本项目基于 MIT License 开源。", style = bodySmall)
                    Text("proot © Cédric VINCENT / STMicroelectronics, GPL v2", style = bodySmall)
                    Text("Ubuntu base © Canonical Ltd.", style = bodySmall)
                }

                Spacer(Modifier.height(14.dp))
                AboutSection("致谢") {
                    Text("项目参考与灵感:", style = bodySmall)
                    Text("· FCL (Fold Craft Launcher) —— Android 端 MC 启动器先驱", style = bodySmall)
                    Text("· HMCL (Hello Minecraft! Launcher) —— 多版本/多加载器管理范式", style = bodySmall)
                    Text("· PojavLauncher —— proot 环境方案", style = bodySmall)
                    Text("· PaperMC / PurpurMC —— 高性能服务端", style = bodySmall)
                    Text("· Modrinth —— 模组/插件搜索 API", style = bodySmall)
                    Text("· Adoptium (Eclipse Temurin) —— JDK 发行", style = bodySmall)
                }

                Spacer(Modifier.height(14.dp))
                AboutSection("相关链接") {
                    Text("GitHub: github.com/0Sakura721/Kaze-SLauncher", style = bodySmall)
                }

                Spacer(Modifier.height(10.dp))
                Text("© 2026 Kaze SLauncher Team", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
                }
            }
        }
    }

private val bodySmall: androidx.compose.ui.text.TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall

@Composable
private fun ColumnScope.AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    content()
}

@Composable
private fun FeatureLine(name: String, desc: String) {
    Row {
        Text("· ", style = bodySmall)
        Column {
            Text("$name — $desc", style = bodySmall)
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
