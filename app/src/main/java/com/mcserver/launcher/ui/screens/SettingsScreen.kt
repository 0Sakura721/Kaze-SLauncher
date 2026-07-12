package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.data.JreStatus
import com.mcserver.launcher.server.ServerManager
import com.mcserver.launcher.ui.components.ThemeSelectorCard
import com.mcserver.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val serverManager = ServerManager.instance
    val jreInfo by serverManager.jreInfo.collectAsState()
    val scope = rememberCoroutineScope()

    var showJreProgress by remember { mutableStateOf(false) }
    var jreProgress by remember { mutableFloatStateOf(0f) }

    // 版本选择
    var availableVersions by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf(serverManager.selectedJreVersion) }
    var selectedPackage by remember { mutableStateOf(serverManager.selectedJrePackage) }
    var showVersionPicker by remember { mutableStateOf(false) }
    var loadingVersions by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }

    // 首次加载：获取可选版本列表
    LaunchedEffect(Unit) {
        loadingVersions = true
        loadError = false
        serverManager.fetchAvailableVersions().fold(
            onSuccess = { versions ->
                availableVersions = versions
                loadingVersions = false
            },
            onFailure = {
                // 网络失败时用内置列表
                availableVersions = listOf("21", "17", "11", "8")
                loadError = true
                loadingVersions = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // 主题选择
        ThemeSelectorCard(
            currentTheme = currentTheme,
            onThemeSelected = onThemeChange
        )

        // JRE 版本与安装管理
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Java 运行时管理",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 状态行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (jreInfo.status) {
                                JreStatus.INSTALLED -> "✅ Java $selectedVersion 已就绪"
                                JreStatus.NOT_INSTALLED -> "⚠️ 未安装"
                                JreStatus.DOWNLOADING -> "⬇️ 下载中..."
                                JreStatus.EXTRACTING -> "📦 解压中..."
                                JreStatus.ERROR -> "❌ 安装失败"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        if (jreInfo.status == JreStatus.INSTALLED && jreInfo.installedVersions.size > 1) {
                            Text(
                                text = "已安装: ${jreInfo.installedVersions.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 版本选择行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("版本:", style = MaterialTheme.typography.bodyMedium)
                    if (loadingVersions) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("获取中...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        // 版本下拉
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) {
                                Text(selectedVersion)
                                Icon(Icons.Filled.ArrowDropDown, null)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                availableVersions.forEach { v ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Java $v")
                                                if (jreInfo.installedVersions.contains(v)) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedVersion = v
                                            serverManager.setJreVersion(v, selectedPackage)
                                            expanded = false
                                        },
                                        leadingIcon = {
                                            if (v == selectedVersion)
                                                Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // JDK / JRE 切换
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = selectedPackage == "jre",
                            onClick = {
                                selectedPackage = "jre"
                                serverManager.setJreVersion(selectedVersion, "jre")
                            },
                            label = { Text("JRE", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = selectedPackage == "jdk",
                            onClick = {
                                selectedPackage = "jdk"
                                serverManager.setJreVersion(selectedVersion, "jdk")
                            },
                            label = { Text("JDK", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                if (loadError) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "⚠ 无法连接网络，使用内置版本列表",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 安装/重装按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val needInstall = jreInfo.status != JreStatus.INSTALLED ||
                            !jreInfo.installedVersions.contains(selectedVersion)

                    Button(
                        onClick = {
                            scope.launch {
                                showJreProgress = true
                                serverManager.installJre { progress -> jreProgress = progress }
                                showJreProgress = false
                            }
                        },
                        enabled = !showJreProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (showJreProgress) "安装中..."
                            else if (needInstall) "安装 Java $selectedVersion (${selectedPackage.uppercase()})"
                            else "重新安装"
                        )
                    }
                }

                if (showJreProgress) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { jreProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${(jreProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // 关于
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                AboutRow("版本", "1.0.0")
                AboutRow("目标架构", "ARM64 (v7a / v8a)")
                AboutRow("平台", "Android 8.0+ (API 26+)")
                AboutRow("Java 运行时", "Eclipse Temurin (Adoptium)")
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
