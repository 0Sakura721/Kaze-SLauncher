package com.kaze.newage.core.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 带上限的响应体读取。
 *
 * 为什么不能直接 `readText()`：那是"把整个 body 读进内存再返回"。端点异常、被劫持的
 * DNS/HTTP 网关、或纯粹的恶意服务端只要回一个几百 MB 的 body，应用当场 OOM
 * （Java 堆 OOM 连可读的错误都来不及给）。AI 模块的响应体（正文与错误体）一律走这里。
 *
 * 超限的处理是**截断并告知**而不是静默丢掉：调用方需要知道"拿到的不是全部"，
 * 才能把"内容不完整"如实表达出来（JSON 类响应截断后必然解析不了，那种情况应报错，
 * 不能拿半截 body 去解析然后说"返回了无法解析的内容"）。
 */
internal object BodyLimit {

    /** 单次响应体上限：正常 chat/completions 响应只有几 KB ~ 几百 KB，2MB 以上必是异常 */
    const val MAX_BYTES = 2 * 1024 * 1024

    /**
     * 错误体上限。错误体只用来取"第一行原因"给用户看，
     * 没必要为一个 500 错误页面把几 MB HTML 读进内存。
     */
    const val MAX_ERROR_BYTES = 8 * 1024

    /** 读取结果：[text] 是已解码文本，[truncated] = 因为超过上限被截断（内容不完整） */
    data class Limited(val text: String, val truncated: Boolean)

    /**
     * 流式读取 [stream]（读完自动关闭），最多 [limit] 字节。
     * 读取中途的 IO 异常照旧抛出 —— 那属于"连接断了"，与"体量超限"是两回事。
     */
    fun read(stream: InputStream?, limit: Int = MAX_BYTES): Limited {
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

    /** 只要一小段（错误体）时的便捷封装：截断与否对这一用途没有意义 */
    fun readErrorText(stream: InputStream?): String = read(stream, MAX_ERROR_BYTES).text
}
