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
 *  - **体量**：读取单文件截断到 [MAX_READ_BYTES]，写入单文件上限 [MAX_WRITE_CHARS]（字符），
 *    防止一次工具调用把上下文或磁盘撑爆。
 */
object AiFileTools {

    /** 单次读取的最大字节数（超出部分截断并注明） */
    const val MAX_READ_BYTES = 64 * 1024

    /**
     * 单次写入的最大**字符数**。
     *
     * 口径说明：调用方手里就是 `String`，能直接量的只有 `length`（字符）。
     * 以前这个常量叫 MAX_WRITE_BYTES 却拿去和 `length` 比 —— 名字说字节、实际数字符，
     * 中文内容下两者差 3 倍，提示行又给"字节"，于是"上限 256KB"和"已写入 700KB"
     * 能同时出现。现在统一：**限制与拒绝提示都按字符**，成功提示同时给出字符与 UTF-8 字节。
     */
    const val MAX_WRITE_CHARS = 256 * 1024

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
        "args", "list", "url", "lang", "old", "disabled", "done",
    )
    // 说明：`bak` 曾经在这份名单里（可读也可写）。备份文件是**唯一的回滚凭据**，
    // 而名单同时管读写 —— AI 能写 .bak 就意味着能覆盖掉上一代备份，让"改坏了能回滚"
    // 这条退路失效。备份只由 AiFileTools 自己生成、由用户手工恢复，不给 AI 经手。
    // 备份名带时间戳（见 writeFile）：多代共存，不会被下一次覆盖冲掉。

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
     * 一次**读取**请求的策略判定结果。
     *
     * [sensitiveReason] 非 null = 这个文件的内容是凭据或权限（数据库口令、RCON 密码、op 名单），
     * 调用方必须先让用户确认再读 —— 工具结果会进模型上下文，也可能随对话发往第三方端点，
     * 而触发这次读取的完全可能是玩家聊天或网页里的一句"提示"（间接提示注入）。
     */
    data class ReadPolicy(
        val sensitiveReason: String? = null,
        val resolvedPath: String = "",
    ) {
        val needsConfirm: Boolean get() = sensitiveReason != null
    }

    /** 精确命中的敏感文件名（小写） */
    private val SENSITIVE_READ_NAMES = mapOf(
        "ops.json" to "该文件是服务器管理员（op）名单，读了等于把权限名单交给模型",
    )

    /**
     * 文件名里出现这些片段即视为凭据类。按词元（非字母数字切开）匹配，
     * 所以 `db_password` / `apikey` / `access_token` / `rcon.secret` / `serverkey` 命中。
     *
     * 代价是也会问一些其实不敏感的名字（`monkey.json`、`keywords.md` 这类"恰好含 key"的）：
     * 一次多余的确认只是一下点击，而漏掉一个真口令就是把它发给了第三方端点 ——
     * 这个方向上宁可误报。`*.key` 这类私钥文件另有 `.key` 不在可读类型名单里兜着（读都读不到）。
     */
    private val CREDENTIAL_HINTS = listOf(
        "password", "passwd", "secret", "token", "apikey", "key", "credential",
    )

    /** 判定一次读取请求的策略（只看文件名，必要时看一眼 server.properties 的 rcon 配置） */
    fun readPolicyFor(instanceDir: File?, rawPath: String): ReadPolicy {
        val rel = normalizeRel(rawPath)
        val name = rel.substringAfterLast('/')
        val resolved = absPathOrNull(instanceDir, rawPath, forWrite = false) ?: rel
        return ReadPolicy(sensitiveReasonFor(instanceDir, rel, name), resolved)
    }

    private fun sensitiveReasonFor(instanceDir: File?, rel: String, name: String): String? {
        SENSITIVE_READ_NAMES[name]?.let { return it }
        val tokens = name.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        CREDENTIAL_HINTS.firstOrNull { hint ->
            tokens.any { it == hint || it.endsWith(hint) || it.startsWith(hint) }
        }?.let { return "文件名含「$it」，通常是凭据类配置（数据库口令 / 密钥 / 访问令牌）" }
        if (name == "server.properties" && hasRconSecret(instanceDir, rel)) {
            return "该 server.properties 里开了 RCON 或填了 RCON 密码，读了等于把远程控制口令交给模型"
        }
        return null
    }

    /**
     * 本地看一眼 `server.properties` 是否带 rcon 配置（**只看这一项，不读给模型**）。
     * 口令行存在才算敏感：普通 server.properties（端口、内存、难度）是诊断最常用的文件，
     * 一律要求确认会平白拦住正常排查。
     */
    private fun hasRconSecret(instanceDir: File?, rel: String): Boolean = runCatching {
        val root = instanceDir ?: return@runCatching false
        val f = resolve(root, null, rel, forWrite = false)
        if (!f.isFile || f.length() > MAX_READ_BYTES) return@runCatching false
        f.useLines { lines -> lines.any { isRconSecretLine(it) } }
    }.getOrDefault(false)

    /** 单行判定（独立出来便于单测）：`enable-rcon=true` 或 `rcon.password=<非空>` */
    internal fun isRconSecretLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return false
        val key = t.substringBefore('=').trim().lowercase()
        val value = t.substringAfter('=', "").trim()
        return when (key) {
            "rcon.password" -> value.isNotEmpty()
            "enable-rcon" -> value.equals("true", ignoreCase = true)
            else -> false
        }
    }

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
    private fun absPathOrNull(instanceDir: File?, rawPath: String, forWrite: Boolean = true): String? =
        instanceDir?.let {
            runCatching { resolve(it, null, rawPath, forWrite = forWrite).path }.getOrNull()
        }

    /**
     * 解析相对路径 → 根目录内的规范文件。
     *
     * `app:` 前缀走应用私有目录（只读，且**只限** [APP_READ_DIRS] / [APP_READ_FILES]），
     * 其余相对实例目录；规范化后必须在对应根内部（含等于根本身，供 list_dir 用 "."），
     * 否则视为越界直接拒绝。
     */
    internal fun resolve(instanceDir: File, appFilesDir: File?, rawPath: String, forWrite: Boolean): File {
        val p = rawPath.trim().trimStart('/')
        if (p.isEmpty()) throw IllegalArgumentException("路径为空")
        if (p.startsWith(APP_PREFIX)) {
            if (forWrite) throw IllegalArgumentException("app: 是启动器应用目录，只读不允许写入")
            val appRoot = appFilesDir ?: throw IllegalArgumentException("应用目录不可用")
            return resolveAppRead(appRoot, p.removePrefix(APP_PREFIX))
        }
        return childOf(instanceDir, p)
    }

    /**
     * `app:` 之下**允许 AI 读取**的范围（相对应用私有目录的首段）。
     *
     * 以前 `app:` 的可读根就是整个 filesDir —— 那里面有 `instances.json`（实例库：
     * 所有实例的名字、路径、内存与 Java 版本）、`background.png`、`linux/`（rootfs）。
     * 这些跟"诊断"没有半点关系，却会被 read_file 一读就走，随后随对话发往第三方端点。
     * 收窄到应用日志（[APP_READ_DIRS]）与诊断文件（[APP_READ_FILES]）：
     * 够用（排查要的正是这两样），越界的一律拒绝。
     *
     * **注意别把 prefs / 密钥搬进 filesDir**：AI 的 `app:` 读根就是 filesDir，
     * 而密钥现在住在 `shared_prefs/`（filesDir 的兄弟目录，不在可读范围内），
     * 这个隔离是刻意的（见 SettingsPrefs 的 SECRET_PREFS_NAME）。
     */
    private val APP_READ_DIRS = setOf("logs")
    private val APP_READ_FILES = setOf("diagnostics.txt")

    /** 先按普通规则做越界校验，再按**规范路径**确认落在允许范围内（`logs/../instances.json` 也挡住） */
    private fun resolveAppRead(appRoot: File, rel: String): File {
        val canon = childOf(appRoot, rel)
        val logsCanon = File(appRoot, APP_READ_DIRS.first()).canonicalFile
        val allowed = canon.path == logsCanon.path ||
            canon.path.startsWith(logsCanon.path + File.separator) ||
            APP_READ_FILES.any { canon.path == File(appRoot, it).canonicalFile.path }
        if (!allowed) {
            throw IllegalArgumentException(
                "app: 只允许读取应用日志（app:logs/…）与诊断文件（app:diagnostics.txt）；" +
                    "应用其它文件与诊断无关，不向 AI 开放"
            )
        }
        return canon
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
    }

    /** 读文本文件；超长截断并注明。路径相对实例目录，`app:` 前缀读应用目录。 */
    fun readFile(instanceDir: File, appFilesDir: File?, path: String): String {
        val f = resolve(instanceDir, appFilesDir, path, forWrite = false)
        if (!f.exists()) throw FileNotFoundException("${f.path}（文件不存在，可先用 list_dir 确认位置）")
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

    /** 列目录：目录在前、按名排序，带大小；超过 [MAX_LIST] 条注明剩余。 */
    fun listDir(instanceDir: File, appFilesDir: File?, path: String): String {
        val d = resolve(instanceDir, appFilesDir, path, forWrite = false)
        if (!d.exists()) throw FileNotFoundException("${d.path}（目录不存在）")
        if (!d.isDirectory) throw IllegalArgumentException("${d.name} 是文件，请用 read_file 读取")
        val entries = d.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: throw IOException("无法列出 ${d.path}")
        if (entries.isEmpty()) return "（空目录）"
        val sb = StringBuilder()
        entries.take(MAX_LIST).forEach { e ->
            if (e.isDirectory) {
                sb.append(e.name).append("/\n")
            } else {
                sb.append(e.name).append("  (").append(formatSize(e.length())).append(")\n")
            }
        }
        if (entries.size > MAX_LIST) sb.append("…还有 ${entries.size - MAX_LIST} 项未列出\n")
        return sb.toString().trimEnd()
    }

    /**
     * 写文本文件（调用方必须已获用户确认）。覆盖已有文件前留一份**带时间戳的**备份。
     * 仅限实例目录；.jar 等二进制与 app: 前缀一律拒绝。
     */
    fun writeFile(instanceDir: File, path: String, content: String): String {
        if (content.isEmpty()) throw IllegalArgumentException("内容为空（如需清空文件请直接说明）")
        if (content.length > MAX_WRITE_CHARS) {
            throw IllegalArgumentException("内容过大：${content.length} 字符 > 上限 $MAX_WRITE_CHARS 字符")
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
            throw IOException("无法创建目录：${parent.path}")
        }
        var bakNote = ""
        if (f.exists()) {
            if (!f.isFile) throw IllegalArgumentException("${f.path} 不是普通文件")
            // 备份名带时间戳：原来的 `同名.bak` 只有**一代** —— 同一天让 AI 改两次，
            // 第二代就把第一代（也就是"改坏之前的原始状态"）覆盖掉了，回滚凭据当场消失。
            // 时间戳让每一代都留着，`latest` 不再是唯一可回滚的点。
            val bak = uniqueBackupFor(f)
            f.copyTo(bak, overwrite = false)
            bakNote = "，原文件已备份为 ${bak.name}"
        }
        f.writeText(content, Charsets.UTF_8)
        return "已写入 ${f.name}（${content.length} 字符 / " +
            "${content.toByteArray(Charsets.UTF_8).size} 字节$bakNote）"
    }

    /** 备份文件名：`server.properties.20261003-121530.bak`（秒级时间戳，天然按名排序） */
    internal fun backupNameFor(fileName: String, now: java.util.Date = java.util.Date()): String =
        fileName + "." + backupStamp("yyyyMMdd-HHmmss", now) + ".bak"

    /**
     * 取一个**还不存在**的备份名。
     *
     * 同一秒内的第二次写入（用户连点重试、AI 连续改两处）会撞上刚生成的那个名字；
     * 这时加序号而不是覆盖 —— 覆盖掉的那份恰好是"上一代原始内容"，正是回滚要用的东西。
     */
    private fun uniqueBackupFor(f: File): File {
        val base = backupNameFor(f.name).removeSuffix(".bak")
        var candidate = f.resolveSibling("$base.bak")
        var i = 2
        while (candidate.exists() && i <= 50) {
            candidate = f.resolveSibling("$base-$i.bak")
            i++
        }
        if (!candidate.exists()) return candidate
        // 极端情况（同一秒内 50 次覆盖同一个文件）：退化到毫秒精度，仍然保证有一份独立备份
        return f.resolveSibling(f.name + "." + backupStamp("yyyyMMdd-HHmmss-SSS") + ".bak")
    }

    private fun backupStamp(pattern: String, now: java.util.Date = java.util.Date()): String =
        java.text.SimpleDateFormat(pattern, java.util.Locale.US).format(now)

    internal fun formatSize(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else "${(bytes + 1023) / 1024} KB"
}
