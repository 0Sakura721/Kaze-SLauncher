package com.kaze.newage.core.console

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 控制台日志行类型 */
enum class LineType { System, Info, Warn, Error, Command }

/** 控制台日志行 */
data class ConsoleLine(
    val text: String,
    val type: LineType = LineType.Info,
    val timestamp: Long = System.currentTimeMillis(),
    /** 进程内唯一序号：LazyColumn key 需要唯一性（同毫秒同文本的两行会造成键冲突崩溃） */
    val seq: Long = nextSeq(),
    /**
     * 该行应**替换**上一行（服务端用 `\r` 输出的原地进度行）。
     * 只有环形缓冲知道要替换是不够的：实时订阅者是"收到一行就追加一行"，
     * 不把这个意图传下去的话，「Preparing spawn area: x%」照样会刷出几百行。
     */
    val replaceLast: Boolean = false,
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        private fun nextSeq(): Long = counter.incrementAndGet()
    }
}

/**
 * 控制台日志流：全局共享，UI 订阅实时渲染。
 * 实现要点：
 *  - 环形缓冲保留最近 [maxLines] 行，供 [snapshot] 回填（切换实例 / 重建界面时用）
 *  - 实时流不带 replay，缓冲满时丢**最旧**的行，保证最新输出一定到得了界面
 */
/**
 * 控制台保留的最大行数（UI 与环形缓冲共用同一个上限，避免"缓冲 2000 / 界面又是另一个数"
 * 这种对不上的情况）。超出的**最旧**行会被丢掉，但完整日志始终落盘在实例目录的
 * `console-output.log`，控制台的「保存日志」与实例日志页都能拿到全量。
 *
 * 内存量级：每行约 100~150 字节，5000 行 ≈ 1 MB 以内。
 */
const val CONSOLE_MAX_LINES = 5000

class ConsoleStream(
    private val maxLines: Int = CONSOLE_MAX_LINES,
) {
    private val _lines = MutableSharedFlow<ConsoleLine>(
        replay = 0,
        extraBufferCapacity = 256,
        // 订阅者跟不上时（例如服务端刷屏）丢最旧的行，而不是让 tryEmit 直接失败把新行扔掉。
        // 旧实现用默认的 SUSPEND 策略 + 忽略 tryEmit 返回值：无订阅者或 UI 卡顿时新日志
        // 会被静默丢弃，且没有任何地方能察觉。
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val lines: SharedFlow<ConsoleLine> = _lines.asSharedFlow()

    /** 供 UI 读取的已缓冲行（简单环形缓冲实现） */
    private val buffer = ArrayDeque<ConsoleLine>()

    @Synchronized
    fun emit(text: String, type: LineType = LineType.Info, replaceLast: Boolean = false) {
        val line = ConsoleLine(text, type, replaceLast = replaceLast)
        buffer.addLast(line)
        while (buffer.size > maxLines) buffer.removeFirst()
        _lines.tryEmit(line)
    }

    /** 覆盖式输出（服务端用 \r 原地更新进度，如 "Preparing spawn area: 50%"）：
     *  替换最后一行，控制台不刷屏、观感与原始日志一致。
     *  注意要同时带上 replaceLast 标记：订阅者是"收到就追加"，只改缓冲的话实时视图仍会刷屏。 */
    @Synchronized
    fun emitReplace(text: String, type: LineType = LineType.Info) {
        if (buffer.isNotEmpty()) buffer.removeLast()
        emit(text, type, replaceLast = true)
    }

    @Synchronized
    fun snapshot(): List<ConsoleLine> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}
