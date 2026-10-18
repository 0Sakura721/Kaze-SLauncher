package com.kaze.newage.core.server

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 白名单（whitelist.json）读写。
 *
 * 为什么不走 `whitelist add/remove` 命令，而是直接改文件：
 * 命令只有在服务端**运行中**才可用，而"我要先把谁加进白名单再开服"是完全正常的用法；
 * 而且命令不会告诉你当前名单里到底有谁 —— 用户只能盲加盲删。直接读写文件两个问题一起解决，
 * 服务端下次启动（或执行 `whitelist reload`）就会读到。
 *
 * 文件格式（Minecraft Java 版）：
 * ```json
 * [{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"}]
 * ```
 *
 * ## UUID 必须算对
 * 正版（`online-mode=true`）玩家的 UUID 来自 Mojang 账号；离线服（本项目默认
 * `online-mode=false`）的 UUID 是 **`UUID.nameUUIDFromBytes("OfflinePlayer:<名字>")`**
 * —— 也就是 MD5 加版本位。写错 UUID 的后果不是报错，而是**该玩家依然进不来**：
 * 服务端按 UUID 匹配白名单，名字对不上号等于没加。所以这里按离线服规则生成，
 * 并在 [entry] 里允许调用方传入已知 UUID（例如从 ops.json / usercache.json 读到正版 UUID 时）。
 */
object WhitelistFile {

    private const val FILE_NAME = "whitelist.json"

    /** 离线模式 UUID：`UUID.nameUUIDFromBytes("OfflinePlayer:<name>")`，与 Minecraft 服务端一致 */
    fun offlineUuid(name: String): String =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString()

    /**
     * 从 usercache.json / ops.json 里读到的真实 UUID 缓存。
     * 正版服的玩家在 usercache.json 里留着 UUID，优先用它 —— 否则给正版玩家写离线 UUID
     * 等于没加（服务端认的是正版 UUID）。
     */
    private fun readKnownUuid(dir: File, name: String): String? {
        for (f in listOf("usercache.json", "ops.json", "banned-players.json")) {
            val file = File(dir, f)
            if (!file.isFile) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            // 不用完整 JSON 解析：这些文件是扁平数组，正则足够且不会因某个字段缺失而整体失败
            val re = Regex(
                """"uuid"\s*:\s*"([0-9a-fA-F-]{32,36})"\s*,\s*"name"\s*:\s*"${Regex.escape(name)}""" +
                    """|"name"\s*:\s*"${Regex.escape(name)}"\s*,\s*"uuid"\s*:\s*"([0-9a-fA-F-]{32,36})""""
            )
            re.find(text)?.let { m ->
                val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
                if (raw.isNotEmpty()) return normalizeUuid(raw)
            }
        }
        return null
    }

    /** 统一成带连字符的小写 36 位形式（MC 两种写法都接受，但统一便于比较） */
    fun normalizeUuid(raw: String): String {
        val hex = raw.replace("-", "").lowercase()
        if (hex.length != 32) return raw.lowercase()
        return buildString {
            for ((i, c) in hex.withIndex()) {
                if (i == 8 || i == 12 || i == 16 || i == 20) append('-')
                append(c)
            }
        }
    }

    /** 一条白名单记录 */
    data class Entry(val name: String, val uuid: String)

    /**
     * 解析白名单文件。**解析失败时返回空列表而不是抛异常**：
     * 文件可能被服务端正在写、也可能是用户手改坏了 —— 界面显示"空名单"比整页崩掉好，
     * 而且 [add] 会在此基础上重建。
     */
    fun read(dir: File): List<Entry> {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        // 用正则逐条提取：whitelist.json 是 [{uuid,name}] 扁平数组，字段顺序两种都可能出现
        val out = LinkedHashMap<String, Entry>()
        val re = Regex(
            """"uuid"\s*:\s*"([^"]*)"\s*,\s*"name"\s*:\s*"([^"]*)"""" +
                """|"name"\s*:\s*"([^"]*)"\s*,\s*"uuid"\s*:\s*"([^"]*)""""
        )
        for (m in re.findAll(text)) {
            val uuid = m.groupValues[1].ifEmpty { m.groupValues[4] }
            val name = m.groupValues[2].ifEmpty { m.groupValues[3] }
            if (name.isNotBlank()) out[name] = Entry(name, normalizeUuid(uuid))
        }
        return out.values.toList()
    }

    /** 原子写：先写临时文件再 rename，避免服务端/掉电看到半截 JSON */
    fun write(dir: File, entries: List<Entry>) {
        val file = File(dir, FILE_NAME)
        dir.mkdirs()
        val body = entries.joinToString(",", prefix = "[", postfix = "]") { e ->
            """{"uuid":"${e.uuid}","name":"${escape(e.name)}"}"""
        }
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            // rename 失败（跨分区/被占用）时退化为直接写，但至少内容已经完整生成
            file.writeText(body)
            tmp.delete()
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * 加一个玩家。已存在（按名字，忽略大小写 —— MC 玩家名大小写不敏感）时返回 unchanged。
     *
     * UUID 取用顺序：`usercache.json`/`ops.json` 里已知的正版 UUID → 离线 UUID。
     */
    fun add(dir: File, name: String): AddResult {
        val clean = name.trim()
        if (clean.isEmpty()) return AddResult.Invalid
        if (clean.length > 16) return AddResult.TooLong
        val current = read(dir)
        if (current.any { it.name.equals(clean, ignoreCase = true) }) {
            return AddResult.AlreadyThere
        }
        val uuid = readKnownUuid(dir, clean) ?: offlineUuid(clean)
        write(dir, current + Entry(clean, uuid))
        return AddResult.Added(uuid)
    }

    /** 删一个玩家（按名字，忽略大小写）。返回是否真的删掉了 */
    fun remove(dir: File, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val current = read(dir)
        val rest = current.filterNot { it.name.equals(clean, ignoreCase = true) }
        if (rest.size == current.size) return false
        write(dir, rest)
        return true
    }

    /** 整体覆盖（用于"清空名单"） */
    fun clear(dir: File) = write(dir, emptyList())

    sealed interface AddResult {
        data class Added(val uuid: String) : AddResult
        data object AlreadyThere : AddResult
        data object Invalid : AddResult
        data object TooLong : AddResult
    }

    /** 玩家名校验：MC 允许字母数字下划线，1~16 位；离线服中文名也算合法（不少整合包用） */
    fun isValidName(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > 16) return false
        if (n.any { it.isWhitespace() }) return false
        // 排除会破坏 JSON 或命令行语义的字符
        return n.none { it == '"' || it == '\\' || it == '\u0000' }
    }

    /** 名字的 MD5 前 8 位，仅用于调试日志（不参与任何判断） */
    internal fun debugHash(name: String): String {
        val d = MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8))
        return d.take(4).joinToString("") { "%02x".format(it) }
    }
}
