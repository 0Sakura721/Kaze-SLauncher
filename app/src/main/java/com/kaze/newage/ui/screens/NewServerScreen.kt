package com.kaze.newage.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import com.kaze.newage.core.download.CoreBuild
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.data.model.CoreCategory
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.GameVersion
import com.kaze.newage.data.model.JavaVersionInference
import com.kaze.newage.data.model.VersionType
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.DownloadState
import com.kaze.newage.ui.components.InstanceIcon
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3ESegmentedRow
import com.kaze.newage.ui.components.M3EStatusChip
import com.kaze.newage.ui.components.WavyLinearProgress
import com.kaze.newage.ui.theme.M3Motion
import com.kaze.newage.ui.components.MemorySliderRow
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import androidx.compose.material3.OutlinedButton

/**
 * 新建服务端 —— 三步向导：选核心 → 选版本与资源 → 确认安装。
 *
 * 版式来自 m3e-canvas 生成的 `docs/m3e/prompt-新建服务端.md`：
 *   标题 + 步骤标题 → 波浪形向导进度 → 核心选择卡（选中=主色容器底 + 对勾）
 *   → 下一步按钮；进入第 2 步后是版本列表（搜索 + 类型筛选），选中版本进第 3 步
 *   确认页（实例名 / 内存 / Java / 构建 / 服务器设置 / EULA / 下载进度）。
 *
 * 页面流转仍是组件内部的 `coreType` / `selected` 两个状态，**没有**引入 NavHost：
 * 调用方只拿到 onBack，向导内部的"上一步"由本文件自己管（与旧版完全一致）。
 * 每个阶段都是「标题栏 → 可滚动内容（占满剩余高度）→ 固定在底部的动作」，
 * 底部动作不会随内容滚走，长列表上也不用翻到底才能按"下一步"。
 */
@Composable
fun NewServerScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    // rememberSaveable：转屏后不退回阶段①重新选核心（CoreType 是 enum，可直接进 Bundle）
    var coreType by rememberSaveable { mutableStateOf<CoreType?>(null) }

    // 导入 jar（核心类型选择「导入 jar」时直接走文件选择）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 同 ServerScreen：复制在 ViewModel 的 IO 协程里做，失败有 Toast（原实现主线程拷贝 + 静默吞异常）
        uri?.let {
            viewModel.importJar(
                uri = it,
                name = "导入-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date()),
                memoryMb = 1024,
            )
            onBack()
        }
    }

    // 系统返回键先回退向导**内部**的一步，而不是整页弹出（否则页内箭头只退一级、
    // 系统返回直接关掉整个向导，已填的名称/端口/EULA 一起丢）。
    // 配置页还有一层更深的 BackHandler（回版本列表），它注册得更晚、优先级更高。
    BackHandler(enabled = coreType != null) { coreType = null }

    if (coreType == null) {
        CoreSelectPhase(
            onSelect = { type ->
                if (type == CoreType.CUSTOM) {
                    importLauncher.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*"))
                } else {
                    coreType = type
                }
            },
            onBack = onBack,
        )
    } else {
        VersionConfigPhase(
            viewModel = viewModel,
            coreType = coreType!!,
            onBackToCore = { coreType = null },
            onExit = onBack,
        )
    }
}
