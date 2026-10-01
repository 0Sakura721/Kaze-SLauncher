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
 * 1. **块边界会切断行**。所以每一次读都从**行首**开始：先从 `end` 往回退 [BLOCK]，
 *    再向前找到最近的换行符当起点（见 [blockStart]），块里每个元素因此都是完整行。
 *
 *    旧实现不是这样：它把块首那半行带在游标 `carry` 里交给下一次调用，而那个半行的字节
 *    范围是 `[start, 首个换行)`、下一块的读取区间是 `[end-BLOCK, end)` 且 `end > start` ——
 *    两段**重叠**，同一段内容被读两遍、边界行被拼坏。它还得靠"把解出来的字符串重新编码"
 *    来结算字节数，块边界切开一个多字节汉字时解码成替换字符、长度对不上，翻页继续错位。
 *    对齐行首之后每个元素都是完整行，字节数就是文件里的真实字节数。
 *
 * 2. **提前凑够行数时，偏移量要落在"最老的已取走那一行"的行首**。若直接写成块起点，
 *    下次就会重复读同一块、看起来"翻不动了"；落点必须按**字节**结算（中文一行 3 字节），
 *    按行数或字符数算都会错位。
 *
 * ## 已知限制（如实记录）
 * 单行超过 [MAX_LINE_CHARS]（128 KB）时，往回 128 KB 内找不到行首，于是退回原始块起点，
 * 并把块首那段半行标记为"已截断"后丢弃。真实日志行都在 1 KB 以内，走不到这条路径；
 * 与其为极端情况留一个不牢靠的实现，不如**有界**并明确写出来。
 */
object ConsoleArchive {

    /** 每次读的块大小：256 KB ≈ 2500~3000 行 */
    private const val BLOCK = 256 * 1024

    const val DEFAULT_CHUNK_LINES = 3000

    /**
     * 往回找行首时最多回退多少：再长就当作超长行按截断处理（见文件头"已知限制"）。
     */
    private const val MAX_LINE_CHARS = 128 * 1024

    /**
     * 读取位置：从 [file] 的 [offset] **往前**读（不含 offset 处）。
     *
     * [offset] 总落在行首（或文件头），所以游标里不需要携带"半行"状态 ——
     * 那正是旧实现跨块拼坏边界行的原因，见文件头说明。
     */
    data class Cursor(val file: File, val offset: Long)

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

        var fileIndex = files.indexOfFirst { it.absolutePath == cursor?.file?.absolutePath }
        if (fileIndex < 0) fileIndex = 0
        // **先记下未钳制的原始偏移**：轮转判定要靠它，钳制之后就永远看不出"越界"了
        //（这正是第一版修复没生效的原因）。
        val rawOffset = cursor?.offset ?: files[fileIndex].length()
        var end = rawOffset.coerceIn(0L, files[fileIndex].length())

        // 落点越过文件末尾 = 这份日志在两次回读之间**被轮转过**：
        // `persistLine` 超过 8MB 时把 console-output.log 改名成 console-output.old.log、
        // 再新建一个空的 console-output.log。游标记的是绝对路径，于是它认到的其实是**那个新文件**，
        // 偏移被 coerce 到新文件的末尾（0，或轮转后新写入的那点内容），接着就会把刚写进来、
        // 用户已经在内存窗口里看过的行再读一遍 —— 界面上表现为"往上翻又看到刚才那些行"。
        //
        // 而且**只在新文件里读一点、再接着读旧文件**同样不对（翻页会出现"旧的最早几行 +
        // 新的最后几行"这种两头拼起来的怪页）。所以这里直接跟到旧文件，完全不碰轮转后的新文件：
        // 里面那点内容必然已经显示过（它们就排在用户刚刚看过的最后几行之后）。
        // 偏移**不需要换算**：改名不改内容，同一个偏移在新名字下指向同一行边界。
        // （游标为 null 时 end 必然等于当前文件长度，进不来这个分支）
        val from = cursor
        if (from != null && rawOffset > files[fileIndex].length() && fileIndex + 1 < files.size) {
            fileIndex++
            end = rawOffset.coerceIn(0L, files[fileIndex].length())
        }

        val collected = ArrayDeque<String>()   // 逆序收集，最后正序返回

        while (collected.size < maxLines) {
            if (fileIndex >= files.size) break
            val file = files[fileIndex]
            if (end <= 0L) {                   // 这一份读到头 → 换更旧的一份
                fileIndex++
                if (fileIndex < files.size) end = files[fileIndex].length()
                continue
            }
            val blockEnd = end
            val (start, aligned) = blockStart(file, blockEnd)
            val parts = readRange(file, start, blockEnd).split('\n')
            // 只有超长行（往回 128 KB 找不到行首）才会出现"块首不是完整行"
            val usableFrom = if (aligned) 0 else 1
            if (!aligned) {
                collected.addFirst("[上一行过长（超过 ${MAX_LINE_CHARS / 1024} KB），已截断]")
            }

            // 从块的末尾往前取行，并累计**字节数**（不是字符数：中文一行 3 字节）。
            // after = 从"最老的已取走那一行"的行首到块末尾的字节数。
            var after = 0L
            var stopped = false
            val last = parts.lastIndex
            for (i in last downTo usableFrom) {
                if (collected.size >= maxLines) {
                    stopped = true
                    break
                }
                val line = parts[i]
                if (line.isEmpty() && i == last) continue   // 行尾 \n 造成的空串
                collected.addFirst(line)
                // 块末尾那个元素后面没有换行符在块内，不能多算一个字节
                after += line.toByteArray(Charsets.UTF_8).size + if (i == last) 0 else 1
            }

            end = if (stopped) {
                // 提前凑够行数：落点正好是"最老的已取走那一行"的行首，
                // 否则下次会重复读已取走的行、或跳掉还没取走的那几行
                (blockEnd - after).coerceIn(start, blockEnd)
            } else {
                // 整块都取走了（或块首被截断丢弃）：落到行首，下次从更早处继续。
                // 这里若写成"块尾减去已取走字节"就会落到已经取走的内容里（旧实现的错法）。
                start
            }
        }

        val hasMore = end > 0L || fileIndex + 1 < files.size
        val next = if (!hasMore) null else Cursor(files[fileIndex.coerceAtMost(files.size - 1)], end)
        return Chunk(collected.toList(), next, hasMore)
    }

    /**
     * 这一块的起点，**保证落在行首**。
     *
     * 从 `end - BLOCK` 再往前找到最近的一个换行符，起点取它的下一个字节。
     * 往回最多找 [MAX_LINE_CHARS]：再长就当作超长行，退回原始起点，由调用方按截断处理；
     * 若已经回退到文件头，说明这一行就是文件第一行 —— 0 本身就是合法的行首。
     */
    private fun blockStart(file: File, end: Long): Pair<Long, Boolean> {
        val raw = maxOf(0L, end - BLOCK)
        if (raw == 0L) return 0L to true
        val from = maxOf(0L, raw - MAX_LINE_CHARS)
        val buf = ByteArray((raw - from).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            raf.readFully(buf)
        }
        // UTF-8 的续字节都 ≥ 0x80，不可能等于 0x0A —— 按字节找换行是安全的
        for (i in buf.indices.reversed()) {
            if (buf[i] == '\n'.code.toByte()) return (from + i + 1) to true
        }
        return if (from == 0L) 0L to true else raw to false
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
