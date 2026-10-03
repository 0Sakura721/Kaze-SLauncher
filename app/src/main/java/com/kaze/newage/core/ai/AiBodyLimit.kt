package com.kaze.newage.core.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 带上限的响应体读取。
 *
 * 为什么不能 `readText()`：那是"先把整个 body 读进内存再返回"。端点异常、DNS/网关被劫持，
 * 或纯粹的恶意服务端只要回一个几百 MB 的响应体，应用当场 OOM —— 而且是在读的过程中炸，
 * 连一句可读的错误都来不及给用户。
 *
 * 两个上限语义不同，调用方的处理也必须不同：
 *  - **正文** [MAX_BODY_BYTES]：正常 chat/completions 只有几 KB ~ 几百 KB，2MB 以上必是异常。
 *    超限时 [Limited.truncated] = true，调用方要**报错**，不能拿半截 body 去解析 ——
 *    截断的 JSON 必然解不出来，报成"返回了无法解析的内容"会把用户引到错误的方向
 *    （他会去怀疑模型，而真正的问题是端点回了一个超大响应）。
 *  - **错误体** [MAX_ERROR_BYTES]：只用来取第一行原因给用户看，8KB 足够，
 *    超限直接截断（这一用途下"内容不完整"没有意义）。
 */
internal object AiBodyLimit {

    /** 单次响应体上限（正文） */
    const val MAX_BODY_BYTES = 2 * 1024 * 1024

    /** 错误体上限（只取第一行原因，见 [readErrorText]） */
    const val MAX_ERROR_BYTES = 8 * 1024

    /**
     * 流式（SSE）读取的总量上限。
     *
     * 流式本身是增量读，但"增量"没有上限：一个不结束的 SSE 流能让循环永远跑下去，
     * 一行也可以是无限长。[CappedStream] 到顶即报 EOF，把最坏情况钉在 16MB 上 ——
     * 正常会话（含思考内容）连 1MB 都用不到。
     */
    const val MAX_STREAM_BYTES = 16 * 1024 * 1024

    /** [read] 的结果：[text] 是已解码文本，[truncated] = 触到上限、内容不完整 */
    data class Limited(val text: String, val truncated: Boolean)

    /**
     * 流式读取 [stream]（读完自动关闭），最多 [limit] 字节。
     *
     * 只吞"读完了"与"读到上限"，中途的 IO 异常照旧抛出 —— 那属于"连接断了"，
     * 与"体量超限"是两回事，上层要给不一样的提示。
     */
    fun read(stream: InputStream?, limit: Int = MAX_BODY_BYTES): Limited {
        if (stream == null) return Limited("", false)
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var truncated = false
        stream.use { ins ->
            while (true) {
                val r = ins.read(buf)
                if (r < 0) break
                val room = limit - out.size()
                if (r > room) {
                    if (room > 0) out.write(buf, 0, room)
                    truncated = true
                    break
                }
                out.write(buf, 0, r)
            }
        }
        return Limited(String(out.toByteArray(), Charsets.UTF_8), truncated)
    }

    /** 只要一小段（错误体）的封装：这一用途下不关心是否截断 */
    fun readErrorText(stream: InputStream?): String = read(stream, MAX_ERROR_BYTES).text

    /**
     * 触到 [limit] 就报 EOF 的输入流包装（流式场景）。
     *
     * 用"包装读"而不是"读完整段再判断"：这样连**单行超长**也被挡住 ——
     * `BufferedReader.readLine()` 会一直分配到换行为止，只限制总字节数救不了它。
     * [capped] = true 表示内容被截断（调用方把这一轮标记为不完整）。
     */
    internal class CappedStream(
        private val src: InputStream,
        private val limit: Int,
    ) : InputStream() {

        private var served = 0

        /** 是否因为触到上限而提前 EOF */
        var capped = false
            private set

        override fun read(): Int {
            if (served >= limit) {
                capped = true
                return -1
            }
            val b = src.read()
            if (b >= 0) served++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= limit) {
                capped = true
                return -1
            }
            val n = src.read(b, off, minOf(len, limit - served))
            if (n > 0) served += n
            return n
        }

        override fun available(): Int = minOf(src.available(), limit - served)

        override fun close() = src.close()
    }
}
