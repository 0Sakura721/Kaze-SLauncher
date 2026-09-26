package com.kaze.newage.core.console

import java.io.File
import java.io.RandomAccessFile

/**
 * 从实例目录的**运行日志文件**里往回读更早的行。
 *
 * ## 为什么需要
 * 控制台的环形缓冲有上限（内存装不下无限行：120 万行 ≈ 150 MB），
 * 但用户真正想要的是"能一直往上翻"。日志文件是 append-only 的，所以控制台
 * 只需**按需往回读**，就能做到对用户意义上的无限可翻，且不占常驻内存。
 *
 * ## 磁盘上的实际上限
 * `DefaultServerManager.persistLine()` 会在 `console-output.log` 超过 8 MB 时把它改名成
 * `console-output.old.log`（更旧的直接删）。所以可回读总量是**这两份文件之和 ≈ 16 MB**
 * （约 12 万行）—— 不是数学意义上的无限，但比内存窗口大两个数量级。
 *
 * ## 两个必须处理对的细节（都是被测试抓出来的）
 * 1. **块边界会切断行**。块开头那半行要**带在 [Cursor.carry] 里交给下一次调用** ——
 *    只放在函数内的局部变量里，跨调用就丢了，而丢掉的往往正好是用户要找的报错。
 * 3. **已知限制（如实记录）**：单行超过 [MAX_LINE_CHARS]（128 KB）时会被截断并标记。
 *    往回读必须把一个逻辑行拼完整，超长行会让中间状态无限膨胀（测试里直接 OOM，
 *    或表现为同一段内容重复堆叠）。真实日志行都在 1 KB 以内，走不到这条路径；
 *    与其为极端情况留一个不牢靠的实现，不如**有界**并明确写出来。
 *
 * 2. **提前凑够行数时，偏移量要落在块内部**。若直接写成块起点，下次就会重复读同一块、
 *    看起来"翻不动了"。
 */
object ConsoleArchive {

    /** 每次读的块大小：256 KB ≈ 2500~3000 行 */
    private const val BLOCK = 256 * 1024

    const val DEFAULT_CHUNK_LINES = 3000

    /**
     * 单行字符数上限：往回读时必须把一个逻辑行拼完整，超过这个长度就截断并标记。
     * 目的是**有界** —— 一行几百 KB 时任其膨胀会把内存撑爆（测试里就是这么 OOM 的）。
     * 真实日志行都在 1 KB 以内，走不到这条路径。
     */
    private const val MAX_LINE_CHARS = 128 * 1024

    /**
     * 读取位置：从 [file] 的 [offset] **往前**读（不含 offset 处）。
     *
     * [carry] 是上一次分块在开头切出来的半行（它会接在下一块读取结果的**末尾**前面）。
     * 首次调用传 `Cursor(files[0], files[0].length())`。
     */
    data class Cursor(val file: File, val offset: Long, val carry: String = "")

    data class Chunk(
        /** 按时间**正序**（越靠前越早），与界面从上到下的顺序一致 */
        val lines: List<String>,
        val cursor: Cursor?,
        val hasMore: Boolean,
    )

    /** 当前实例可回读的日志文件，按"从新到旧"排列 */
    internal fun filesNewestFirst(dir: File): List<File> =
        listOf(File(dir, "console-output.log"), File(dir, "console-output.old.log"))
            .filter { it.isFile && it.length() > 0 }

    /** 首次回读的起点 */
    fun start(dir: File): Cursor? =
        filesNewestFirst(dir).firstOrNull()?.let { Cursor(it, it.length()) }

    /** 往回读最多 [maxLines] 行；[cursor] 为 null 时等价于从最新处开始 */
    fun readOlder(dir: File, cursor: Cursor?, maxLines: Int = DEFAULT_CHUNK_LINES): Chunk {
        val files = filesNewestFirst(dir)
        if (files.isEmpty()) return Chunk(emptyList(), null, false)

        var cur = cursor ?: Cursor(files[0], files[0].length())
        var fileIndex = files.indexOfFirst { it.absolutePath == cur.file.absolutePath }
        if (fileIndex < 0) fileIndex = 0
        var end = cur.offset.coerceIn(0L, files[fileIndex].length())
        var carry = cur.carry

        val collected = ArrayDeque<String>()   // 逆序收集，最后正序返回

        while (collected.size < maxLines) {
            if (fileIndex >= files.size) break
            val file = files[fileIndex]
            if (end <= 0L) {                   // 这一份读到头 → 换更旧的一份
                fileIndex++
                if (fileIndex < files.size) {
                    end = files[fileIndex].length()
                    carry = ""
                }
                continue
            }
            val start = maxOf(0L, end - BLOCK)
            val text = readRange(file, start, end) + carry
            carry = ""


            val parts = text.split('\n')
            val usable: List<String>
            if (start > 0) {
                carry = parts.first()          // 被切断的半行，交给下一块（接在它末尾）
                usable = parts.drop(1)
            } else {
                carry = ""
                usable = parts
            }

            // 有界兜底：carry 是"还没凑齐的一行"。正常日志里它最多几 KB，
            // 但如果遇到几百 KB 的单行（把整个堆栈打在一行），任它膨胀就会 OOM。
            // 超限就标记截断并**丢弃该行剩余部分** —— 宁可少显示一行，也不能炸内存或重复堆叠。
            if (carry.length > MAX_LINE_CHARS) {
                collected.addFirst("[上一行过长（超过 ${MAX_LINE_CHARS / 1024} KB），已截断]")
                carry = ""
                end = start
                continue
            }

            // 从块的末尾往前取行，并累计**字节数**（不是字符数：中文一行 3 字节）
            var consumed = 0L
            for (i in usable.indices.reversed()) {
                if (collected.size >= maxLines) break
                val line = usable[i]
                if (line.isEmpty() && i == usable.lastIndex) {   // 行尾 \n 造成的空串
                    consumed += 1
                    continue
                }
                collected.addFirst(line)
                consumed += line.toByteArray(Charsets.UTF_8).size + 1
            }
            // 关键：落点必须往前走。
            //  - 正常情况落到"块内已消费字节"处，否则下次会重复读同一块（表现为"翻不动"）；
            //  - 一行都没消费（整个块落在一行内部，比如超长堆栈行）时直接退到块起点 ——
            //    内容由 carry 带着，下一块会接在它前面，直到遇见 \n 才凑成完整行。
            //    这里若忘了推进就是死循环（测试里表现为 OOM）。
            end = if (consumed == 0L) start else (end - consumed).coerceIn(start, end)
        }

        val hasMore = end > 0L || fileIndex + 1 < files.size || carry.isNotEmpty()
        val next = if (!hasMore) null else Cursor(files[fileIndex.coerceAtMost(files.size - 1)], end, carry)
        return Chunk(collected.toList(), next, hasMore)
    }

    private fun readRange(f: File, start: Long, end: Long): String {
        val len = (end - start).toInt()
        if (len <= 0) return ""
        val buf = ByteArray(len)
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            raf.readFully(buf)
        }
        return String(buf, Charsets.UTF_8)
    }
}
