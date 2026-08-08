package com.mcserver.launcher.ui.screens.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.ui.components.LocalUiMessenger
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.ui.theme.KazeSpacing

/**
 * 配置 Tab:基础 / 世界 / 性能 / JVM 参数编辑,保存后重启生效。
 */
@Composable
fun ConfigTab(instance: ServerInstance) {
    val messenger = LocalUiMessenger.current
    val cfg = instance.config
    var port by remember { mutableStateOf(cfg.serverPort.toString()) }
    var maxRam by remember { mutableStateOf(cfg.maxRamMB.toString()) }
    var maxPlayers by remember { mutableStateOf(cfg.maxPlayers.toString()) }
    var motd by remember { mutableStateOf(cfg.motd) }
    var onlineMode by remember { mutableStateOf(cfg.onlineMode) }
    var whiteList by remember { mutableStateOf(cfg.whiteList) }
    var pvp by remember { mutableStateOf(cfg.pvp) }
    var gamemode by remember { mutableStateOf(cfg.gamemode) }
    var difficulty by remember { mutableStateOf(cfg.difficulty) }
    var levelName by remember { mutableStateOf(cfg.levelName) }
    var levelSeed by remember { mutableStateOf(cfg.levelSeed) }
    var levelType by remember { mutableStateOf(cfg.levelType) }
    var hardcore by remember { mutableStateOf(cfg.hardcore) }
    var allowNether by remember { mutableStateOf(cfg.allowNether) }
    var allowFlight by remember { mutableStateOf(cfg.allowFlight) }
    var spawnMonsters by remember { mutableStateOf(cfg.spawnMonsters) }
    var spawnAnimals by remember { mutableStateOf(cfg.spawnAnimals) }
    var viewDistance by remember { mutableStateOf(cfg.viewDistance.toString()) }
    var spawnProtection by remember { mutableStateOf(cfg.spawnProtection.toString()) }
    var maxWorldSize by remember { mutableStateOf(cfg.maxWorldSize.toString()) }
    var jvmArgs by remember { mutableStateOf(cfg.jvmArgs) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = KazeSpacing.lg)) {
            item {
                Text("服务器配置(保存后重启生效)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(KazeSpacing.md))

                SectionLabel("基础")
                Spacer(Modifier.height(6.dp))
                NumberField(port, { port = it.filter { c -> c.isDigit() }.take(5) }, "端口")
                Spacer(Modifier.height(8.dp))
                Text("最大内存:${maxRam.toIntOrNull() ?: 2048} MB", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = (maxRam.toIntOrNull() ?: 2048).toFloat().coerceIn(512f, 8192f),
                    onValueChange = { maxRam = it.toInt().toString() },
                    valueRange = 512f..8192f,
                    steps = 14,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("建议:1GB(1024)起步,2-4GB 适合 10-50 人,上限受设备内存限制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                NumberField(maxPlayers, { maxPlayers = it.filter { c -> c.isDigit() }.take(4) }, "最大玩家数")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = motd, onValueChange = { motd = it },
                    label = { Text("服务器描述(MOTD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(12.dp))
                SwitchRow("在线模式(正版验证)", onlineMode) { onlineMode = it }
                SwitchRow("白名单", whiteList) { whiteList = it }
                SwitchRow("允许 PvP", pvp) { pvp = it }

                Spacer(Modifier.height(12.dp))
                Text("游戏模式", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf("survival", "creative", "adventure"), gamemode) { gamemode = it }
                Spacer(Modifier.height(10.dp))
                Text("难度", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf("peaceful", "easy", "normal", "hard"), difficulty) { difficulty = it }

                Spacer(Modifier.height(16.dp))
                SectionLabel("世界")
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = levelName, onValueChange = { levelName = it },
                    label = { Text("世界目录名(level-name),如导入世界名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = levelSeed, onValueChange = { levelSeed = it },
                    label = { Text("世界种子(留空随机)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("世界类型", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf("default", "flat", "large_biomes", "amplified"), levelType) { levelType = it }
                Spacer(Modifier.height(8.dp))
                SwitchRow("极限模式(Hardcore)", hardcore) { hardcore = it }
                SwitchRow("允许地狱(下界)", allowNether) { allowNether = it }
                SwitchRow("允许飞行", allowFlight) { allowFlight = it }
                SwitchRow("生成怪物", spawnMonsters) { spawnMonsters = it }
                SwitchRow("生成动物", spawnAnimals) { spawnAnimals = it }

                Spacer(Modifier.height(16.dp))
                SectionLabel("性能")
                Spacer(Modifier.height(6.dp))
                NumberField(viewDistance, { viewDistance = it.filter { c -> c.isDigit() }.take(2) }, "视距(2-32)")
                Spacer(Modifier.height(8.dp))
                NumberField(spawnProtection, { spawnProtection = it.filter { c -> c.isDigit() }.take(3) }, "出生点保护半径(0=关闭)")
                Spacer(Modifier.height(8.dp))
                NumberField(maxWorldSize, { maxWorldSize = it.filter { c -> c.isDigit() }.take(8) }, "最大世界边界(1-29999984)")

                Spacer(Modifier.height(16.dp))
                SectionLabel("JVM 参数(高级)")
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = jvmArgs, onValueChange = { jvmArgs = it },
                    label = { Text("附加参数,如 -XX:+UseG1GC -XX:+ParallelRefProcEnabled") },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                Text("示例(Paper 推荐):-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
        }

        Button(onClick = {
            val newCfg = cfg.copy(
                serverPort = port.toIntOrNull() ?: cfg.serverPort,
                maxRamMB = maxRam.toIntOrNull()?.coerceAtLeast(512) ?: cfg.maxRamMB,
                maxPlayers = maxPlayers.toIntOrNull() ?: cfg.maxPlayers,
                motd = motd,
                onlineMode = onlineMode,
                whiteList = whiteList,
                pvp = pvp,
                gamemode = gamemode,
                difficulty = difficulty,
                levelName = levelName.ifBlank { "world" },
                levelSeed = levelSeed,
                levelType = levelType,
                hardcore = hardcore,
                allowNether = allowNether,
                allowFlight = allowFlight,
                spawnMonsters = spawnMonsters,
                spawnAnimals = spawnAnimals,
                viewDistance = viewDistance.toIntOrNull()?.coerceIn(2, 32) ?: cfg.viewDistance,
                spawnProtection = spawnProtection.toIntOrNull()?.coerceAtLeast(0) ?: cfg.spawnProtection,
                maxWorldSize = maxWorldSize.toIntOrNull()?.coerceIn(1, 29999984) ?: cfg.maxWorldSize,
                jvmArgs = jvmArgs.trim()
            )
            InstanceStore.update(instance.copy(config = newCfg))
            messenger.toastSuccess("配置已保存,重启服务器后生效")
        }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("保存配置") }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { opt ->
            FilterChip(
                selected = selected == opt,
                onClick = { onSelect(opt) },
                label = { Text(opt) }
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
