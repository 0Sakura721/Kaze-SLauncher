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
class ConsoleStream(
    private val maxLines: Int = 2000,
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
    fun emit(text: String, type: LineType = LineType.Info) {
        val line = ConsoleLine(text, type)
        buffer.addLast(line)
        while (buffer.size > maxLines) buffer.removeFirst()
        _lines.tryEmit(line)
    }

    /** 覆盖式输出（服务端用 \r 原地更新进度，如 "Preparing spawn area: 50%"）：
     *  替换最后一行，控制台不刷屏、观感与原始日志一致 */
    @Synchronized
    fun emitReplace(text: String, type: LineType = LineType.Info) {
        if (buffer.isNotEmpty()) buffer.removeLast()
        emit(text, type)
    }

    @Synchronized
    fun snapshot(): List<ConsoleLine> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}
