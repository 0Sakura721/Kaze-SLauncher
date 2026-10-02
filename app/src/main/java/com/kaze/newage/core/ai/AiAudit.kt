package com.kaze.newage.core.ai

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 动作审计日志（借鉴 Harness 的可观测性思路）。
 *
 * 所有"AI 真正做了"的动作 —— 自动执行的控制台命令、用户确认的命令、
 * 批准的文件写入、被策略拦截的写入 —— 都追加到应用私有目录的 `ai_audit.log`。
 * 出问题时有据可查，这也是「全部自动」模式敢放开的前提。
 *
 * 只追加不读取（诊断页/文件管理器可直接查看）；写失败静默 —— 审计不应打断主流程。
 */
object AiAudit {

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun log(context: Context, line: String) {
        runCatching {
            File(context.filesDir, "ai_audit.log")
                .appendText("${fmt.format(Date())} | $line\n")
        }
    }
}
