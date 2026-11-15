package com.kaze.newage.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.net.NetworkAccess
import com.kaze.newage.core.net.SakuraTunnel
import com.kaze.newage.core.sync.ModSyncServer
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EConnectedList
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.WavyLinearProgress
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.core.i18n.t

/**
 * 公网访问 / 远程管理。
 *
 * 三条线路 + 一个远程面板：
 *  - Tailscale：零配置组网，拿到稳定的 100.x 地址，朋友直接连；
 *  - 樱花映射：国内穿透，应用内用**访问密钥**登录 → 选隧道 → 一键起 frpc；
 *  - 自建 frp：有自己的 frps 时最稳；
 *  - 远程管理：复用「客户端同步」那个 HTTP 服务，多出 /api/status|logs|command|start|stop，
 *    把端口经 Tailscale/frp 暴露出去就能在浏览器里远程看日志/发命令/启停。
 *
 * frpc 是 SakuraFrp 官方分发的 linux_arm64 静态二进制，放在 proot 的 /root/kaze-net 下运行，
 * 不需要 root，也不依赖 Termux。
 */
@Composable
fun NetworkScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val currentId by viewModel.currentInstanceId.collectAsStateWithLifecycle()
    val instance = instances.firstOrNull { it.id == currentId }
    val controller = viewModel.networkController
    val tailscale by controller.tailscale.collectAsStateWithLifecycle()
    val sakura by controller.sakura.collectAsStateWithLifecycle()
    val frpcState by controller.frpc.state.collectAsStateWithLifecycle()
    val frpMessage by controller.message.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val progress by controller.progress.collectAsStateWithLifecycle()
    val frpProbe by controller.frpProbe.collectAsStateWithLifecycle()
    val syncStates by viewModel.modSyncStates.collectAsStateWithLifecycle()
    val sync = instance?.let { syncStates[it.id] } ?: ModSyncServer.Snapshot()

    var tokenInput by rememberSaveable { mutableStateOf("") }
    var serverAddr by remember { mutableStateOf(viewModel.uiPrefs.frpServer.value) }
    var serverPort by remember { mutableStateOf(viewModel.uiPrefs.frpServerPort.value.toString()) }
    var frpToken by remember { mutableStateOf(viewModel.uiPrefs.frpToken.value) }
    var remotePort by remember { mutableStateOf(viewModel.uiPrefs.frpRemotePort.value.toString()) }

    LaunchedEffect(Unit) {
        controller.refreshTailscale()
        // 已保存访问密钥时自动恢复登录，避免每次进应用都重新输密钥
        controller.ensureLogin()
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        M3EScreenHeader(
            title = t("公网与远程"),
            subtitle = t("Tailscale / 樱花映射 / 自建 frp / 远程管理"),
            leading = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("返回"))
                }
            },
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = M3Spacing.screenMargin),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { controller.refreshAll() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(t("一键刷新"))
            }
            Text(
                t("Tailscale / 樱花隧道 / frps 连通性"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = M3Spacing.screenMargin)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
        ) {
            // ── Tailscale ──
            M3ECard(
                variant = M3ECardVariant.Filled,
                title = t("Tailscale（推荐，零配置组网）"),
                titleIcon = Icons.Filled.Public,
                supporting = if (!tailscale.installed) {
                    t("没检测到 Tailscale App。装好并登录同一账号后，朋友用你的 100.x 地址就能直连。")
                } else if (tailscale.connected) {
                    t("已检测到 Tailscale 接口：%s", tailscale.ips.joinToString(", ").ifBlank {
                        tailscale.interfaces.joinToString(", ")
                    })
                } else {
                    t("已安装 Tailscale，但没读到 100.x 地址（可能没连接，或系统隐藏了 VPN 地址）。")
                },
                content = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tailscale.installed) {
                            Button(onClick = { controller.openTailscale() }) { Text(t("打开 Tailscale")) }
                        } else {
                            Button(onClick = { openUrl(NetworkAccess.TAILSCALE_DOWNLOAD_URL) }) {
                                Text(t("下载 Tailscale"))
                            }
                        }
                        TextButton(onClick = { controller.refreshTailscale() }) { Text(t("重新检测")) }
                    }
                    tailscale.primaryIp?.let { ip ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t("朋友连接地址：%s:%s", ip, instance?.let { controller.localPortOf(it) } ?: 25565),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                clipboard.setText(
                                    AnnotatedString("${ip}:${instance?.let { controller.localPortOf(it) } ?: 25565}"),
                                )
                            }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(t("复制"))
                            }
                        }
                    }
                    Text(
                        t("Tailscale 不需要端口映射；本机与朋友都要装客户端并登录同一 tailnet。"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            // ── 樱花映射 ──
            M3ECard(
                variant = M3ECardVariant.Outlined,
                title = t("樱花映射（SakuraFrp）"),
                titleIcon = Icons.Filled.Router,
                supporting = t("在应用内用访问密钥登录，选一条隧道即可启动 frpc；配置由樱花 API 直接生成。"),
                content = {
                    if (sakura.token.isBlank()) {
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it.trim() },
                            label = { Text(t("访问密钥（樱花面板 → 账户 → 访问密钥）")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { controller.loginSakura(tokenInput) },
                                enabled = !sakura.loading,
                            ) {
                                Text(if (sakura.loading) t("登录中…") else t("登录"))
                            }
                            TextButton(onClick = { openUrl("https://www.natfrp.com/user/") }) {
                                Text(t("打开樱花面板"))
                            }
                        }
                        Text(
                            t("登录状态会保持：访问密钥加密存在本机，下次进入自动恢复，只有点「退出登录」才会清除。"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            t("已登录：%s", sakura.user?.name ?: t("已保存登录")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (sakura.restoring || (sakura.loading && sakura.tunnels.isEmpty())) {
                            Text(
                                t("正在用已保存的访问密钥恢复登录…"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { controller.refreshSakura() }) { Text(t("刷新")) }
                            TextButton(onClick = { controller.logoutSakura() }) { Text(t("退出登录")) }
                            if (sakura.tunnels.isEmpty() && !sakura.loading) {
                                TextButton(onClick = { controller.ensureLogin() }) { Text(t("重试")) }
                            }
                        }
                        Text(
                            t("选择隧道（%s 条）：", sakura.tunnels.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (sakura.tunnels.isEmpty()) {
                            Text(
                                t("账号下还没有隧道。先去樱花面板创建一条 TCP 隧道（本地地址 127.0.0.1，本地端口填服务器端口）。"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            M3EConnectedList(count = sakura.tunnels.size) { index, shape ->
                                val tunnel = sakura.tunnels[index]
                                SakuraTunnelRow(
                                    tunnel = tunnel,
                                    selected = sakura.selectedId == tunnel.id,
                                    shape = shape,
                                    onClick = { controller.selectTunnel(tunnel.id) },
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Button(
                                onClick = { instance?.let { controller.startSakura(it) } },
                                enabled = instance != null && sakura.selectedId != null && !busy,
                            ) { Text(t("启动隧道")) }
                            TextButton(onClick = { controller.stopTunnel() }, enabled = frpcState.running) {
                                Text(t("停止"))
                            }
                            if (busy) {
                                Text(
                                    t("下载/启动中 %s%%", (progress * 100).toInt()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    sakura.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    sakura.message.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    // 樱花官方规则：公开游戏服务器只能用"多线"节点
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        Icon(
                            Icons.Filled.WarningAmber,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            t("若服务器要公开宣传，樱花规则要求使用「多线」节点（节点 flag 含 2048），否则可能封号且不退款。"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                },
            )

            // ── 自建 frp ──
            M3ECard(
                variant = M3ECardVariant.Outlined,
                title = t("自建 frp（有自己的 frps 时最稳）"),
                titleIcon = Icons.Filled.Router,
                supporting = t("填 frps 地址与令牌，应用用同一个 frpc 二进制开一条 TCP 隧道。"),
                content = {
                    OutlinedTextField(
                        value = serverAddr,
                        onValueChange = { serverAddr = it.trim() },
                        label = { Text(t("frps 地址")) },
                        placeholder = { Text("frp.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = serverPort,
                            onValueChange = { s -> serverPort = s.filter { it.isDigit() }.take(5) },
                            label = { Text(t("frps 端口")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = remotePort,
                            onValueChange = { s -> remotePort = s.filter { it.isDigit() }.take(5) },
                            label = { Text(t("远程端口")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = frpToken,
                        onValueChange = { frpToken = it },
                        label = { Text(t("frp 令牌（auth.token，可留空）")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        t(
                            "本地端口：%s（来自 server.properties / 基岩版默认 19132）",
                            instance?.let { controller.localPortOf(it) } ?: 25565,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                instance?.let {
                                    controller.startCustomFrp(
                                        it,
                                        serverAddr,
                                        serverPort.toIntOrNull() ?: 7000,
                                        frpToken,
                                        remotePort.toIntOrNull() ?: 25565,
                                    )
                                }
                            },
                            enabled = instance != null && serverAddr.isNotBlank() && !busy,
                        ) { Text(t("启动 frp")) }
                        TextButton(onClick = { controller.stopTunnel() }, enabled = frpcState.running) {
                            Text(t("停止"))
                        }
                        TextButton(onClick = {
                            controller.probeFrp(serverAddr, serverPort.toIntOrNull() ?: 7000)
                        }) { Text(t("检测")) }
                    }
                    if (frpProbe.checking) {
                        Text(
                            t("正在检测 frps…"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (frpProbe.reachable != null) {
                        Text(
                            if (frpProbe.reachable == true) {
                                t("frps 可达（%s ms）", frpProbe.latencyMs ?: 0)
                            } else {
                                t("frps 不可达：%s", frpProbe.detail)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (frpProbe.reachable == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    frpMessage.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
            )

            // ── 远程管理（复用客户端同步的 HTTP 服务）──
            M3ECard(
                variant = M3ECardVariant.Filled,
                title = t("远程管理面板"),
                titleIcon = Icons.Filled.Public,
                supporting = t(
                    "开启后可在浏览器里远程看状态/日志、发命令、启停服务端。" +
                        "把「客户端同步」的端口经 Tailscale 或 frp 暴露出去即可。",
                ),
                content = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { instance?.let { viewModel.startModSync(it) } },
                            enabled = instance != null && !sync.running,
                        ) { Text(t("开启")) }
                        TextButton(
                            onClick = { instance?.let { viewModel.stopModSync(it) } },
                            enabled = sync.running,
                        ) { Text(t("关闭")) }
                    }
                    if (sync.running) {
                        Text(
                            sync.shareUrl?.substringBefore("?") ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            t("接口：/api/status · /api/logs?n=200 · /api/command?cmd=list · /api/start · /api/stop（都要带 ?token=）"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            t("未开启。端口与令牌在「客户端同步」卡片里配置。"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )

            // ── 远程串流说明（Sunshine 是 x86_64 桌面程序，不能在手机跑）──
            M3ECard(
                variant = M3ECardVariant.Outlined,
                title = t("远程串流（Sunshine / Moonlight）"),
                titleIcon = Icons.Filled.Public,
                supporting = t(
                    "Sunshine 是 x86_64 的桌面游戏串流服务端（需要 GPU 编码），无法在手机 ARM 上运行。" +
                        "想在手机上看电脑画面：在 PC 装 Sunshine（或其分支 foundation-sunshine）," +
                        "手机装 Moonlight，两者都接入同一个 Tailscale 即可。",
                ),
                content = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            openUrl("https://github.com/AlkaidLab/foundation-sunshine")
                        }) { Text(t("foundation-sunshine")) }
                        TextButton(onClick = {
                            openUrl("https://github.com/LizardByte/Sunshine")
                        }) { Text(t("Sunshine 官方")) }
                        TextButton(onClick = {
                            openUrl("https://moonlight-stream.org/")
                        }) { Text(t("Moonlight")) }
                    }
                },
            )

            // ── frpc 日志 ──
            if (frpcState.log.isNotEmpty() || frpcState.running || frpcState.error != null) {
                M3ECard(
                    variant = M3ECardVariant.Outlined,
                    title = t("隧道日志"),
                    supporting = if (frpcState.running) {
                        t("运行中 · %s", frpcState.provider)
                    } else {
                        t("未运行")
                    },
                    content = {
                        if (busy) {
                            WavyLinearProgress(progress = progress.takeIf { it > 0f }, modifier = Modifier.fillMaxWidth())
                        }
                        frpcState.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            frpcState.log.takeLast(40).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 40,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SakuraTunnelRow(
    tunnel: SakuraTunnel,
    selected: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
) {
    M3EListItem(
        headline = "#${tunnel.id} ${tunnel.name}",
        supporting = tunnel.summary,
        shape = shape,
        onClick = onClick,
        leadingIcon = if (selected) Icons.Filled.Check else Icons.Filled.Router,
        iconContainer = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        trailing = {
            Surface(
                shape = CircleShape,
                color = if (tunnel.online) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (tunnel.online) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    if (tunnel.online) t("在线") else t("离线"),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        },
    )
}
