package com.kaze.newage.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.console.LineType
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.ExpressiveLoadingGlyph
import com.kaze.newage.ui.components.ExpressiveLoadingIndicator
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3EStatusChip
import com.kaze.newage.ui.components.StatusTone
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.theme.consoleBackgroundColor
import com.kaze.newage.ui.theme.consoleLineColor
import com.kaze.newage.ui.theme.statusPalette
import com.kaze.newage.ui.toLabel
import com.kaze.newage.ui.toTone
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.kaze.newage.core.console.CONSOLE_MAX_LINES

/**
 * 控制台：实时日志（主题化深色终端）+ 命令输入 —— 本应用的主工作台。
 *
 * 版式来自 m3e-canvas 生成的 `docs/m3e/prompt-控制台.md`：
 *   屏幕头（实例切换 + 实例摘要 + 状态胶囊）→ 日志动作行 → 终端画布 → 命令输入
 *
 * 两条刻意保留的旧设计：
 *  - 日志面板是「终端画布」而不是普通卡片：深色底（consoleBackgroundColor）、等宽字体、
 *    按日志级别着色（consoleLineColor），除 M3E 的 20dp 圆角外不加描边/标题/底色。
 *  - 命令输入是描边输入框 fused 上主色填充发送键（相连按钮组：外角全圆、内角 8dp）。
 */
/**
 * 自动跟随滚动的"平滑/直接跳"分界：目标与当前可见行相差在这个范围内才走动画。
 * 差得多（切页回来、暂停跟随后恢复）直接跳到底，动画要走几百行会让人以为没反应。
 */
private const val SCROLL_ANIMATE_MAX_ITEMS = 30

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConsoleScreen(viewModel: AppViewModel) {
    val lines by viewModel.consoleLines.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val tone = serverState.toTone()

    // 停止时清空输入框残留：保证 placeholder「服务端运行后可输入命令」恒定可见
    LaunchedEffect(serverState) {
        if (serverState != ServerState.Running) input = ""
    }

    val palette = statusPalette()
    val stateColor = when (tone) {
        StatusTone.Running -> palette.running
        StatusTone.Busy -> palette.busy
        StatusTone.Idle -> palette.idle
        StatusTone.Error -> palette.error
    }

    // 实例切换器（多开：每实例独立控制台）
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val currentInstanceId by viewModel.currentInstanceId.collectAsStateWithLifecycle()
    val current = instances.firstOrNull { it.id == currentInstanceId }
    val states by viewModel.serverStates.collectAsStateWithLifecycle()
    var showSwitcher by remember { mutableStateOf(false) }

    // 保存日志：SAF 选择目标位置，一次性导出当前实例完整日志。
    // MIME 用 application/octet-stream：vivo 对 text/plain 会把 .log 自动改名 .log.txt
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.saveConsoleLog(it) }
    }

    // 键盘/底栏动态避让：
    // 输入框位置 = 窗口底 - max(imeInset, 96dp) —— 键盘弹出时贴键盘顶（inset>96dp），
    // 收起动画期间 inset 连续减小，padding 同步补偿（96-inset），输入框恒不穿过底栏，
    // 平稳回到 96dp 位置。不能只用 isImeVisible 切换（收起动画期间 inset>0 仍为 true，
    // padding 保持 0 会让输入框沉到窗口底部穿过底栏再弹回）。
    // 96dp = M3Spacing.bottomBarSpace（常驻底栏占位）
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottomPad = (M3Spacing.bottomBarSpace - imeBottom).coerceAtLeast(0.dp)

    // 程序性滚动标记：自动滚底与键盘弹出重滚同样是 isScrollInProgress=true，
    // 不区分的话「用户上滑暂停跟随」会把自己的自动滚动误判成上滑，跟随被自己关掉
    var autoScrolling by remember { mutableStateOf(false) }
    suspend fun scrollToNewest(animated: Boolean) {
        if (lines.isEmpty()) return
        autoScrolling = true
        try {
            if (animated) {
                listState.animateScrollToItem(lines.size - 1)
            } else {
                listState.scrollToItem(lines.size - 1)
            }
        } finally {
            autoScrolling = false
        }
    }

    // 新日志自动滚到底部（可暂停跟随）。
    //
    // 这里**不能**用 LaunchedEffect(lines.size)：服务端在跑时 lines.size 几毫秒就变一次，
    // 每次变化都会取消上一个 effect —— 而 animateScrollToItem 是持续多帧的动画，
    // 被取消就停在半路。平时"人已经在底部、只差 1 行"看不出来；从别的页面切回来时
    // 差了几百行，动画永远走不完，日志就停在原地，用户得自己往下翻（实报）。
    //
    // 改成单个 snapshotFlow 收集器：块内顺序执行，进行中的滚动不会被新行打断。
    // 另外按距离区分：差得少就平滑滚（连续输出的观感），差得多就直接跳
    // （切页回来 / 暂停后恢复），不让人干等一次长动画。
    LaunchedEffect(listState, follow) {
        snapshotFlow { lines.size }.collect { size ->
            if (!follow || size == 0) return@collect
            val distance = (size - 1) - listState.firstVisibleItemIndex
            scrollToNewest(animated = distance in 1..SCROLL_ANIMATE_MAX_ITEMS)
        }
    }

    // 用户手势把列表从底部拖走（下方还有内容）就暂停跟随，终端右下角浮出「回到底部」。
    // 只暂停、不自动恢复：恢复走「回到底部」或动作行的跟随开关，用户的显式操作优先
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling && follow && listState.canScrollForward) follow = false
        }
    }

    // 键盘弹出瞬间重新滚到底：日志区被键盘压缩，最新一行可能被盖住。
    // 只在"无键盘→有键盘"沿触发一次（imeBottom 动画中连续变化，不重复滚动）
    var wasImeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeBottom) {
        val visible = imeBottom > 0.dp
        if (visible && !wasImeVisible && follow && lines.isNotEmpty()) {
            scrollToNewest(animated = false)
        }
        wasImeVisible = visible
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            // 底部空白承载常驻栏：bottomPad 随 IME inset 连续补偿（见上方注释），输入框不穿过底栏
            .padding(bottom = bottomPad)
    ) {
        // ── 屏幕头：实例切换（左）+ 实例摘要（中）+ 状态胶囊（右）──
        M3EScreenHeader(
            title = current?.name ?: "未选择实例",
            subtitle = if (current != null) {
                buildString {
                    append("MC ")
                    append(current.mcVersion.ifBlank { "自定义" })
                    append(" · ")
                    append(current.coreType.displayName)
                    append(" · Java ")
                    append(current.javaMajor)
                    append(" · ")
                    append(current.memoryMb)
                    append(" MB")
                }
            } else {
                "还没有服务端实例，去「服务端」页新建"
            },
            leading = {
                // 切换实例：多开时每个实例有独立控制台，入口放在屏幕头第一位
                Box {
                    IconButton(
                        onClick = { showSwitcher = true },
                        enabled = instances.isNotEmpty(),
                        // 不设 size：M3 默认 40dp 容器 + 48dp 触控区。
                        // 原来写死 28dp，连触控区一起缩到了 28dp，很难点中
                    ) {
                        Icon(
                            Icons.Filled.Dns,
                            contentDescription = "切换实例",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = showSwitcher,
                        onDismissRequest = { showSwitcher = false },
                        shape = M3Shape.medium,
                    ) {
                        instances.forEach { inst ->
                            val s = states[inst.id] ?: ServerState.Idle
                            // 每实例一个状态圆点：一眼看出哪个实例在跑，不必切过去看
                            val dot = when {
                                s == ServerState.Running -> palette.running
                                s.isBusy() -> palette.busy
                                s == ServerState.Error -> palette.error
                                else -> palette.idle
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                                        Column(Modifier.weight(1f)) {
                                            Text(inst.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                            Text(
                                                s.toLabel(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                },
                                trailingIcon = {
                                    if (inst.id == currentInstanceId) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "当前实例",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.selectInstance(inst)
                                    showSwitcher = false
                                },
                            )
                        }
                    }
                }
            },
            trailing = {
                M3EStatusChip(text = serverState.toLabel(), color = stateColor)
            },
        )

        // ── 日志动作行：复制 / 导出 / 跟随 / 清空 + 行数 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = M3Spacing.screenMargin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 复制日志：一键复制当前实例完整日志到剪贴板
            ConsoleAction(Icons.Filled.ContentCopy, "复制日志", enabled = current != null) {
                viewModel.copyConsoleLog()
            }
            ConsoleAction(Icons.Filled.Save, "保存日志", enabled = current != null) {
                val name = "${current?.name ?: "server"}-${
                    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                }.log"
                saveLauncher.launch(name)
            }
            ConsoleAction(
                icon = Icons.Filled.ArrowDownward,
                label = if (follow) "暂停自动滚动" else "恢复自动滚动",
                active = follow,
            ) {
                if (follow) {
                    follow = false
                } else {
                    // 恢复跟随顺手跳回最新一行：否则要等下一行日志进来才动
                    follow = true
                    scope.launch { scrollToNewest(animated = true) }
                }
            }
            ConsoleAction(Icons.Filled.Delete, "清空日志") { viewModel.clearConsole() }
            Spacer(Modifier.weight(1f))
            Text(
                // 到上限时明确标出来：日志仍在继续写盘，只是控制台不再往上堆
                if (lines.size >= CONSOLE_MAX_LINES) "${lines.size} 行（上限）"
                else "${lines.size} 行",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 终端画布（深色底 + 等宽 + 按级别着色，随主题微调）──
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin)
                .clip(M3Shape.largeIncreased)
                .background(consoleBackgroundColor())
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (lines.isEmpty()) {
                    item {
                        // 空态：形状变化的加载指示器（不是转圈）——启动中会转，停止/出错时定格成形状
                        Column(
                            Modifier.fillParentMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val emptyColor = consoleLineColor(LineType.System)
                            when (tone) {
                                StatusTone.Busy -> ExpressiveLoadingIndicator(
                                    size = 44.dp,
                                    color = emptyColor.copy(alpha = 0.7f),
                                )
                                StatusTone.Error -> ExpressiveLoadingGlyph(
                                    size = 44.dp,
                                    color = consoleLineColor(LineType.Error).copy(alpha = 0.8f),
                                    shapeIndex = 4,
                                )
                                // 空闲态用控制台图标而不是定格的 Oval 形状：
                                // 44dp、0.45 透明度的椭圆在空荡荡的日志区里读起来像渲染残渣，
                                // 不像"待命"。用「终端」图标既表意又和底栏图标同源。
                                else -> Icon(
                                    Icons.Filled.Terminal,
                                    contentDescription = null,
                                    tint = emptyColor.copy(alpha = 0.45f),
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                            Text(
                                if (tone == StatusTone.Busy) {
                                    "正在启动服务端，日志马上就来\n首次启动会自动生成 eula.txt 并接受条款后重启"
                                } else {
                                    "启动服务端后，日志将实时显示在这里\n首次启动会自动生成 eula.txt 并接受条款后重启"
                                },
                                color = emptyColor.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
                // 直接用 items(list) 而不是 items(count) 按下标回读：
                // count 是组合时定下的，而 key/content 里读的 lines 是**实时** State
                // （后台协程在 IO 线程整体替换它，切实例/清空时会变短），
                // 若替换正好落在组合与测量之间，lines[i] 就越界崩溃。
                items(lines, key = { it.seq }) { line ->
                    Text(
                        line.text,
                        color = consoleLineColor(line.type),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // 手动上滑暂停跟随后浮出的「回到底部」：点一下恢复跟随并跳到最新一行
            JumpToBottomPill(
                visible = !follow && lines.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                follow = true
                scope.launch { scrollToNewest(animated = true) }
            }
        }

        // ── 命令输入：描边输入框 fused 主色填充发送键（相连按钮组：外侧外角全圆、内侧 8dp）──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = M3Spacing.screenMargin, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 只有运行中才可发送：与按钮的可用性同源，键盘上的发送键也走同一守卫
            val sendEnabled = serverState == ServerState.Running && input.isNotBlank()
            fun submit() {
                if (serverState != ServerState.Running) return
                if (input.isNotBlank()) {
                    viewModel.sendCommand(input.trim())
                    input = ""
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (serverState == ServerState.Running) "输入命令（stop / op 玩家名 / say …）"
                        else "服务端运行后可输入命令"
                    )
                },
                singleLine = true,
                // 停止时禁用：避免能输入但发不出去，且残留文字顶掉 placeholder
                enabled = serverState == ServerState.Running,
                shape = M3Shape.groupFirst(56f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Surface(
                modifier = Modifier.size(56.dp).clip(M3Shape.groupLast(56f)),
                shape = M3Shape.groupLast(56f),
                color = if (sendEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (sendEnabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                enabled = sendEnabled,
                onClick = { submit() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * 控制台动作：48dp 圆底图标按钮（与主页的次要动作同一套语言）。
 * 不使用裸 IconButton —— 圆底容器让四个动作在深色终端上方保持同一视觉分量。
 */
@Composable
private fun ConsoleAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    /** 开关型动作（跟随）的选中态：用 secondaryContainer 表达「正在生效」 */
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(48.dp).clip(CircleShape),
        shape = CircleShape,
        color = if (active) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        contentColor = when {
            !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.38f)
            active -> scheme.onSecondaryContainer
            else -> scheme.onSurfaceVariant
        },
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * 终端右下角的「回到底部」：跟随暂停时浮出。
 *
 * 写成顶层私有组件而不就地展开，是因为外层是 Column —— Box 里的 AnimatedVisibility
 * 会被解析成 ColumnScope 的那个重载，编译不过（顶层函数没有隐式接收者，只有一种重载可用）。
 */
@Composable
private fun JumpToBottomPill(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.clip(M3Shape.groupSingle(36f)),
            shape = M3Shape.groupSingle(36f),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onClick,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("回到底部", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
