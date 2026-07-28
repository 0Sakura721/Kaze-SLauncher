package com.mcserver.launcher.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.server.NetworkUtils
import com.mcserver.launcher.server.PerformanceMonitor
import com.mcserver.launcher.server.ServerManager
import com.mcserver.launcher.ui.theme.McColors
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    config: ServerConfig,
    onNavigateToConfig: () -> Unit,
    onNavigateToConsole: () -> Unit,
    onNavigateToManagement: () -> Unit,
    onNavigateToServerList: () -> Unit,
    onNavigateToTerminal: () -> Unit
) {
    val serverManager = ServerManager.instance
    val serverStatus by serverManager.serverStatus.collectAsState()
    val perfMetrics by PerformanceMonitor.instance.metrics.collectAsState()
    val context = LocalContext.current

    var networkState by remember { mutableStateOf(NetworkUtils.NetworkState.DISCONNECTED) }
    var localIp by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        localIp = NetworkUtils.getLocalIpAddress()
        networkState = NetworkUtils.getNetworkState(context)
    }

    val isRunning = serverStatus.state == ServerState.RUNNING
    val isStarting = serverStatus.state == ServerState.STARTING

    Scaffold(containerColor = Color(0xFF0A0E17)) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(serverStatus, config, isRunning, isStarting)
            RingRow(perfMetrics, isRunning, config)
            DetailGrid(localIp, config.serverPort, perfMetrics)
            ActionRow(onNavigateToConsole, onNavigateToManagement, onNavigateToConfig, onNavigateToServerList)
        }
    }
}

@Composable
private fun StatusCard(
    status: com.mcserver.launcher.data.ServerStatus,
    config: ServerConfig,
    isRunning: Boolean,
    isStarting: Boolean
) {
    val statusColor = if (isRunning) McColors.Success else if (isStarting) McColors.Warning else McColors.Offline
    val statusText = if (isRunning) "运行中" else if (isStarting) "启动中..." else "已停止"
    val pulse by if (isRunning) rememberInfiniteTransition().animateFloat(0.3f,0.8f,infiniteRepeatable(tween(1500),RepeatMode.Reverse)) else remember{mutableFloatStateOf(0.5f)}

    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = McColors.Surface)) {
        Box(Modifier.fillMaxWidth().padding(20.dp)) {
            if (isRunning) Canvas(Modifier.matchParentSize()) { drawCircle(Color(0xFF66DE7B).copy(alpha=pulse*0.12f), size.minDimension, Offset(size.width*0.85f,size.height*0.2f)) }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(config.name.ifEmpty{"Minecraft Server"}, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = McColors.OnSurface)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor.copy(alpha=if(isRunning)pulse else 1f)))
                            Spacer(Modifier.width(6.dp))
                            Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                        }
                    }
                    FilledIconButton({if(isRunning)ServerManager.instance.stopServer()else ServerManager.instance.startServer(config)}, Modifier.size(56.dp), shape=RoundedCornerShape(16.dp), enabled = !isStarting,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if(isRunning) McColors.ErrorContainer.copy(0.3f) else McColors.PrimaryContainer, contentColor = if(isRunning) McColors.Error else McColors.Primary),
                        Icon(if(isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow, if(isRunning)"停止" else"启动", Modifier.size(28.dp))
                    }
                }
                if (isRunning) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.People,null,Modifier.size(14.dp),tint=McColors.OnSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text("${status.playerCount}/${config.maxPlayers}",style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,color=McColors.OnSurface)
                            Spacer(Modifier.width(4.dp))
                            Text("玩家",style=MaterialTheme.typography.bodySmall,color=McColors.OnSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Timer,null,Modifier.size(14.dp),tint=McColors.OnSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text(fmtUptime(status.uptimeSeconds),style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,color=McColors.OnSurface)
                            Spacer(Modifier.width(4.dp))
                            Text("运行",style=MaterialTheme.typography.bodySmall,color=McColors.OnSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RingRow(m: com.mcserver.launcher.server.PerformanceMonitor.Metrics, isRunning: Boolean, config: ServerConfig) {
    val memMax = if (isRunning) m.memoryMaxMB else config.allocatedMemoryMB.toLong()
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = McColors.Surface)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Ring("CPU", m.cpuPercent / 100f, "${m.cpuPercent.roundToInt()}%", McColors.Accent)
            Ring("内存", if(memMax>0) m.memoryUsedMB.toFloat()/memMax else 0f, "${m.memoryUsedMB}MB", McColors.Primary)
            Ring("TPS", (m.tps/20.0).toFloat().coerceIn(0f,1f), String.format("%.1f",m.tps), if(m.tps>=19) McColors.Success else McColors.Warning)
        }
    }
}

@Composable
private fun Ring(label: String, fraction: Float, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
            val f by animateFloatAsState(fraction, tween(600))
            Canvas(Modifier.size(72.dp)) {
                val s = 6.dp.toPx()
                drawArc(color.copy(0.15f),135f,270f,false,Stroke(s,cap=StrokeCap.Round))
                drawArc(color,135f,270f*f,false,Stroke(s,cap=StrokeCap.Round))
            }
            Text(value, style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold, color=McColors.OnSurface, fontFamily=FontFamily.Monospace, fontSize=11.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style=MaterialTheme.typography.labelSmall, color=McColors.OnSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailGrid(ip: String?, port: Int, m: com.mcserver.launcher.server.PerformanceMonitor.Metrics) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = McColors.Surface)) {
        FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(Icons.Filled.Language, "${ip?"-"}:$port", "地址")
            Chip(Icons.Filled.Speed, String.format("%.1f",m.tps), "TPS")
            Chip(Icons.Filled.TrendingDown, String.format("%.1fms",m.mspt), "MSPT")
            Chip(Icons.Filled.Memory, "${m.threadCount}", "线程")
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, value: String, label: String) {
    Surface(Modifier, RoundedCornerShape(12.dp), color = McColors.SurfaceVariant) {
        Row(Modifier.padding(horizontal=12.dp,vertical=8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon,null,Modifier.size(14.dp),tint=McColors.Primary)
            Spacer(Modifier.width(6.dp))
            Text(value,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,color=McColors.OnSurface)
            Spacer(Modifier.width(4.dp))
            Text(label,style=MaterialTheme.typography.bodySmall,color=McColors.OnSurfaceVariant)
        }
    }
}

@Composable
private fun ActionRow(console: ()->Unit, mgmt: ()->Unit, config: ()->Unit, server: ()->Unit) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = McColors.Surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActBtn(Icons.Filled.Terminal,"控制台",console)
            ActBtn(Icons.Filled.Widgets,"管理",mgmt)
            ActBtn(Icons.Filled.Tune,"配置",config)
            ActBtn(Icons.Filled.Storage,"服务器",server)
        }
    }
}

@Composable
private fun ActBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon,label,Modifier.size(24.dp),tint=McColors.Primary)
            Spacer(Modifier.height(2.dp))
            Text(label,style=MaterialTheme.typography.labelSmall,color=McColors.OnSurfaceVariant)
        }
    }
}

private fun fmtUptime(s: Long) = if(s<60) "${s}s" else{val m=s/60;val h=m/60;if(h>0)"${h}h${m%60}m" else "${m}m${s%60}s"}
