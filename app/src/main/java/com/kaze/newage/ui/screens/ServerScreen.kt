package com.kaze.newage.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.CoreCategory
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.M3EConnectedList
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3ESegmentedRow
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.toLabel

/**
 * 服务端 —— 多实例的列表与管理（多开 / 筛选 / 启停 / 导入）。
 *
 * 版式来自 m3e-canvas 生成的 `docs/m3e/prompt-服务端.md`：
 *   标题 → 一排动作按钮（新建 / 导入 jar）→ 分段筛选（全部 / 官方 / 性能 / 模组，带数量）
 *   → 实例列表（相连列表项：类型图标 + 名称 + 一行摘要 + 启停 / ⋮）→ 批量启停相连按钮组
 *
 * 与旧版的差别（行为一条没少，只是换皮）：
 *  - 不再有整页大卡框架，内容直接铺在屏幕上，分组交给 M3EListItem / M3ECard
 *  - 筛选 chip 从「横向滚动 + 右侧箭头」换成分段选择：四档一屏放得下，
 *    不再需要那个"还有更多"的指示按钮（窄屏上它本身就是个容易被忽略的补丁）
 *  - 实例行换成官方 72dp 列表项（相连列表：首尾 28dp、内部 8dp、间隔 3dp），
 *    信息压成一行摘要；EULA 未接受直接写在行内，不再藏进详情页
 *  - 启停按钮从 FilledIconButton 换成 40dp 圆形动作按钮：整行本身可点开详情，
 *    按钮必须明显小于整行，才不会和"点行"抢触控
 */
@Composable
fun ServerScreen(
    viewModel: AppViewModel,
    onOpenInstance: (ServerInstance) -> Unit,
    onNewServer: () -> Unit,
) {
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val states by viewModel.serverStates.collectAsStateWithLifecycle()
    val currentInstanceId by viewModel.currentInstanceId.collectAsStateWithLifecycle()
    val runningCount by viewModel.runningCount.collectAsStateWithLifecycle()

    // 分类筛选（Zalith VersionCategory）：全部 = 不过滤
    var filter by remember { mutableStateOf(InstanceFilter.ALL) }
    val category: CoreCategory? = filter.category
    val filtered = remember(instances, category) {
        if (category == null) instances
        else instances.filter { it.coreType.category == category }
    }
    fun countOf(cat: CoreCategory?): Int =
        if (cat == null) instances.size else instances.count { it.coreType.category == cat }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 复制挪到 ViewModel 的 IO 协程里：主线程同步拷贝几十 MB 的 jar 会 ANR，
        // 而且原来的 `catch (_: Exception) { }` 会把失败全吞掉（用户点完毫无反应）。
        uri?.let {
            viewModel.importJar(
                uri = it,
                name = "导入-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date()),
                memoryMb = 1024,
            )
        }
    }

    // 批量启停的可用性：启动只针对「既没跑也不在启动中」的实例，
    // 停止只针对运行中的实例（stop() 对停止中的实例是空操作，不必打扰）
    val startableCount = filtered.count { inst ->
        val state = states[inst.id] ?: ServerState.Idle
        state != ServerState.Running && !state.isBusy()
    }
    val stoppableCount = filtered.count { (states[it.id] ?: ServerState.Idle) == ServerState.Running }

    Column(Modifier.fillMaxSize()) {
        M3EScreenHeader(
            title = "服务端",
            subtitle = if (instances.isEmpty()) {
                "还没有服务端实例"
            } else {
                "共 ${instances.size} 个实例 · $runningCount 个运行中"
            },
        )

        // ── 一排动作：新建（填充）+ 导入 jar（色调），横向相连的按钮组 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin)
                .padding(bottom = M3Spacing.betweenGroups),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupButton(
                label = "新建",
                icon = Icons.Filled.Add,
                shape = M3Shape.groupFirst(56f),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                enabled = true,
                onClick = onNewServer,
            )
            GroupButton(
                label = "导入 jar",
                icon = Icons.Filled.FileOpen,
                shape = M3Shape.groupLast(56f),
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                enabled = true,
                // 用系统文件选择器挑一个已有的 server.jar 导入
                onClick = {
                    importLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                },
            )
        }

        // ── 分类筛选（每一档都带数量，选中的一档用容器色填充）──
        M3ESegmentedRow(
            options = InstanceFilter.entries,
            selected = filter,
            label = { f -> "${f.label} ${countOf(f.category)}" },
            onSelect = { filter = it },
            modifier = Modifier.padding(horizontal = M3Spacing.screenMargin),
            height = 40.dp,
        )

        // ── 实例列表（空态另说）──
        if (filtered.isEmpty()) {
            EmptyState(
                hasInstances = instances.isNotEmpty(),
                filterLabel = filter.label,
                onNewServer = onNewServer,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = M3Spacing.screenMargin),
                contentPadding = PaddingValues(
                    top = M3Spacing.betweenGroups,
                    bottom = M3Spacing.betweenGroups,
                ),
            ) {
                item { SectionLabel("实例（${filtered.size}）") }
                item {
                    M3EConnectedList(count = filtered.size) { index, shape ->
                        val instance = filtered[index]
                        InstanceRow(
                            instance = instance,
                            shape = shape,
                            selected = instance.id == currentInstanceId,
                            state = states[instance.id] ?: ServerState.Idle,
                            // 点行 = 选为当前实例 + 打开实例详情（与旧版一致）
                            onSelect = {
                                viewModel.selectInstance(instance)
                                onOpenInstance(instance)
                            },
                            onStart = { viewModel.startInstance(instance) },
                            onStop = { viewModel.stopInstance(instance) },
                            onDelete = { viewModel.removeInstance(instance) },
                        )
                    }
                }
            }
        }

        // ── 批量启停：只作用于当前筛选出来的实例 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin)
                .padding(top = M3Spacing.betweenGroups),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            GroupButton(
                label = "启动全部",
                icon = Icons.Filled.PlayArrow,
                shape = M3Shape.groupFirst(56f),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                enabled = startableCount > 0,
                onClick = {
                    // start() 自身按生命周期状态防重入（重复触发只记一条警告日志），
                    // 但这里仍然只对停止态的实例发起，免得白白改写 currentInstanceId
                    filtered.forEach { inst ->
                        val state = states[inst.id] ?: ServerState.Idle
                        if (state != ServerState.Running && !state.isBusy()) {
                            viewModel.startInstance(inst)
                        }
                    }
                },
            )
            GroupButton(
                label = "停止全部",
                icon = Icons.Filled.Stop,
                shape = M3Shape.groupLast(56f),
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                enabled = stoppableCount > 0,
                onClick = {
                    filtered.forEach { inst ->
                        if ((states[inst.id] ?: ServerState.Idle) == ServerState.Running) {
                            viewModel.stopInstance(inst)
                        }
                    }
                },
            )
        }

        // 常驻底栏占位：列表最后一项能滚到底栏之上，不被永久遮住
        Spacer(Modifier.height(M3Spacing.bottomBarSpace))
    }
}

// ───────────────────────────────────────────────
// 页面局部：筛选 / 空态 / 动作按钮
// ───────────────────────────────────────────────

/** 顶部分段筛选的档位（「全部」没有对应的 CoreCategory，所以不能直接用那个枚举） */
private enum class InstanceFilter(val label: String, val category: CoreCategory?) {
    ALL("全部", null),
    OFFICIAL("官方", CoreCategory.OFFICIAL),
    OPTIMIZED("性能", CoreCategory.OPTIMIZED),
    MODDED("模组", CoreCategory.MODDED),
}

/**
 * 相连按钮组里的一个按钮（必须写在 RowScope 里：它靠 weight 平分宽度）。
 *
 * 不可用时自己画成"禁用"的样子（surfaceContainerHighest + 38% 前景色），
 * 而不是把 onClick 置空——调用方仍然拿到点击事件，由它决定要不要真的执行。
 */
@Composable
private fun RowScope.GroupButton(
    label: String,
    icon: ImageVector,
    shape: Shape,
    container: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .clip(shape),
        shape = shape,
        color = if (enabled) container else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** 分组小标题：列表上方的一行说明文字 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = M3Spacing.betweenParts),
    )
}

/** 空态：一个实例都没有，或当前筛选档下一个都没有 —— 两种文案不一样 */
@Composable
private fun EmptyState(
    hasInstances: Boolean,
    filterLabel: String,
    onNewServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = M3Spacing.screenMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
        ) {
            Icon(
                if (hasInstances) Icons.Filled.Dns else Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                if (hasInstances) "「$filterLabel」下没有服务端" else "还没有服务端",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (hasInstances) {
                    "换一个筛选档，或者新建一个"
                } else {
                    "点上方「新建」下载 Vanilla / Paper 服务端，\n或「导入 jar」添加已有的 server.jar"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!hasInstances) {
                TextButton(onClick = onNewServer) { Text("新建服务端") }
            }
        }
    }
}

// ───────────────────────────────────────────────
// 实例行
// ───────────────────────────────────────────────

/**
 * 分类 → 图标圆底的容器色。
 *
 * 三档分类用同一个 primaryContainer 就分不出类型了（草图的「生存服」「创造服」用不同
 * 容器色区分），所以这里把 M3EListItem 的 iconContainer 按分类换成三个容器色角色；
 * 图标本身由组件固定用 onPrimaryContainer —— 三个容器色在浅色/深色下都与它保持对比。
 */
@Composable
private fun coreTypeContainer(category: CoreCategory): Color {
    val scheme = MaterialTheme.colorScheme
    return when (category) {
        CoreCategory.OFFICIAL -> scheme.primaryContainer
        CoreCategory.OPTIMIZED -> scheme.secondaryContainer
        CoreCategory.MODDED -> scheme.tertiaryContainer
        CoreCategory.IMPORT -> scheme.surfaceContainerHighest
    }
}

/** 分类 → 图标：官方/性能是"服务器"，模组加载是"扩展"，导入的 jar 是"文件" */
private fun coreTypeIcon(category: CoreCategory): ImageVector = when (category) {
    CoreCategory.MODDED -> Icons.Filled.Extension
    CoreCategory.IMPORT -> Icons.Filled.FileOpen
    else -> Icons.Filled.Dns
}

/** 实例状态文案（运行中 / 启动中 / 已停止 / 启动失败）——全应用统一词汇见 ServerStateUi */
private fun instanceStatusText(state: ServerState): String = when {
    state == ServerState.Running -> "运行中"
    state.isBusy() -> state.toLabel()
    state == ServerState.Error -> "启动失败"
    else -> "已停止"
}

/**
 * 实例行的一行摘要：核心 + MC 版本 + Java + 内存 + 状态。
 * 状态必须进摘要（不能只靠行尾按钮的图标）：EULA 之外，「启动中 / 启动失败」
 * 也要一眼看到，而不是点进详情才知道。
 */
private fun instanceSummary(instance: ServerInstance, state: ServerState): String = buildString {
    append(instance.coreType.displayName)
    // 不写 "MC "：版本号本身就是 MC 版本，这几个字符在这个摘要行里很值钱
    append(" · ")
    append(instance.mcVersion.ifBlank { "自定义" })
    append(" · Java ")
    append(instance.javaMajor)
    append(" · ")
    append(instance.memoryMb)
    append(" MB · ")
    append(instanceStatusText(state))
}

/**
 * 实例行：左侧类型图标 → 名称 / 摘要 / 状态胶囊 → 行尾启停按钮 + ⋮ 菜单。
 *
 * 无障碍：整个列表项是一个 `selectable(Role.RadioButton)` —— 它代表"当前实例"这一个
 * 单选状态。M3EListItem 自带的 clickable 必须去掉（onClick = null），否则同一个动作
 * 会多出一个可点区域与一套"按钮"语义，TalkBack 也读不出"已选中"。
 */
@Composable
private fun InstanceRow(
    instance: ServerInstance,
    shape: Shape,
    selected: Boolean,
    state: ServerState,
    onSelect: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = state == ServerState.Running
    // running **或** busy 都显示「停止」：启动流程里包含部署、装 Java、下载核心、
    // Forge --installServer，可能要几分钟。此前 busy 时给的是一个禁用的「启动」，
    // 用户在整个启动过程中没有任何中止手段，只能删掉实例。
    // stop() 已能处理"进程还没创建"的启动期（置取消标志让 start() 自己收尾）。
    val showStop = running || state.isBusy()
    val iconContainer = coreTypeContainer(instance.coreType.category)
    // EULA 未接受：需要用户动作的关键信息，必须直接在行内点出来（不能藏进详情页）。
    // 用 error 色正文而不是再加一个彩色胶囊——同一行里两个胶囊会互相抢注意力。
    val eulaMissing = !instance.eulaFile.exists() && !running

    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    // 删除实例必须二次确认：会连目录内全部世界数据一起删掉，一次误触即丢档不可恢复
    var confirmDelete by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
    ) {
        M3EListItem(
            headline = instance.name,
            supporting = instanceSummary(instance, state),
            leadingIcon = coreTypeIcon(instance.coreType.category),
            iconContainer = iconContainer,
            shape = shape,
            highlighted = selected,
            // 实例名是用户自己起的，可能很长：跑马灯而不是截断
            marqueeHeadline = true,
            // onClick = null：整行的 selectable 已是唯一触控目标（见上面的无障碍说明）
            onClick = null,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (eulaMissing) {
                        // 用警示图标而不是「EULA 未接受」四个字：那段文字要占 ~55dp，
                        // 把标题/摘要挤到只剩不到 90dp，摘要被折成两行后又被 72dp 行高压掉
                        // （真机截图里是「Java 21 · 4096 M…」）。图标同样醒目、仍然行内可见，
                        // 语义靠 contentDescription 保留给读屏。
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "EULA 未接受",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    RoundActionButton(
                        icon = if (showStop) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        description = if (showStop) "停止" else "启动",
                        container = if (showStop) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        content = if (showStop) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        onClick = { if (showStop) onStop() else onStart() },
                    )
                    RoundActionButton(
                        icon = Icons.Filled.MoreVert,
                        description = "更多",
                        container = Color.Transparent,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { menuExpanded = true },
                    )
                }

                // 实例菜单：打开实例目录 / 删除（删除进二次确认）
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = M3Shape.medium,
                ) {
                    DropdownMenuItem(
                        text = { Text("打开实例目录") },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null, Modifier.size(20.dp)) },
                        onClick = {
                            menuExpanded = false
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.fromFile(instance.dir), "resource/folder")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            confirmDelete = true
                        },
                    )
                }

                // 删除确认弹窗：删掉的是实例目录里的全部世界数据与备份
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("删除「${instance.name}」？") },
                        text = {
                            Text("将永久删除实例目录及其全部备份（世界存档、配置、插件/模组）。此操作不可恢复。")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmDelete = false
                                    onDelete()
                                }
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) {
                                Text("取消")
                            }
                        },
                    )
                }
            },
        )

        // 选中态描边：画在列表项之上、不参与它的内部布局（描边跟随相连列表的圆角）
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(2.dp, MaterialTheme.colorScheme.primary, shape)
            )
        }
    }
}

/**
 * 实例行末尾的圆形动作按钮（启停 / 更多）。
 *
 * 刻意做成 40dp：它比整行（72dp、整行可点开详情）小一圈，
 * 用 FilledIconButton 的 48dp 触控区会和"点行"抢触控。
 */
@Composable
private fun RoundActionButton(
    icon: ImageVector,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape),
        shape = CircleShape,
        color = container,
        contentColor = content,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    }
}
