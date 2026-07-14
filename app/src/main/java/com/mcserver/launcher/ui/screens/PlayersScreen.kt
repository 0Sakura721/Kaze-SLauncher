package com.mcserver.launcher.ui.screens

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
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.server.PlayerManager
import com.mcserver.launcher.server.ServerManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen() {
    val serverManager = ServerManager.instance
    val serverStatus by serverManager.serverStatus.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("在线玩家", "OP", "白名单", "封禁")

    // 在线玩家
    var ops by remember { mutableStateOf<List<PlayerManager.OpEntry>>(emptyList()) }
    var whitelist by remember { mutableStateOf<List<PlayerManager.WhitelistEntry>>(emptyList()) }
    var bans by remember { mutableStateOf<List<PlayerManager.BanEntry>>(emptyList()) }

    var addName by remember { mutableStateOf("") }
    var addReason by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf<Pair<String, Int>?>(null) } // name, tab

    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            ops = PlayerManager.getOps()
            whitelist = PlayerManager.getWhitelist()
            bans = PlayerManager.getBans()
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3000)
            message = null
        }
    }

    showRemoveConfirm?.let { (name, tab) ->
        val label = when (tab) { 1 -> "OP"; 2 -> "白名单"; 3 -> "封禁"; else -> "项" }
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = null },
            title = { Text("移除$label") },
            text = { Text("确定从$label 中移除「$name」吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            when (tab) {
                                1 -> PlayerManager.removeOp(name)
                                2 -> PlayerManager.removeWhitelist(name)
                                3 -> PlayerManager.removeBan(name)
                            }
                            showRemoveConfirm = null
                            refresh()
                            message = "已移除：$name"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = null }) { Text("取消") } }
        )
    }

    if (showAddDialog) {
        val title = when (selectedTab) { 1 -> "添加 OP"; 2 -> "添加到白名单"; 3 -> "封禁玩家"; else -> "" }
        AlertDialog(
            onDismissRequest = { showAddDialog = false; addName = ""; addReason = "" },
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(
                        value = addName,
                        onValueChange = { addName = it },
                        label = { Text("玩家名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (selectedTab == 3) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = addReason,
                            onValueChange = { addReason = it },
                            label = { Text("封禁原因（可选）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            when (selectedTab) {
                                1 -> PlayerManager.addOp(addName.trim()).onSuccess { message = "已添加 OP：$addName" }.onFailure { message = "失败：${it.message}" }
                                2 -> PlayerManager.addWhitelist(addName.trim()).onSuccess { message = "已添加白名单：$addName" }.onFailure { message = "失败：${it.message}" }
                                3 -> PlayerManager.addBan(addName.trim(), addReason.trim()).onSuccess { message = "已封禁：$addName" }.onFailure { message = "失败：${it.message}" }
                            }
                            showAddDialog = false; addName = ""; addReason = ""
                            refresh()
                        }
                    },
                    enabled = addName.isNotBlank()
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false; addName = ""; addReason = "" }) { Text("取消") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("玩家管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Tab 栏
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        // 操作按钮
        if (selectedTab in 1..3) {
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PersonAdd, null)
                Spacer(Modifier.width(8.dp))
                Text(when (selectedTab) {
                    1 -> "添加 OP"
                    2 -> "添加到白名单"
                    3 -> "封禁玩家"
                    else -> ""
                })
            }
        }

        when (selectedTab) {
            0 -> OnlinePlayersTab(serverStatus)
            1 -> PlayerListTab(
                title = "OP 列表",
                items = ops.map { "${it.name} (Lv.${it.level})" },
                emptyText = "没有 OP。点击上方按钮添加。",
                onRemove = { name -> showRemoveConfirm = name to 1 }
            )
            2 -> PlayerListTab(
                title = "白名单",
                items = whitelist.map { it.name },
                emptyText = "白名单为空。",
                onRemove = { name -> showRemoveConfirm = name to 2 }
            )
            3 -> BanListTab(
                items = bans,
                emptyText = "没有封禁记录。",
                onRemove = { name -> showRemoveConfirm = name to 3 }
            )
        }

        message?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun OnlinePlayersTab(status: com.mcserver.launcher.data.ServerStatus) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("在线玩家", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${status.playerCount} 人在线", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            if (status.state != ServerState.RUNNING) {
                Spacer(Modifier.height(12.dp))
                Text("服务器未运行，无法查看在线玩家。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (status.players.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("当前没有在线玩家。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Spacer(Modifier.height(8.dp))
                status.players.forEach { player ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Person, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(player, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PlayerListTab(
    title: String,
    items: List<String>,
    emptyText: String,
    onRemove: (String) -> Unit
) {
    if (items.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(emptyText, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items.size) { index ->
                val item = items[index]
                val name = item.substringBefore(" (")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Person, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(item, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        IconButton(onClick = { onRemove(name) }) {
                            Icon(Icons.Filled.RemoveCircle, "移除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BanListTab(
    items: List<PlayerManager.BanEntry>,
    emptyText: String,
    onRemove: (String) -> Unit
) {
    if (items.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(emptyText, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items.size) { index ->
                val entry = items[index]
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Block, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                            if (entry.reason.isNotBlank()) {
                                Text("原因: ${entry.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (entry.source.isNotBlank()) {
                                Text("来源: ${entry.source} | 过期: ${entry.expires}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { onRemove(entry.name) }) {
                            Icon(Icons.Filled.RemoveCircle, "解封", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
