package com.kaze.newage.ui.ai

import android.content.Context
import com.kaze.newage.core.console.ConsoleLine
import com.kaze.newage.core.monitor.ProcessStats
import com.kaze.newage.core.server.ServerManager
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.InstanceStore
import com.kaze.newage.data.prefs.SettingsPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * [AiChatController] 向宿主（[com.kaze.newage.ui.AppViewModel]）索取的依赖与现场。
 *
 * 之所以抽成接口而不是直接持有 AppViewModel：把 AI 逻辑与 ViewModel 解耦后，
 * 以后可以用假宿主对它做**单元测试**（工具循环此前没有端到端测试，因为它在 ViewModel 里、
 * 需要 Android 运行时）。这次重构本身不行为变更 —— 每个成员的值都是搬运前
 * AppViewModel 里对应的那个对象本身（getter 转发，不是拷贝）。
 */
interface AiChatHost {

    /** 应用上下文（私有目录、审计日志用） */
    val appContext: Context

    /** 应用级协程作用域（草稿防抖落盘这类"进程活多久就跑多久"的活） */
    val appScope: CoroutineScope

    /** ViewModel 级协程作用域（提问、写入/读取确认后的续跑；随 VM 清理而取消） */
    val scope: CoroutineScope

    /** 设置（AI 档案、搜索源、工具权限、命令档位、思考开关） */
    val uiPrefs: SettingsPrefs

    /** 实例仓库（按消息记录的实例 id 定位目录） */
    val instanceStore: InstanceStore

    /** 服务端管理器（命令执行档位的自动发送与运行态判定） */
    val serverManager: ServerManager

    /** 当前选中的实例 id（提问时快照进会话；工具默认实例） */
    val currentInstanceId: StateFlow<String?>

    /** 当前实例状态（快照里的状态文案） */
    val serverState: StateFlow<ServerState>

    /** 当前实例运行时长（快照） */
    val uptimeSec: StateFlow<Long>

    /** 当前实例在线玩家（快照） */
    val onlinePlayers: StateFlow<List<String>>

    /** 服务端进程占用（快照） */
    val procStats: StateFlow<ProcessStats.Reading?>

    /** 控制台显示窗口（快照里的日志） */
    val consoleLines: StateFlow<List<ConsoleLine>>
    /**
     * 当前实例可用技能的提示词段（清单，不含全文）。空串 = 没有技能。
     *
     * 给默认实现：skills 是可选能力，测试里的假宿主不必都实现它。
     */
    fun skillsPromptBlock(): String = ""

}