package com.kaze.newage.ui

import com.kaze.newage.core.update.UpdateChecker

/*
 * ViewModel 对外暴露的**状态类型**与**配置常量**。
 *
 * 为什么单独成文件：这些声明被 40 多个界面/控制器引用，但和 AppViewModel 的
 * 业务逻辑没有耦合 —— 放在同一个 3200 行的文件顶部，只会让人以为"改状态就得
 * 读整个 ViewModel"。抽出来之后：
 *   · 包名不变（仍在 com.kaze.newage.ui），所以所有引用处**一行都不用改**；
 *   · AppViewModel.kt 少 60 行，类本身从 import 后的第一行就能看到业务。
 *
 * 注意：这里只搬**声明**，行为一字未改。
 */

/** 服务端下载状态 */
data class DownloadState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val done: Boolean = false,
    val error: String? = null,
    /**
     * 附加组件专用：解析出目标文件与已装文件同名，正等用户确认是否替换。
     * 此时 [running] 为 false（没有任务在跑），界面据此弹确认框而不是显示进度条。
     */
    val replacePending: Boolean = false,
)

/** Java 安装/卸载任务状态 */
data class JavaTaskState(
    val running: Boolean = false,
    val version: Int? = null,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
    /** 取消请求标志（下载循环轮询；true 时下载中止并保留断点） */
    val cancelRequested: Boolean = false,
)

/**
 * 应用自身更新的状态机。
 *
 * 必须活在 ViewModel 里，不能像原来那样放在设置页的 `remember` + `rememberCoroutineScope`：
 * 更新包几十 MB、下载要几分钟，用户点完「下载并安装」去别的页面看看、或转一下屏幕，
 * 组合被销毁 → 协程作用域取消 → 下载静默中止，而界面不会有任何提示
 *（回到设置页时状态已经重置成 Idle，看起来像"我根本没点过"）。
 */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class Error(val msg: String) : AppUpdateState
    data object Latest : AppUpdateState
    data class Found(val info: UpdateChecker.ReleaseInfo) : AppUpdateState
    data class Downloading(
        val info: UpdateChecker.ReleaseInfo,
        val progress: Float,
        val message: String,
    ) : AppUpdateState
}

/**
 * 服务端核心 jar 的最小可接受体积（64KB）。
 *
 * 只用来兜底"明显不是包"的响应，真正的防伪是 ZIP 魔数与官方哈希。
 * **下限不能定高**：Fabric 的 server launcher jar 实测只有 178KB（181,840 字节），
 * 历史上 1MB 的阈值使 Fabric 每次下载都被判为非法、删文件重试，最终报"所有源不可用"。
 */
internal const val MIN_CORE_JAR_BYTES = 64L * 1024L

/** 占用采样节拍（毫秒）。CPU 靠两次求差，窗口越短越跟得上突发负载；配合 EMA 平滑防跳。 */
internal const val PROC_STATS_INTERVAL_MS = 1_000L

/** 版本列表拉取的界面可见超时（毫秒）。 */
internal const val VERSION_FETCH_TIMEOUT_MS = 45_000L
