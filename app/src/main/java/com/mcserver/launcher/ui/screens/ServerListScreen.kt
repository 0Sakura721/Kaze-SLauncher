package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.server.ServerManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onBack: () -> Unit,
    onServerSelected: (String) -> Unit = {}
) {
    val serverManager = ServerManager.instance
    val serverList by serverManager.serverList.collectAsState()
    val currentId by serverManager.currentServerId.collectAsState()
    val scope = rememberCoroutineScope()
    val serverRunning = serverManager.isRunning

    var showCreateDialog by remember { mutableStateOf(false) }
    var newServerName by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf<ServerConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("服务器列表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "创建服务器")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(serverList, key = { it.id }) { server ->
                val isCurrent = server.id == currentId
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = if (isCurrent) 4.dp else 1.dp
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Dns,
                                    null,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isCurrent) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        server.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "ID: ${server.id.take(8)}...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (isCurrent) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("当前") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            null,
                                            Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "核心: ${server.jarPath.substringAfterLast("/").ifEmpty { "未设置" }}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "端口: ${server.serverPort}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Java: ${server.javaVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!serverRunning) {
                                        serverManager.switchServer(server.id)
                                        onServerSelected(server.id)
                                    }
                                },
                                enabled = !isCurrent && !serverRunning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Login, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("切换")
                            }
                            OutlinedButton(
                                onClick = { showDeleteDialog = server },
                                enabled = serverList.size > 1,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("删除")
                            }
                        }
                        if (serverRunning && !isCurrent) {
                            Text(
                                "服务器运行中，禁止切换",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            icon = { Icon(Icons.Filled.AddCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("创建新服务器") },
            text = {
                OutlinedTextField(
                    value = newServerName,
                    onValueChange = { newServerName = it },
                    label = { Text("服务器名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newServerName.isNotBlank()) {
                            scope.launch {
                                serverManager.createServer(newServerName.trim())
                                newServerName = ""
                                showCreateDialog = false
                            }
                        }
                    },
                    enabled = newServerName.isNotBlank()
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            }
        )
    }

    showDeleteDialog?.let { server ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除服务器") },
            text = {
                Text("确定要删除「${server.name}」吗？\n服务器目录中的文件不会被删除，但配置将移除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            serverManager.deleteServer(server.id)
                            showDeleteDialog = null
                        }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("取消") }
            }
        )
    }
}
