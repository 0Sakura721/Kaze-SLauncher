package com.mcserver.launcher.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.env.EnvState
import com.mcserver.launcher.core.server.JreInstaller
import com.mcserver.launcher.data.SettingsStore
import com.mcserver.launcher.ui.components.Chip
import com.mcserver.launcher.ui.components.GhostButton
import com.mcserver.launcher.ui.components.GradientButton
import com.mcserver.launcher.ui.components.KazeTopBar
import com.mcserver.launcher.ui.components.ListGroup
import com.mcserver.launcher.ui.components.RowItemDivider
import com.mcserver.launcher.ui.components.SectionHeader
import com.mcserver.launcher.ui.components.StatusDot
import com.mcserver.launcher.ui.theme.KazeCorners
import com.mcserver.launcher.ui.theme.KazeError
import com.mcserver.launcher.ui.theme.KazeSizes
import com.mcserver.launcher.ui.theme.KazeSpacing
import com.mcserver.launcher.ui.theme.KazeSuccess
import com.mcserver.launcher.ui.theme.KazeType
import com.mcserver.launcher.ui.theme.KazeWarning
import com.mcserver.launcher.util.FileImporter
import java.io.File
import kotlinx.coroutines.launch

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

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val envState by EnvManager.state.collectAsState()
    val busyVersion by JreInstaller.busy.collectAsState()
    val message by JreInstaller.message.collectAsState()
    val installedJavas by JreInstaller.installedJavas.collectAsState()
    val themeMode by SettingsStore.themeMode.collectAsState()
    val darkAmoled by SettingsStore.darkAmoled.collectAsState()
    val reduceMotion by SettingsStore.reduceMotion.collectAsState()
    var showAbout by remember { mutableStateOf(false) }

    var installing by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }
    var showUninstallConfirm by remember { mutableStateOf<Int?>(null) }
    var showEnvSetup by remember { mutableStateOf(false) }

    val pkg = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
    }
    val versionName = pkg?.versionName ?: "1.0.0"

    BackHandler(enabled = showAbout || showEnvSetup) {
        showAbout = false; showEnvSetup = false
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            importing = null
            return@rememberLauncherForActivityResult
        }
        if (importing != null) {
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

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = KazeSpacing.xxl)
        ) {
            // ── 应用头部卡片 ──
            item {
                AppProfileHeader(versionName = versionName)
            }

            // ── 环境状态 ──
            item {
                SettingsGroup(title = "运行环境") {
                    EnvStatusRow(
                        envState = envState,
                        onRedeploy = { showEnvSetup = true }
                    )
                }
            }
            item { Spacer(Modifier.height(KazeSpacing.groupGap)) }

            // ── Java 运行时 ──
            item {
                SettingsGroup(title = "Java 运行时", subtitle = "按需安装或导入，不内置 Java") {
                    listOf(8, 11, 17, 21).forEachIndexed { index, version ->
                        val java = installedJavas.firstOrNull { it.version == version.toString() }
                        val isBuiltin = java?.isBuiltin == true
                        val busy = busyVersion == version.toString()

                        val (stateText, stateColor) = when {
                            java == null -> "未安装" to MaterialTheme.colorScheme.onSurfaceVariant
                            isBuiltin -> "内置 · 已就绪" to KazeSuccess
                            java.kind == "android" -> "已安装 · 可用" to KazeSuccess
                            java.kind == "glibc" -> "已安装 · 兼容受限" to KazeWarning
                            else -> "已安装" to MaterialTheme.colorScheme.primary
                        }

                        JavaRow(
                            version = version,
                            stateText = stateText,
                            stateColor = stateColor,
                            isBuiltin = isBuiltin,
                            installed = java != null,
                            busy = busy,
                            onInstall = {
                                installing = version.toString()
                                scope.launch {
                                    JreInstaller.install(version.toString()).onFailure { }
                                    installing = null
                                }
                            },
                            onImport = {
                                importing = version.toString()
                                importLauncher.launch(null)
                            },
                            onUninstallRequest = { showUninstallConfirm = version },
                            showDivider = index < 3
                        )
                    }
                }
                if (message.isNotBlank() || installing != null || importing != null) {
                    Spacer(Modifier.height(KazeSpacing.sm))
                    SettingsTipCard(
                        message = message,
                        installing = installing,
                        busyVersion = busyVersion,
                        importing = importing
                    )
                }
            }
            item { Spacer(Modifier.height(KazeSpacing.groupGap)) }

            // ── 外观 ──
            item {
                SettingsGroup(title = "外观", subtitle = "主题与显示偏好") {
                    val modes = com.mcserver.launcher.data.ThemeMode.labels.toList()
                    modes.forEachIndexed { index, (mode, label) ->
                        val selected = themeMode == mode
                        val icon = when (mode) {
                            com.mcserver.launcher.data.ThemeMode.LIGHT -> Icons.Filled.LightMode
                            com.mcserver.launcher.data.ThemeMode.DARK -> Icons.Filled.DarkMode
                            else -> Icons.Filled.Palette
                        }
                        ThemeRow(
                            icon = icon,
                            title = label,
                            subtitle = com.mcserver.launcher.data.ThemeMode.descriptions[mode] ?: "",
                            selected = selected,
                            onClick = { SettingsStore.setThemeMode(mode) },
                            showDivider = index < modes.size - 1
                        )
                    }
                    RowItemDivider(indent = KazeSpacing.xxxl)
                    ThemeSwitchRow(
                        icon = Icons.Filled.DarkMode,
                        title = "AMOLED 纯黑",
                        subtitle = "深色模式下纯黑背景，更省电",
                        checked = darkAmoled,
                        onCheckedChange = { SettingsStore.setDarkAmoled(it) }
                    )
                    RowItemDivider(indent = KazeSpacing.xxxl)
                    ThemeSwitchRow(
                        icon = Icons.Filled.Palette,
                        title = "降低动效",
                        subtitle = "低端设备自动开启，减少动画更流畅",
                        checked = reduceMotion,
                        onCheckedChange = { SettingsStore.setReduceMotion(it) }
                    )
                }
            }
            item { Spacer(Modifier.height(KazeSpacing.groupGap)) }

            // ── 关于 ──
            item {
                SettingsGroup(title = "关于") {
                    AboutRow(versionName = versionName, onShow = { showAbout = true })
                }
            }
            item { Spacer(Modifier.height(KazeSpacing.xxxl)) }
        }

        AnimatedVisibility(
            visible = showEnvSetup,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut()
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                EnvSetupScreen(onSetupComplete = { showEnvSetup = false })
            }
        }
        AnimatedVisibility(
            visible = showAbout,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut()
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                AboutScreen(onBack = { showAbout = false })
            }
        }
    }

    val uninstallVersion = showUninstallConfirm
    if (uninstallVersion != null) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = null },
            title = {
                Text("卸载 Java $uninstallVersion?", style = KazeType.title)
            },
            text = {
                Text(
                    "将删除已安装的 Java 运行时并释放存储空间，此操作不可撤销。",
                    style = KazeType.body
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUninstallConfirm = null
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            JreInstaller.delete(uninstallVersion.toString())
                        }
                    }
                }) {
                    Text("卸载", color = KazeError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = null }) {
                    Text("取消", style = KazeType.body)
                }
            }
        )
    }
}

@Composable
private fun AppProfileHeader(versionName: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KazeSpacing.pageHorizontal)
            .padding(top = KazeSpacing.xxl, bottom = KazeSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(KazeCorners.medium)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "K",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
        Spacer(Modifier.width(KazeSpacing.lg))
        Column {
            Text("Kaze SLauncher", style = KazeType.hero, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(KazeSpacing.xxs))
            Text(
                "v$versionName · Minecraft Java 服务端启动器",
                style = KazeType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    subtitle: String? = null,
    count: Int? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        SectionHeader(title = title, subtitle = subtitle, count = count)
        ListGroup {
            content()
        }
    }
}

@Composable
private fun IconBox(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onPrimary,
    bgColor: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier
            .size(40.dp)
            .clip(KazeCorners.small)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            null,
            Modifier.size(KazeSizes.iconMedium),
            tint = tint
        )
    }
}

@Composable
private fun SettingDivider() {
    RowItemDivider(indent = KazeSpacing.xxxl)
}

@Composable
private fun EnvStatusRow(
    envState: EnvState,
    onRedeploy: () -> Unit
) {
    val (statusText, dotColor) = when (envState) {
        EnvState.READY -> "已就绪" to KazeSuccess
        EnvState.SETTING_UP -> "部署中" to KazeWarning
        EnvState.ERROR -> "出错" to KazeError
        else -> "未部署" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onRedeploy)
            .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(
            icon = Icons.Filled.SettingsEthernet,
            bgColor = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(KazeSpacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    active = envState == EnvState.READY || envState == EnvState.SETTING_UP,
                    pulse = envState == EnvState.SETTING_UP,
                    color = dotColor
                )
                Spacer(Modifier.width(KazeSpacing.sm))
                Text("运行环境 · $statusText", style = KazeType.title)
            }
            Spacer(Modifier.height(KazeSpacing.xxs))
            val javaNames = EnvManager.installedJavas().map {
                if (it.isBuiltin) "21(内置)" else "${it.version}${if (it.kind == "android") "" else "(受限)"}"
            }
            Text(
                "已安装 Java：${if (javaNames.isEmpty()) "无" else javaNames.joinToString(", ")}",
                style = KazeType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.CloudDownload,
            null,
            Modifier.size(KazeSizes.iconSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun JavaRow(
    version: Int,
    stateText: String,
    stateColor: Color,
    isBuiltin: Boolean,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onImport: () -> Unit,
    onUninstallRequest: () -> Unit,
    showDivider: Boolean
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(KazeCorners.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$version",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
            }
            Spacer(Modifier.width(KazeSpacing.md))
            Column(Modifier.weight(1f)) {
                Text("Java $version", style = KazeType.title)
                Text(
                    stateText,
                    style = KazeType.caption,
                    color = stateColor
                )
            }
            when {
                isBuiltin -> {
                    Chip(text = "内置", compact = true)
                }
                installed -> {
                    if (busy) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        androidx.compose.material3.TextButton(
                            onClick = onUninstallRequest,
                            contentPadding = PaddingValues(horizontal = KazeSpacing.sm, vertical = 2.dp)
                        ) {
                            Text("卸载", style = KazeType.caption, color = KazeError, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                else -> {
                    if (busy) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(KazeSpacing.xxs)) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = onImport,
                                contentPadding = PaddingValues(horizontal = KazeSpacing.sm, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    null,
                                    Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("导入", style = KazeType.caption, fontWeight = FontWeight.SemiBold)
                            }
                            androidx.compose.material3.Button(
                                onClick = onInstall,
                                contentPadding = PaddingValues(horizontal = KazeSpacing.sm, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.CloudDownload,
                                    null,
                                    Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("下载", style = KazeType.caption, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        if (showDivider) SettingDivider()
    }
}

@Composable
private fun SettingsTipCard(
    message: String,
    installing: String?,
    busyVersion: String?,
    importing: String?
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KazeSpacing.pageHorizontal)
            .clip(KazeCorners.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(KazeSpacing.md)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Info,
                null,
                Modifier.size(16.dp).padding(top = 2.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(KazeSpacing.xs))
            Text(
                "下载源为 Adoptium Linux 版(glibc)，Android 16 系统限制无法直接运行，建议使用内置 Java 21 或本地导入 Android 版 JRE(Termux openjdk / FCL 运行时)。",
                style = KazeType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(KazeSpacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(active = true, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(KazeSpacing.xs))
                Text(message, style = KazeType.body, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (installing != null && busyVersion == null) {
            Spacer(Modifier.height(KazeSpacing.xs))
            Text(
                "正在下载 Java $installing…",
                style = KazeType.caption,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (importing != null) {
            Spacer(Modifier.height(KazeSpacing.xs))
            Text(
                "请选择包含 bin/java 的 JDK 目录(本地导入，不消耗流量)",
                style = KazeType.caption,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ThemeRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBox(
                icon = icon,
                bgColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(KazeSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = KazeType.title)
                Text(
                    subtitle,
                    style = KazeType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    null,
                    Modifier.size(KazeSizes.iconMedium),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (showDivider) SettingDivider()
    }
}

@Composable
private fun ThemeSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(
            icon = icon,
            bgColor = MaterialTheme.colorScheme.surfaceVariant,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(KazeSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = KazeType.title)
            Text(
                subtitle,
                style = KazeType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AboutRow(versionName: String, onShow: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onShow)
            .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(
            icon = Icons.Filled.Info,
            bgColor = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(KazeSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("关于 Kaze SLauncher", style = KazeType.title)
            Text(
                "v$versionName · 查看详细信息",
                style = KazeType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.Info,
            null,
            Modifier.size(KazeSizes.iconSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pkg = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) { null }
    }
    val versionName = pkg?.versionName ?: "1.0.0"
    @Suppress("DEPRECATION")
    val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
        pkg?.longVersionCode ?: 100L
    } else {
        pkg?.versionCode?.toLong() ?: 100L
    }
    val systemPaddings = WindowInsets.systemBars.asPaddingValues()

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(systemPaddings.calculateTopPadding()))
        KazeTopBar(
            title = "关于 Kaze",
            onBack = onBack
        )
        LazyColumn(Modifier.weight(1f)) {
            item {
                Column(Modifier.padding(horizontal = KazeSpacing.pageHorizontal)) {

                    AboutHeaderCard(versionName, versionCode)
                    Spacer(Modifier.height(KazeSpacing.lg))

                    SettingsGroup(title = "核心特性") {
                        FeatureRow(Icons.Filled.Inventory2, "多实例管理", "每个服务端独立目录/配置/世界，互不干扰", showDivider = true)
                        FeatureRow(Icons.Filled.Palette, "多核心支持", "Vanilla / Paper / Purpur / Spigot / Fabric / Forge / NeoForge", showDivider = true)
                        FeatureRow(Icons.Filled.CloudDownload, "统一下载中心", "全局队列、断点续传、暂停/恢复/取消", showDivider = true)
                        FeatureRow(Icons.Filled.FolderOpen, "本地资源导入", "Java / 服务端 JAR / 插件模组 / 世界，优先本地，省流量", showDivider = true)
                        FeatureRow(Icons.Filled.SettingsEthernet, "Java 多版本", "本地导入 + 在线下载，按需选择(推荐 Android 版 JRE)", showDivider = true)
                        FeatureRow(Icons.Filled.Extension, "插件/模组管理", "Modrinth 在线搜索 + 本地导入，一键启用禁用", showDivider = false)
                    }
                    Spacer(Modifier.height(KazeSpacing.lg))

                    SettingsGroup(title = "v2.0 重写更新") {
                        val lines = listOf(
                            "全新架构：多实例 + 版本隔离，告别单服务器时代",
                            "引擎升级：Android JRE 直跑模式，兼容 Android 16，无需 proot",
                            "Java 按需：首次启动不强制部署，设置页导入/下载即可",
                            "手写 tar 解压引擎，带实时进度与速度显示",
                            "文件导入走系统文件选择器，ELF 架构自动校验",
                            "UI 重构：紧凑列表设计，贴近原生 Android 设置体验"
                        )
                        lines.forEachIndexed { index, line ->
                            BulletRow(line, showDivider = index < lines.size - 1)
                        }
                    }
                    Spacer(Modifier.height(KazeSpacing.lg))

                    SettingsGroup(title = "技术栈 & 许可") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Chip(text = "Kotlin", compact = true)
                            Spacer(Modifier.width(KazeSpacing.sm))
                            Chip(text = "Jetpack Compose", compact = true)
                            Spacer(Modifier.width(KazeSpacing.sm))
                            Chip(text = "Material 3", compact = true)
                        }
                        SettingDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.md)
                        ) {
                            Text(
                                "本项目基于 MIT License 开源。Java 运行时基于 OpenJDK (GPL v2 + Classpath Exception)。",
                                style = KazeType.body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(KazeSpacing.lg))

                    SettingsGroup(title = "致谢") {
                        val credits = listOf(
                            "FCL (Fold Craft Launcher) —— Android 端 MC 启动器先驱",
                            "HMCL —— 多版本/多加载器管理范式",
                            "PojavLauncher —— Android 运行时方案参考",
                            "Termux —— Android 版 OpenJDK 打包方案",
                            "PaperMC / PurpurMC —— 高性能服务端",
                            "Modrinth —— 模组/插件搜索 API",
                            "Adoptium (Eclipse Temurin) —— JDK 发行"
                        )
                        credits.forEachIndexed { index, credit ->
                            BulletRow(credit, showDivider = index < credits.size - 1)
                        }
                    }
                    Spacer(Modifier.height(KazeSpacing.lg))

                    SettingsGroup(title = "相关链接") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.md)
                        ) {
                            Text("GitHub：github.com/0Sakura721/Kaze-SLauncher", style = KazeType.body)
                        }
                    }
                    Spacer(Modifier.height(KazeSpacing.lg))

                    Text(
                        "© 2026 Kaze SLauncher Team",
                        style = KazeType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = KazeSpacing.lg),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutHeaderCard(versionName: String, versionCode: Long) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(KazeCorners.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                KazeSizes.strokeThin,
                MaterialTheme.colorScheme.outlineVariant,
                KazeCorners.medium
            )
            .padding(KazeSpacing.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(KazeCorners.medium)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "K",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
            Spacer(Modifier.width(KazeSpacing.md))
            Column {
                Text("Kaze SLauncher", style = KazeType.display)
                Text(
                    "v1.0 ($versionName · $versionCode)",
                    style = KazeType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(KazeSpacing.md))
        Text(
            "在 Android 上运行 Minecraft Java 版服务端，无需 ROOT，Java 可本地导入或在线下载，开箱即用。",
            style = KazeType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    name: String,
    desc: String,
    showDivider: Boolean
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBox(
                icon = icon,
                bgColor = MaterialTheme.colorScheme.primaryContainer,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(KazeSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(name, style = KazeType.title)
                Text(
                    desc,
                    style = KazeType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showDivider) SettingDivider()
    }
}

@Composable
private fun BulletRow(text: String, showDivider: Boolean) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.sm),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .padding(top = 7.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(KazeSpacing.md))
            Text(
                text,
                style = KazeType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        if (showDivider) SettingDivider()
    }
}
