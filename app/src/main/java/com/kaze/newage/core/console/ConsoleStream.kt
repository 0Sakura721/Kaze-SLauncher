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
 * ## 为什么不能真的"无限"
 * 每行约 130 字节：
 *
 * | 行数 | 内存 |
 * |---|---|
 * | 5 万 | ≈ 6 MB |
 * | 20 万 | ≈ 25 MB |
 * | 120 万 | ≈ 150 MB ← 手机 App 的堆已经装不下 |
 * | 1 亿 | ≈ 12 GB |
 *
 * 所以内存里必然有上限。真正"无限"的是**磁盘上的 `console-output.log`**（append-only），
 * 控制台只是一个滑动窗口；被挤掉的行数记在 [ConsoleStream.droppedCount] 里，
 * 界面上点一下就能看到精确数字与文件体积，并知道去哪找完整日志。
 */
const val CONSOLE_MAX_LINES = 50_000

/**
 * 连续多少行"同类刷屏输出"之后在**控制台**折叠。
 *
 * 起因（真机反馈）：补全依赖 / 解压时每来一个文件就是一行，几千行直接把环形缓冲冲爆，
 * 前面真正有用的输出全被挤掉，屏幕上只剩「N 行（上限）」。
 *
 * ⚠️ 折叠必须**保守**：只针对"文件操作 / 进度"这类天然会重复成千上万行的输出。
 * 早期版本用「前 20 个字符相同」当判据，结果把正常的服务器日志
 * （`【Server thread/INFO】: …` 这类共享前缀的行）也折叠了 —— 那是**信息**，不是噪声。
 *
 * 折叠只作用于控制台：`slot.log()` 已经同步把每一行写进实例目录的
 * `console-output.log`，完整记录一条不少。
 */
const val CONSOLE_BURST_LIMIT = 8

/** 会被折叠的"刷屏型"行的特征：文件操作动词，或进度形式 */
private val FLOOD_VERBS = Regex(
    "^(downloading|download|extracting|extract|unpacking|installing|copying|copy|fetching|" +
        "deploying|verifying|checking|resolving|" +
        "下载|解压|解压缩|安装|复制|展开|补齐|补全|校验|获取|正在)\\b",
    RegexOption.IGNORE_CASE,
)

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

    // ── 同类刷屏折叠的状态 ──
    private var burstKey: String? = null
    private var burstSeen = 0
    private var burstStartType: LineType = LineType.Info
    /** 缓冲里最后一行是不是"折叠汇总行"（要原地更新它，而不是再堆一行） */
    private var lastIsSummary = false

    /**
     * 因为超过 [maxLines] 而被丢掉的行数。
     *
     * 服务器开久了必然会丢 —— 这不是 bug，但界面必须**说清楚**：
     * 这些行还在实例目录的 `console-output.log` 里，而不是凭空消失。
     */
    @Volatile
    var droppedCount: Int = 0
        private set

    @Synchronized
    fun emit(text: String, type: LineType = LineType.Info, replaceLast: Boolean = false) {
        val key = familyKey(text)

        // 换了"家族"（或空行、或本来就要替换上一行）：重新计数
        if (replaceLast || key.isEmpty() || key != burstKey) {
            burstKey = key.ifEmpty { null }
            burstSeen = if (key.isEmpty()) 0 else 1
            burstStartType = type
            append(text, type, replaceLast)
            return
        }

        burstSeen++
        if (burstSeen <= CONSOLE_BURST_LIMIT) {
            append(text, type, false)
            return
        }

        // 超出阈值：不再往缓冲里塞新行，改成**原地更新**一行汇总
        val folded = burstSeen - CONSOLE_BURST_LIMIT
        val summary = buildString {
            append(text.take(140))
            append("\n        ⋯ 同类输出已折叠 ").append(folded)
            append(" 行（完整内容见实例目录的 console-output.log）")
        }
        if (lastIsSummary && buffer.isNotEmpty()) buffer.removeLast()   // 替换上一个汇总行
        append(summary, burstStartType, replaceLast = true)
    }

    private fun append(text: String, type: LineType, replaceLast: Boolean) {
        val line = ConsoleLine(text, type, replaceLast = replaceLast)
        lastIsSummary = text.contains("同类输出已折叠")
        buffer.addLast(line)
        while (buffer.size > maxLines) {
            buffer.removeFirst()
            droppedCount++
        }
        _lines.tryEmit(line)
    }

    /**
     * 一行的"家族"：用于判断连续多行是不是同一类刷屏。
     *
     * **只有"刷屏型"行才有家族**（文件操作动词开头 / 进度形式），其余一律返回空串 ——
     * 空串永远不折叠。这一点很关键：早期版本拿"前 20 字符相同"当判据，把正常的
     * 服务器日志也折叠了，那是信息而不是噪声。
     */
    private fun familyKey(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        if (!isFloodProne(t)) return ""
        // 家族 = 归一化后的**动词**（去掉时间戳与数字）
        val stripped = t.replace(Regex("^\\[?\\d{1,2}:\\d{2}:\\d{2}]?\\s*"), "")
        val verb = FLOOD_VERBS.find(stripped)?.value?.lowercase() ?: return ""
        return verb
    }

    /** 这一行像不像"成千上万行"的那种输出（文件操作 / 进度） */
    private fun isFloodProne(t: String): Boolean {
        // 进度形式：以 % 结尾，或 "3/128" 这种计数
        if (t.endsWith("%")) return true
        if (Regex("\\b\\d+\\s*/\\s*\\d+\\b").containsMatchIn(t)) return true
        val stripped = t.replace(Regex("^\\[?\\d{1,2}:\\d{2}:\\d{2}]?\\s*"), "")
        return FLOOD_VERBS.containsMatchIn(stripped)
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
        burstKey = null
        burstSeen = 0
        lastIsSummary = false
        droppedCount = 0
    }
}
