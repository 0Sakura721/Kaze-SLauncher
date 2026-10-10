package com.kaze.newage.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import com.kaze.newage.core.console.CountFormat
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import com.kaze.newage.core.monitor.ProcessStats

/**
 * 控制台：实时日志（主题化深色终端）+ 命令输入 —— 本应用的主工作台。
 *
 * 版式来自 m3e-canvas 生成的 `docs/m3e/prompt-控制台.md`：
 *   屏幕头（实例切换 + 实例摘要 + 状态胶囊）→ 日志动作行（复制/保存/跟随/清空/筛选 + 行数）
 *   → 筛选搜索栏（默认收起，动作行开关展开）→ 快捷命令行（运行中或有命令才出现）
 *   → 终端画布 → 命令输入
 *
 * 控件默认收起/条件出现的原则：控制台的主工作区是日志本身，DIY 控件全部常驻的话
 * 三条行加输入框要吃掉小半屏（真机 360dp 宽度下尤其明显）。
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

/**
 * 日志级别过滤档（控制台 DIY 包，单选）。
 *
 * 「信息」档收编 System / Command 行：系统提示与命令回显属于"正常流水"，
 * 排查时丢了会误导（例如「> 正在停止服务器…」不见了，会以为停止没生效）。
 */
private enum class ConsoleLevelFilter(val label: String) {
    ALL("全部"),
    INFO("信息"),
    WARN("警告"),
    ERROR("错误");

    fun matches(type: LineType): Boolean = when (this) {
        ALL -> true
        INFO -> type == LineType.Info || type == LineType.System || type == LineType.Command
        WARN -> type == LineType.Warn
        ERROR -> type == LineType.Error
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConsoleScreen(viewModel: AppViewModel, onOpenAi: () -> Unit = {}) {
    val lines by viewModel.consoleLines.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    // 回读更早日志：列表顶部会多一个条目，跟随滚动的索引要跟着偏移
    val hasMoreOlder by viewModel.hasMoreOlder.collectAsStateWithLifecycle()
    val loadingOlder by viewModel.loadingOlder.collectAsStateWithLifecycle()
    val headerCount = if (hasMoreOlder) 1 else 0
    val scope = rememberCoroutineScope()
    val tone = serverState.toTone()

    // ── 控制台 DIY 偏好 ──
    // SettingsPrefs 持有的就是 Compose State，直接读即随写入自动重组。
    val uiPrefs = viewModel.uiPrefs
    val fontSp = uiPrefs.consoleFontSp.floatValue
    val showTimestamps = uiPrefs.consoleTimestamps.value
    val quickCommands = uiPrefs.consoleQuickCommands.value
    var showDisplaySettings by remember { mutableStateOf(false) }
    var showQuickCommandEditor by remember { mutableStateOf(false) }
    // 时间戳只在开的时候才格式化：SimpleDateFormat 构造不便宜
    val tsFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    // 级别过滤 + 搜索（会话态：重进页面恢复「全部 / 空」）
    var levelFilter by remember { mutableStateOf(ConsoleLevelFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    // 筛选/搜索栏默认收起：控制台的主工作区是日志本身，常驻三行控件太挤。
    // 收起时条件继续生效 —— 动作行按钮保持选中色、行数显示「命中/总数」
    var showFilterBar by remember { mutableStateOf(false) }
    val filterActive = levelFilter != ConsoleLevelFilter.ALL || searchQuery.isNotBlank()

    // 过滤结果必须放在派生 State 里：跟随滚动靠 snapshotFlow 追踪 State 读取，
    // 普通 remember 计算值不是 State，过滤后的行数变化滚动收不到通知。
    // 无过滤时直接透传原列表 —— 5 万行 × 每秒几十行的全量 contains 是白烧 CPU。
    val shown by remember {
        derivedStateOf {
            val q = searchQuery.trim()
            if (levelFilter == ConsoleLevelFilter.ALL && q.isEmpty()) {
                lines
            } else {
                lines.filter { line ->
                    levelFilter.matches(line.type) &&
                        (q.isEmpty() || line.text.contains(q, ignoreCase = true))
                }
            }
        }
    }

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
    // 滚动目标一律用过滤后的 shown：过滤/搜索生效时，列表渲染的就是它
    suspend fun scrollToNewest(animated: Boolean) {
        if (shown.isEmpty()) return
        autoScrolling = true
        try {
            if (animated) {
                listState.animateScrollToItem(shown.size - 1 + headerCount)
            } else {
                listState.scrollToItem(shown.size - 1 + headerCount)
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
        snapshotFlow { shown.size }.collect { size ->
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
        if (visible && !wasImeVisible && follow && shown.isNotEmpty()) {
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
                    // 真机上这段会被截断成 "Java 17 · ··"：去掉冗余的 "MC " 与内存上限
                    // （上限在实例详情里，实际占用就在下面那一行监控里）
                    append(current.mcVersion.ifBlank { "自定义" })
                    append(" · ")
                    append(current.coreType.displayName)
                    append(" · Java ")
                    append(current.javaMajor)
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

        // 服务端进程的 CPU / 内存占用 + 整机可用内存（每秒刷新；未启动时为空 → 不占位）
        val stats by viewModel.procStats.collectAsStateWithLifecycle()

        // ── 性能监控：CPU / 内存占用（独立一行，只在运行中出现）──
        //
        // 为什么单独占一行，而不是塞进下面的动作行：
        //  动作行左边是 4 个 48dp 圆钮，连间距共 224dp（360dp 屏上只剩 104dp），
        //  行数自己还要 ~45dp。监控此前跟行数同处一个 Row，最多只分得到
        //  「剩余宽度的一半」—— 旁边还有一个 `Spacer(weight(1f))` 在跟它抢同一份
        //  空间，真机上只剩 20~50dp，被截成 "CP…"，看上去就像它在挤压行数。
        //  挪出来后：监控吃满整行宽度、完整可读；动作行只剩「按钮 + 行数」，
        //  行数谁也不用让（Compose 的 Row 先测所有不带 weight 的子项，
        //  带 weight 的最后才分剩余空间，所以它本来也抢不走行数的宽度）。
        //
        // 运行中就**一定**渲染这一行：此前是 stats == null 就整行不显示，
        // 而 pid 在 proot 下匹配不上 → 用户看到的是"控制台根本没有占用数据"。
        // 采样还没出来时显示"采样中"（带诊断计数），比整行消失可诊断得多 ——
        // 而这条诊断串很长，只有在整行宽度下才读得全，这也是它必须独占一行的原因之一。
        if (serverState == ServerState.Running) {
            val s = stats
            // 服务端 RSS 占**整机内存**的比例（totalMemKb 来自同一次采样，见 ProcessStats.Reading）
            val memPct = if (s != null && s.totalMemKb > 0 && s.rssKb >= 0) {
                s.rssKb.toFloat() / s.totalMemKb * 100f
            } else {
                0f
            }
            Text(
                buildString {
                    if (s == null) {
                        // 带上诊断计数：读不到 /proc 和匹配规则不对，修法完全不同
                        append("CPU 采样中…（").append(ProcessStats.lastDiag.ifBlank { "扫描中" })
                            .append("）")
                    } else {
                        append("CPU ").append(fmt("%.0f", s.cpuPercent)).append("%")
                        // 单核占用率可以超过 100%，所以把"用了几个核"也说清楚 ——
                        // 否则 8 核机器上的 12% 会让人以为很闲，其实是吃满了一个核
                        if (s.coresUsed >= 1.05f) append("（").append(fmt("%.1f", s.coresUsed)).append("核）")
                        if (s.rssKb < 0) {
                            // 读不到就直说，**不要显示成 0.00 GB** —— 那看起来像"服务端不占内存"，
                            // 真机上就是这么被误判成"ram 没读出来"的（其实多半是选错了进程）。
                            // 顺带把 pid 打出来，下一次一眼就能核对。
                            append(" · 内存读不到（pid ").append(s.pid).append("）")
                        } else {
                            append(" · 内存 ").append(fmt("%.2f", s.rssKb / 1024f / 1024f)).append("G")
                            append("（").append(fmt("%.0f", memPct)).append("%）")
                        }
                        // 整机还剩多少可用内存：手机上服务端"莫名其妙崩"多半是 OOM，
                        // 把剩余量摆在服务端占用旁边，一眼能看出还有多少余量。
                        if (s.availMemKb >= 0) {
                            // 简写"可用 3.2G"：真机 360dp 宽度下，"整机可用 3.20 GB" 会被截成 "整机可…"
                            append(" · 可用 ").append(fmt("%.1f", s.availMemKb / 1024f / 1024f)).append("G")
                            if (s.lowMemory) append("（低内存）")
                        }
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = M3Spacing.screenMargin, vertical = 2.dp),
            )
        }

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
            // 筛选/搜索/显示设置的开关。选中色在「栏展开」或「条件仍在生效」时都点亮：
            // 收起状态下的生效过滤靠它和行数里的「命中/总数」提示，否则用户会以为列表坏了
            ConsoleAction(
                icon = Icons.Filled.FilterList,
                label = if (showFilterBar) "收起筛选与搜索" else "筛选与搜索",
                active = showFilterBar || filterActive,
            ) { showFilterBar = !showFilterBar }
            Spacer(Modifier.weight(1f))
            // 行数按「万 / 百万 / 千万 / 亿」缩写（一位小数），点一下看精确数字与日志体积。
            // 内存里不可能真的无限（120 万行 ≈ 150 MB），真正的全量在磁盘上的 console-output.log，
            // 所以这里必须能告诉用户"完整日志多大、在哪"。
            var showCountDetail by remember { mutableStateOf(false) }
            Text(
                // 过滤/搜索生效时同时报出"命中 / 总数"，否则用户以为过滤没起作用
                if (shown === lines) "${CountFormat.short(lines.size.toLong())} 行"
                else "${CountFormat.short(shown.size.toLong())} / ${CountFormat.short(lines.size.toLong())} 行",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 行数是常驻信息，任何情况下都不许被压成两行：
                // softWrap=false + maxLines=1 → 宽度不够时省略，而不是折成两行；
                // 另外 Row 先测所有不带 weight 的子项（本项就是），带 weight 的最后
                // 才分剩余空间 —— 所以它的宽度也不会被任何兄弟抢走。
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable { showCountDetail = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            if (showCountDetail) {
                val dropped = viewModel.consoleDroppedCount()
                val logFile = viewModel.consoleLogFile()
                val logSize = logFile?.takeIf { it.isFile }?.length() ?: 0L
                AlertDialog(
                    onDismissRequest = { showCountDetail = false },
                    title = { Text("控制台统计") },
                    text = {
                        Column {
                            Text("内存中保留：${lines.size} 行（${CountFormat.short(lines.size.toLong())}）")
                            if (dropped > 0) {
                                Text("已滚出内存：$dropped 行（${CountFormat.short(dropped.toLong())}）")
                            }
                            Text(
                                "完整日志：${if (logFile?.isFile == true) CountFormat.bytes(logSize) else "（暂无文件）"}",
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "完整日志一直在写盘，控制台只是滑动窗口：\n" +
                                    (logFile?.absolutePath ?: "（未选择实例）"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCountDetail = false }) { Text("知道了") }
                    },
                )
            }
        }

        // ── 筛选与搜索栏：级别 chip（单选）+ 搜索 + 显示设置（字号 / 时间戳）──
        //
        // 默认收起（见动作行「筛选」开关）：控制台的主工作区是日志本身，
        // DIY 控件全部常驻的话三条行加输入框要吃掉小半屏。展开时横向可滚动，窄屏也不截断。
        AnimatedVisibility(
            visible = showFilterBar,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = M3Spacing.screenMargin, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConsoleLevelFilter.entries.forEach { f ->
                    FilterChip(
                        selected = levelFilter == f,
                        onClick = { levelFilter = f },
                        label = { Text(f.label) },
                    )
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.width(160.dp).height(48.dp),
                    placeholder = { Text("搜索日志…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "清除搜索",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                )
                ConsoleAction(Icons.Filled.TextFields, "控制台显示设置（字号 / 时间戳）") {
                    showDisplaySettings = true
                }
            }
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
                if (hasMoreOlder) {
                    item(key = "load-older") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !loadingOlder) { viewModel.loadOlderConsole() }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (loadingOlder) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (loadingOlder) "正在读取更早的日志…"
                                else "加载更早的日志（内存只留最近 5 万行）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
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
                // 内存里有日志、但被级别/搜索过滤光了的空态要与"从未输出"区分开：
                // 前者该提示换条件，后者才是"启动后日志会来"
                if (lines.isNotEmpty() && shown.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillParentMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = consoleLineColor(LineType.System).copy(alpha = 0.45f),
                                modifier = Modifier.size(44.dp),
                            )
                            Text(
                                "当前过滤条件下没有日志行\n换个级别，或清空搜索关键词",
                                color = consoleLineColor(LineType.System).copy(alpha = 0.75f),
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
                items(shown, key = { it.seq }) { line ->
                    val color = consoleLineColor(line.type)
                    Text(
                        // 时间戳是暗色前缀 span：看得见"这行几点打的"，又不与正文抢注意力
                        if (showTimestamps) {
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = color.copy(alpha = 0.5f))) {
                                    append("[${tsFormat.format(Date(line.timestamp))}] ")
                                }
                                append(line.text)
                            }
                        } else {
                            AnnotatedString(line.text)
                        },
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = fontSp.sp),
                    )
                }
            }

            // 手动上滑暂停跟随后浮出的「回到底部」：点一下恢复跟随并跳到最新一行
            JumpToBottomPill(
                visible = !follow && shown.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                follow = true
                scope.launch { scrollToNewest(animated = true) }
            }
        }

        // ── 快捷命令 chip 行：点 = 发送，长按 = 删除，「＋」打开编辑器 ──
        //
        // 只在「运行中」或「已有命令」时出现：它服务的动作（发命令）停止态做不了，
        // 空列表在停止态更没有意义 —— 这两行让位给日志区。入口收敛为：服务端跑起来
        // 后点「＋ 快捷命令」开始配（发送沿用输入框同一条 Running 守卫）。
        if (serverState == ServerState.Running || quickCommands.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = M3Spacing.screenMargin, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(quickCommands, key = { it }) { cmd ->
                    QuickCommandChip(
                        command = cmd,
                        enabled = serverState == ServerState.Running,
                        onClick = { viewModel.sendCommand(cmd) },
                        onRemove = { uiPrefs.setConsoleQuickCommands(quickCommands - cmd) },
                    )
                }
                item {
                    QuickCommandAddChip(onClick = { showQuickCommandEditor = true })
                }
            }
        }

        // ── 命令输入：描边输入框 fused 主色填充发送键（相连按钮组：外侧外角全圆、内侧 8dp）──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = M3Spacing.screenMargin, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // AI 助手入口：永远可点，与运行状态、甚至与「有没有实例」都无关。
            //
            // 之前这里写的是 enabled = current != null，即一个实例都没建时点不进去 ——
            // 而那恰恰是最需要问 AI 的时候（装哪个服务端核心、Java 选哪个版本、为什么起不来）。
            // 出错的现场更是如此：入口被禁用等于在最需要求助的时刻把求助关掉。
            // 需要实例的能力自己降级（取不到实例就如实说取不到），入口不该替它做判断。
            // 也不跟随输入框的 Running 守卫（发命令才需要 Running）。跳全屏 AI 页。
            // 输入行是这一屏的主行动，保持 48dp（动作行五个钮已降到 44dp）。
            ConsoleAction(Icons.Filled.SmartToy, "AI 助手", enabled = true, size = 48.dp) {
                onOpenAi()
            }
            Spacer(Modifier.width(6.dp))

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
                        if (serverState == ServerState.Running) "输入命令（stop / op…）"
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

    // 两个 DIY 弹窗挂在 Column 外：它们是窗口级 UI，不参与版式
    if (showDisplaySettings) {
        DisplaySettingsDialog(
            fontSp = fontSp,
            timestamps = showTimestamps,
            onFont = { uiPrefs.setConsoleFontSp(it) },
            onTimestamps = { uiPrefs.setConsoleTimestamps(it) },
            onDismiss = { showDisplaySettings = false },
        )
    }
    if (showQuickCommandEditor) {
        QuickCommandEditorDialog(
            initial = quickCommands,
            onSave = { list ->
                uiPrefs.setConsoleQuickCommands(list)
                showQuickCommandEditor = false
            },
            onDismiss = { showQuickCommandEditor = false },
        )
    }
}

/**
 * 控制台动作：圆底图标按钮（与主页的次要动作同一套语言）。
 * 不使用裸 IconButton —— 圆底容器让动作在深色终端上方保持同一视觉分量。
 *
 * [size] 默认 48dp；动作行加了第五个钮（筛选）后整体降到 44dp 给行数留位 ——
 * 360dp 屏上五个 48dp 钮 + 间距会把「…行」挤到截断。输入行的主行动（AI）仍是 48dp。
 */
@Composable
private fun ConsoleAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    /** 开关型动作（跟随）的选中态：用 secondaryContainer 表达「正在生效」 */
    active: Boolean = false,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(size).clip(CircleShape),
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

/**
 * 控制台显示设置：字号滑杆 + 时间戳开关。版式照搬 SettingsScreen 的 SliderDialog
 * （滑杆塞进列表行会撑变形，点开才出现），多一个 Switch 行 ——
 * 「字号」与「时间戳」同属"怎么显示"，拆两个弹窗反而把一个概念撕成两处入口。
 */
@Composable
private fun DisplaySettingsDialog(
    fontSp: Float,
    timestamps: Boolean,
    onFont: (Float) -> Unit,
    onTimestamps: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("控制台显示") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "字号 ${fontSp.toInt()}sp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = fontSp,
                    onValueChange = onFont,
                    valueRange = 10f..20f,
                    steps = 9,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "时间戳前缀",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = timestamps, onCheckedChange = onTimestamps)
                }
                Text(
                    "时间戳为 [HH:mm:ss] 前缀，只影响控制台显示，不写入日志文件。" +
                        "从磁盘回读的更早日志没有时间记录，显示的是载入时间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/**
 * 快捷命令编辑器：在本地副本上增删，点「保存」才落盘（取消不改动现有 chip）。
 * 条数与单条长度的上限真正执行在 SettingsPrefs.setConsoleQuickCommands，这里只负责提示。
 */
@Composable
private fun QuickCommandEditorDialog(
    initial: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var list by remember { mutableStateOf(initial) }
    var draft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快捷命令") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "点 chip 直接把命令发给服务端；长按 chip 也可快速删除。最多 12 条。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (list.isEmpty()) {
                    Text(
                        "还没有快捷命令，在下面输入第一条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        itemsIndexed(list) { index, cmd ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    cmd,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { list = list.filterIndexed { i, _ -> i != index } }
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除 $cmd",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("新命令，如 list / say hi", style = MaterialTheme.typography.bodySmall)
                        },
                        singleLine = true,
                    )
                    TextButton(
                        enabled = draft.isNotBlank() && list.size < 12,
                        onClick = {
                            list = list + draft.trim()
                            draft = ""
                        },
                    ) { Text("添加") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(list) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 快捷命令 chip：点 = 发送；长按 = 从列表删除（编辑器里也有删除入口，
 * 长按只是高频用户的快捷路径）。服务端未运行时整颗呈禁用色（点不了）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickCommandChip(
    command: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .clip(M3Shape.groupSingle(100f))
            .combinedClickable(
                onClick = onClick,
                onClickLabel = "发送 $command",
                onLongClick = onRemove,
                onLongClickLabel = "删除快捷命令 $command",
            ),
        shape = M3Shape.groupSingle(100f),
        color = if (enabled) scheme.secondaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (enabled) {
            scheme.onSecondaryContainer
        } else {
            scheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
    ) {
        Text(
            command,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 上限宽度：200 字符的命令不截的话 chip 会撑到 1400dp，把整行滚动都变得没有意义
            modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** 「＋ 快捷命令」chip：列表为空时它就是唯一入口，所以常驻行尾、不受运行状态限制 */
@Composable
private fun QuickCommandAddChip(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(M3Shape.groupSingle(100f)),
        shape = M3Shape.groupSingle(100f),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "快捷命令",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** 指标文本的小工具：统一用 US locale，避免某些系统区域把小数点写成逗号 */
private fun fmt(pattern: String, v: Float): String = String.format(java.util.Locale.US, pattern, v)