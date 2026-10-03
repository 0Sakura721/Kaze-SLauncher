package com.kaze.newage.core.ai

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * AI 的文件工具（读 / 列 / 写），全部在本机执行。
 *
 * 权限边界（刻意收紧的设计，不是实现限制）：
 *  - **读**：实例目录（server.properties 所在的根）+ 启动器应用私有目录
 *    （`app:` 前缀，只读 —— 诊断报告、应用日志）；
 *  - **写**：**仅实例目录**，且调用方必须先经用户确认（UI 层职责，见 AppViewModel），
 *    覆盖已有文件前自动留 `同名.bak` 备份；
 *  - **路径**：一律规范化后校验必须在对应根目录内部 —— `..`、绝对路径、软链逃逸全部拒绝；
 *  - **类型**：二进制后缀（jar/zip/世界文件/图片等）读写全禁；文本按后缀白名单，
 *    写 .jar 被显式拒绝（装模组请走「插件/模组」页面，不经过 AI）；
 *  - **体量**：读取单文件截断到 [MAX_READ_BYTES]，写入单文件上限 [MAX_WRITE_BYTES]，
 *    防止一次工具调用把上下文或磁盘撑爆。
 */
object AiFileTools {

    /** 单次读取的最大字节数（超出部分截断并注明） */
    const val MAX_READ_BYTES = 64 * 1024

    /**
     * 单次写入的最大字节数（UTF-8）。
     *
     * 单位与确认卡上显示的"xxx 字节"、与 [writeFile] 的报错文案必须**同口径** ——
     * 曾经这里是"字符数"上限而卡片显示字节数，同一份内容两个数字，用户无法判断到底哪个是限制。
     */
    const val MAX_WRITE_BYTES = 256 * 1024

    /** 目录列表最多展示的条目数 */
    private const val MAX_LIST = 200

    /** 启动器应用私有目录的虚拟前缀（只读） */
    const val APP_PREFIX = "app:"

    /** 禁止读写（AI 视角）的二进制后缀：世界存档、压缩包、可执行、图片媒体等 */
    private val FORBIDDEN_EXTS = setOf(
        "jar", "zip", "gz", "tar", "7z", "apk",
        "mca", "mcr", "nbt", "dat", "litematic",
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "tga", "psd",
        "mp3", "wav", "ogg", "flac", "mp4", "mkv", "mov", "avi",
        "dll", "so", "class", "bin", "exe", "dylib", "iso",
    )

    /** 允许读写的文本后缀白名单（服务端配置/日志/脚本的常见类型） */
    private val TEXT_EXTS = setOf(
        "txt", "log", "properties", "json", "json5", "yml", "yaml", "toml",
        "cfg", "conf", "ini", "md", "csv", "tsv", "mcmeta", "mcfunction",
        "sh", "bat", "ps1", "py", "xml", "html", "js", "ts", "css",
        "args", "list", "url", "lang", "bak", "old", "disabled", "done",
    )

    /**
     * **只读**后缀：能看，不能写。
     *
     * `.bak` 原来是可写的，两个问题叠在一起：一是"覆盖前自动留备份"产生的名字，AI 也能自己
     * 写同名文件，等于让模型伪造或顶掉备份（备份是用户唯一的回退手段）；二是模型写出来的
     * `xxx.bak` 会混进同名序列里，之后人眼分不清哪份是启动器留的。诊断要回看旧版本，所以读保留。
     */
    private val READ_ONLY_EXTS = setOf("bak")

    /**
     * `app:` 前缀下**允许 AI 访问**的相对路径。
     *
     * 原来 `app:` 等于整个 filesDir：里面有 instances.json（实例库：每个实例的绝对路径与配置）、
     * ai_chat.json（历史会话）等 —— 对诊断没有任何价值，却会被模型"读一段文本"顺走并发往
     * 第三方端点。真正有诊断价值的是应用日志与诊断报告，所以只开放这三项。
     * 目录项带 `/` 前缀匹配，文件项精确匹配（见 [isAppPathAllowed]）。
     */
    private val APP_READ_ALLOW = listOf("logs", "ai_audit.log", "diagnostics.txt")

    /**
     * **禁止写入**的文件名（精确匹配，小写）。
     *
     * 威胁模型很具体：确认卡上的内容对用户只是"一段文本"，但下面这些文件一旦落盘就会被
     * 别的程序当**代码或权限**使用 —— 而注入源是现成的（控制台日志里的玩家聊天、
     * 联网搜索结果都会进模型上下文），所以不能靠"用户看仔细点"兜底。
     */
    private val FORBIDDEN_WRITE_NAMES = mapOf(
        "user_jvm_args.txt" to
            "Forge 启动脚本会把它当 JVM 参数（@argfile）传给 java，等于让 AI 决定 JVM 启动参数（可加载任意代码）",
        "ops.json" to "该文件直接决定谁是服务器管理员（op）",
        "banned-ips.json" to "封禁名单：写入等于让 AI 自行解封或封禁他人",
        "banned-players.json" to "封禁名单：写入等于让 AI 自行解封或封禁他人",
        "whitelist.json" to "白名单直接决定谁能进服：写入等于让 AI 自行放行或拒绝玩家",
        "eula.txt" to "EULA 同意状态必须由你本人决定，不能代填",
    )

    /**
     * **高风险可写**后缀：允许写，但确认卡必须红字说明"此文件会被执行或授权"。
     * 这些脚本本身常常是服主自己放的运维脚本，一刀切禁掉会误伤，所以留人工判断。
     */
    private val HIGH_RISK_WRITE_EXTS = setOf("sh", "bat", "ps1", "py", "js")

    /**
     * 一次写入请求的策略判定结果。
     *
     * [forbidden] 与 [warning] 是互斥的两组结论：前者结构性禁止（任何确认都不放行），
     * 后者允许写、但确认卡必须红字警示。判定放在 core 而不是界面里 —— 界面与真正落盘
     * 共用同一个判定，"卡上没警示、底下照样写"这种错位就不可能发生。
     */
    data class WritePolicy(
        /** 非 null = 禁止写入的原因 */
        val forbidden: String? = null,
        /** 非 null = 允许写入但必须醒目警示的原因 */
        val warning: String? = null,
        /** 解析后的绝对路径（确认卡要显示给用户的就是它）；解析失败时回退为原始相对路径 */
        val resolvedPath: String = "",
    )

    /** 判定一次写入请求的策略（只看路径，不看内容） */
    fun writePolicyFor(instanceDir: File?, rawPath: String): WritePolicy {
        val rel = normalizeRel(rawPath)
        val forbidden = forbiddenReasonFor(rel)
        val warning = if (forbidden == null) highRiskWarningFor(rel) else null
        return WritePolicy(forbidden, warning, absPathOrNull(instanceDir, rawPath) ?: rel)
    }

    /**
     * 一次读取请求的策略判定结果。
     *
     * [sensitive] 非 null = 这个文件里可能有凭据，**读之前要用户点头**：读取的后果和写入不同，
     * 它不改变磁盘，但会把内容发到模型服务商那边，而注入源是现成的（控制台里的玩家聊天、
     * 联网搜索到的网页都能诱导模型去读插件目录里的数据库口令、
     * `server.properties` 里的 RCON 密码）。
     */
    data class ReadPolicy(
        /** 非 null = 敏感内容，读取前必须确认（原因要上卡） */
        val sensitive: String? = null,
        /** 解析后的绝对路径（确认卡显示用）；解析失败回退原始相对路径 */
        val resolvedPath: String = "",
    )

    /** 判定一次读取请求（只看文件名与 server.properties 的 rcon 开关，不看其它内容） */
    fun readPolicyFor(instanceDir: File?, rawPath: String): ReadPolicy {
        val rel = normalizeRel(rawPath)
        return ReadPolicy(sensitiveReasonFor(instanceDir, rel), absPathOrNull(instanceDir, rawPath) ?: rel)
    }

    /** 敏感文件名里的关键词（小写匹配）：名字本身就是"这里有凭据"的信号 */
    private val SENSITIVE_NAME_HINTS = listOf(
        "password", "passwd", "secret", "token", "credential", "apikey", "api_key", "密钥", "凭据",
    )

    private fun sensitiveReasonFor(instanceDir: File?, rel: String): String? {
        if (rel.isEmpty()) return null
        val name = rel.substringAfterLast('/')
        if (name == "ops.json") return "ops.json 是管理员名单：它决定谁能拿到全服权限"
        SENSITIVE_NAME_HINTS.firstOrNull { name.contains(it) }?.let {
            return "$name 的文件名表明它可能含密钥或口令（关键词：$it）"
        }
        // 只有"确实开了 RCON 且设了密码"的 server.properties 才拦：没开的读了也没秘密，
        // 每读一次 server.properties 都弹卡会把确认变成走过场
        if (name == "server.properties" && rconEnabled(instanceDir, rel)) {
            return "这份 server.properties 启用了 RCON，文件里有 RCON 密码"
        }
        return null
    }

    /** 本地判定：enable-rcon=true 且 rcon.password 非空 */
    private fun rconEnabled(instanceDir: File?, rel: String): Boolean = runCatching {
        val dir = instanceDir ?: return false
        val f = resolve(dir, null, rel, forWrite = false)
        if (!f.isFile) return false
        val text = f.readText().take(64 * 1024)
        Regex("(?m)^\\s*enable-rcon\\s*=\\s*true\\s*$").containsMatchIn(text) &&
            Regex("(?m)^\\s*rcon\\.password\\s*=\\s*\\S+").containsMatchIn(text)
    }.getOrDefault(false)

    private fun forbiddenReasonFor(rel: String): String? {
        val name = rel.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "")
        FORBIDDEN_WRITE_NAMES[name]?.let { return it }
        if (ext == "mcfunction") {
            return "数据包函数（*.mcfunction）以服务端权限执行，等于让 AI 把代码放进服务端"
        }
        // 数据包的 load.json / tick.json 会自动触发同名函数：任何 tags/ 下的 json 都按代码看待
        if (ext == "json" && rel.split('/').any { it == "tags" }) {
            return "数据包 tags/*.json 会被服务端自动加载并触发函数执行（load/tick）"
        }
        return null
    }

    private fun highRiskWarningFor(rel: String): String? {
        val ext = rel.substringAfterLast('/').substringAfterLast('.', "")
        return if (ext in HIGH_RISK_WRITE_EXTS) {
            "这是可执行脚本（.$ext）：写入后会被系统或服务端执行，等同于允许 AI 放置可执行代码"
        } else {
            null
        }
    }

    /** 判定用的相对路径归一：反斜杠转正斜杠、去首尾空白与开头斜杠、小写（不用于落盘） */
    private fun normalizeRel(rawPath: String): String =
        rawPath.trim().replace('\\', '/').trimStart('/').lowercase()

    /** 解析后的绝对路径；越界 / app: / 解析失败都返回 null（调用方回退） */
    private fun absPathOrNull(instanceDir: File?, rawPath: String): String? =
        instanceDir?.let {
            runCatching { resolve(it, null, rawPath, forWrite = true).path }.getOrNull()
        }

    /**
     * 解析相对路径 → 根目录内的规范文件。
     *
     * `app:` 前缀走应用私有目录（只读），其余相对实例目录；规范化后必须在
     * 对应根内部（含等于根本身，供 list_dir 用 "."），否则视为越界直接拒绝。
     */
    internal fun resolve(instanceDir: File, appFilesDir: File?, rawPath: String, forWrite: Boolean): File {
        val p = rawPath.trim().trimStart('/')
        if (p.isEmpty()) throw IllegalArgumentException("路径为空")
        if (p.startsWith(APP_PREFIX)) {
            if (forWrite) throw IllegalArgumentException("app: 是启动器应用目录，只读不允许写入")
            val appRoot = appFilesDir ?: throw IllegalArgumentException("应用目录不可用")
            val rel = p.removePrefix(APP_PREFIX).trim().trimStart('/')
            val target = childOf(appRoot, rel.ifBlank { "." })
            // 白名单在 canonical 之后判定：`app:logs/../instances.json` 这类写法也逃不掉
            val relCanon = runCatching { target.relativeTo(appRoot.canonicalFile).invariantSeparatorsPath }
                .getOrDefault(target.name)
            if (!isAppPathAllowed(relCanon)) {
                throw IllegalArgumentException(
                    "app: 只开放应用日志与诊断报告（logs/、ai_audit.log、diagnostics.txt）：" +
                        "$relCanon 不对 AI 开放"
                )
            }
            return target
        }
        return childOf(instanceDir, p)
    }

    /**
     * `app:` 相对路径是否在白名单内。
     *
     * `logs` 是目录：它自己与它下面的一切都放行；`ai_audit.log` / `diagnostics.txt` 是文件，
     * 精确匹配。`.`（应用目录根本身）放行 —— 列出根目录时由 [listDir] 按同一份名单过滤子项，
     * 否则连"有哪些日志文件"都问不出来。
     */
    internal fun isAppPathAllowed(relPath: String): Boolean {
        val r = relPath.trim().trimStart('/')
        if (r.isEmpty() || r == ".") return true
        return APP_READ_ALLOW.any { allowed -> r == allowed || r.startsWith("$allowed/") }
    }

    private fun childOf(root: File, rel: String): File {
        val f = File(root, rel.trim().trimStart('/'))
        val canon = f.canonicalFile
        val rootCanon = root.canonicalFile
        if (canon.path != rootCanon.path && !canon.path.startsWith(rootCanon.path + File.separator)) {
            throw IllegalArgumentException("路径越界：只能访问实例目录或 app: 应用目录内部")
        }
        return canon
    }

    private fun guardTextType(f: File, forWrite: Boolean) {
        val ext = f.extension.lowercase()
        if (ext in FORBIDDEN_EXTS) {
            throw IllegalArgumentException(
                if (forWrite) ".$ext 是二进制类型，不允许通过 AI 写入（如装模组请用「插件/模组」页面）"
                else ".$ext 是二进制类型，不允许读取"
            )
        }
        if (ext.isNotEmpty() && ext !in TEXT_EXTS) {
            throw IllegalArgumentException(
                if (forWrite) ".$ext 不在可写类型名单里"
                else ".$ext 不在可读类型名单里（文本类：txt/log/properties/json/yml/toml 等）"
            )
        }
        if (forWrite && ext in READ_ONLY_EXTS) {
            throw IllegalArgumentException(
                ".$ext 是启动器留的备份文件，只读：AI 写入会伪造或顶掉你唯一的回退版本"
            )
        }
    }

    /**
     * 读文本文件；超长截断并注明。路径相对实例目录，`app:` 前缀读应用目录。
     *
     * 报错一律用**相对路径**：这些字符串会随工具结果回喂给模型（也就发往第三方端点），
     * 绝对路径会把设备目录结构一并带出去，而相对路径对定位问题已经足够。
     * （确认卡上的绝对路径是给**用户**看的，那是有意显示，见 [WritePolicy.resolvedPath]。）
     */
    fun readFile(instanceDir: File, appFilesDir: File?, path: String): String {
        val f = resolve(instanceDir, appFilesDir, path, forWrite = false)
        if (!f.exists()) throw FileNotFoundException("$path（文件不存在，可先用 list_dir 确认位置）")
        if (f.isDirectory) throw IllegalArgumentException("${f.name} 是目录，请用 list_dir 列出")
        guardTextType(f, forWrite = false)
        val total = f.length()
        if (total <= 0L) return "（空文件）"
        // read(buf) 不保证读满（尤其网络/管道语义下），必须循环读够或到 EOF
        val buf = ByteArray(MAX_READ_BYTES)
        var n = 0
        f.inputStream().use { ins ->
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
        }
        val text = String(buf, 0, n, Charsets.UTF_8)
        return if (total > MAX_READ_BYTES) {
            "$text\n…（已截断：文件共 $total 字节，只读取了前 $MAX_READ_BYTES 字节）"
        } else {
            text
        }
    }

    /**
     * 列目录：目录在前、按名排序，带大小；超过 [MAX_LIST] 条注明剩余。
     *
     * **流式扫描**（见 [scanListing]）：mods/ 或世界目录动辄上万项，`listFiles()` 会把整个
     * 目录一次性读成数组，而这里只需要"要显示的那几百项 + 一个总数"。
     */
    fun listDir(instanceDir: File, appFilesDir: File?, path: String): String {
        val d = resolve(instanceDir, appFilesDir, path, forWrite = false)
        if (!d.exists()) throw FileNotFoundException("$path（目录不存在）")
        if (!d.isDirectory) throw IllegalArgumentException("${d.name} 是文件，请用 read_file 读取")
        // 应用目录根：只列出白名单内的项（instances.json 之类的名字都没必要给模型看）
        val inAppRoot = appFilesDir != null && d.canonicalFile == appFilesDir.canonicalFile
        val listing = scanListing(d) { !inAppRoot || isAppPathAllowed(it) }
        val total = listing.dirsTotal + listing.filesTotal
        if (total == 0) return "（空目录）"
        val sb = StringBuilder()
        listing.dirs.sortedBy { it.lowercase() }.forEach { sb.append(it).append("/\n") }
        listing.files.sortedBy { it.first.lowercase() }.forEach { (name, size) ->
            sb.append(name).append("  (").append(formatSize(size)).append(")\n")
        }
        val shown = listing.dirs.size + listing.files.size
        if (total > shown) sb.append("…还有 ${total - shown} 项未列出\n")
        return sb.toString().trimEnd()
    }

    /** 目录扫描的中间结果：只收前 [MAX_LIST] 项，其余只计数 */
    private class Listing {
        val dirs = ArrayList<String>()
        val files = ArrayList<Pair<String, Long>>()
        var dirsTotal = 0
        var filesTotal = 0
    }

    /**
     * 两趟扫描：第一趟收目录名（最多 [MAX_LIST] 个）并数总数，第二趟按余量收文件名与大小。
     *
     * 为什么要两趟：版式是"目录在前"，而一趟遍历只能先到先得 —— 一个前 200 项全是文件的
     * 目录会把后面的子目录挤掉，看上去像"目录不见了"。两趟的代价只是多一次目录遍历。
     */
    private fun scanListing(dir: File, filter: (String) -> Boolean): Listing {
        val out = Listing()
        java.nio.file.Files.newDirectoryStream(dir.toPath()).use { stream ->
            for (p in stream) {
                val name = p.fileName?.toString() ?: continue
                if (!filter(name)) continue
                if (java.nio.file.Files.isDirectory(p)) {
                    out.dirsTotal++
                    if (out.dirs.size < MAX_LIST) out.dirs += name
                } else {
                    out.filesTotal++
                }
            }
        }
        if (out.dirs.size >= MAX_LIST) return out
        java.nio.file.Files.newDirectoryStream(dir.toPath()).use { stream ->
            for (p in stream) {
                val name = p.fileName?.toString() ?: continue
                if (!filter(name)) continue
                if (java.nio.file.Files.isDirectory(p)) continue
                if (out.dirs.size + out.files.size >= MAX_LIST) break
                out.files += name to runCatching { java.nio.file.Files.size(p) }.getOrDefault(0L)
            }
        }
        return out
    }

    /**
     * 写文本文件（调用方必须已获用户确认）。覆盖已有文件前留 `名字.时间戳.bak`。
     * 仅限实例目录；.jar 等二进制、`.bak` 备份与 app: 前缀一律拒绝。
     */
    fun writeFile(instanceDir: File, path: String, content: String): String {
        if (content.isEmpty()) throw IllegalArgumentException("内容为空（如需清空文件请直接说明）")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES) {
            throw IllegalArgumentException("内容过大：超过上限 $MAX_WRITE_BYTES 字节")
        }
        val f = resolve(instanceDir, null, path, forWrite = true)
        // 结构性禁止的文件在这一层**也要**拦：界面已经不再给"允许写入"按钮，
        // 但写盘这一层必须自己成立 —— 换个入口（未来的批量操作、脚本调用）就绕不过去了。
        // 用规范路径判定：`a/../ops.json` 这类写法会落到同一个文件名上。
        val rel = runCatching { f.relativeTo(instanceDir.canonicalFile).path }.getOrDefault(f.name)
        forbiddenReasonFor(normalizeRel(rel))?.let {
            throw IllegalArgumentException("该文件禁止通过 AI 写入：$it")
        }
        guardTextType(f, forWrite = true)
        val parent = f.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建目录：$path")
        }
        var bakNote = ""
        if (f.exists()) {
            if (!f.isFile) throw IllegalArgumentException("$path 不是普通文件")
            val name = backupNameFor(f)
            f.copyTo(f.resolveSibling(name), overwrite = false)
            bakNote = "，原文件已备份为 $name"
        }
        f.writeText(content, Charsets.UTF_8)
        val size = content.toByteArray(Charsets.UTF_8).size
        return "已写入 ${f.name}（$size 字节$bakNote）"
    }

    /**
     * 备份文件名：`原名.20260927-153012.bak`，同一秒内重复备份再加序号。
     *
     * 原来是固定的 `原名.bak` + overwrite：只保留**一代**备份，第二次覆盖就把上一版的原始内容
     * 永久顶掉了 —— 而"改坏了要回退"往往正是在第二次写入之后才发现的（AI 连改两轮配置很常见）。
     * 时间戳让每一代都留得住；同秒序号保证名字唯一（`copyTo(overwrite = false)` 依赖它）。
     */
    internal fun backupNameFor(src: File, now: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date(now))
        val base = "${src.name}.$stamp"
        var candidate = "$base.bak"
        var seq = 1
        while (src.resolveSibling(candidate).exists()) {
            candidate = "$base-$seq.bak"
            seq++
        }
        return candidate
    }

    internal fun formatSize(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else "${(bytes + 1023) / 1024} KB"
}
