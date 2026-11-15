package com.kaze.newage.core.console

import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.addons.ModCompatibility
import com.kaze.newage.core.addons.ModMetadataReader
import com.kaze.newage.core.server.EulaHandler
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.core.server.WhitelistFile
import com.kaze.newage.data.model.ServerInstance
import java.io.File
import java.util.Locale

/**
 * 离线控制台：服务端**没有运行**时也能用的控制台。
 *
 * 真实痛点：想改个 server.properties、加个白名单、把模组停用、看看 jar 到底是哪个版本，
 * 全都得先把服务端启动起来（内存立刻上去、手机发烫），然后在 MC 控制台里根本没有这些命令。
 * 而服务端停着的时候，控制台页面的输入框是**禁用**的，只能干看着。
 *
 * 这里提供一层"本地命令"：直接对实例目录里的文件做事，不需要 Java、不需要进程。
 * 其中 [`sh <命令>`] 会落进 proot 环境执行任意 Linux 命令（`sh ls -la`、`sh du -sh world`），
 * 相当于给了一个停服状态下的真实终端。
 *
 * 设计边界：
 *  - 命令都做**路径沙箱**（解析后的路径必须在实例目录内），不接受绝对路径越界；
 *  - 输出统一截断（行数 / 单行长度），避免 `cat` 一个 100 MB 的日志把界面卡死；
 *  - 纯 JVM 实现（不 import Android），因此可以用普通单元测试覆盖。
 */
object OfflineConsole {

    data class Line(val text: String, val type: LineType = LineType.Info)

    /** 一条命令的用法说明（`help` 与界面提示共用） */
    data class Command(val usage: String, val description: String)

    /** 单次执行的输出行数上限 */
    const val MAX_OUTPUT_LINES = 200

    /** 单行字符上限 */
    private const val MAX_LINE_CHARS = 500

    /** `cat` 允许的最大文件（再大就是日志/世界文件，不是配置） */
    private const val MAX_CAT_BYTES = 2L * 1024 * 1024

    val COMMANDS: List<Command> = listOf(
        Command("help", "列出可用命令"),
        Command("info", "实例概要（核心 / MC 版本 / Java / 端口 / 目录）"),
        Command("ls [目录]", "列出实例目录下的文件（默认根目录）"),
        Command("cat <文件> [行数]", "查看文本文件（默认前 40 行）"),
        Command("grep <文本> [目录]", "在配置/日志文件里搜文本"),
        Command("props", "列出 server.properties 全部键值"),
        Command("props <键>", "查看某个配置项"),
        Command("props <键>=<值>", "修改配置项（立即写盘，重启生效）"),
        Command("mods", "列出已安装模组（含元数据与兼容性提示）"),
        Command("mods info <序号|名字>", "查看单个模组的详情"),
        Command("mods enable|disable <序号|名字>", "启用 / 停用模组"),
        Command("wl", "列出白名单"),
        Command("wl add|remove <玩家名>", "增删白名单（自动按离线 UUID 计算）"),
        Command("wl clear", "清空白名单"),
        Command("eula", "查看 EULA 状态"),
        Command("eula accept", "写入 eula=true"),
        Command("jar", "查看核心 jar / 安装器信息"),
        Command("sh <Linux 命令>", "在 proot 环境里执行命令（如 sh du -sh world）"),
    )

    /**
     * 执行一行离线命令。
     *
     * @param shell proot 里执行任意命令的能力；为 null（环境未部署/初始化中）时 `sh` 会明确报错，
     *   其余命令照常可用 —— 离线控制台的价值不能绑在环境就绪上
     * @return 要写进控制台的行；调用方负责把它们按顺序 emit
     */
    suspend fun run(
        instance: ServerInstance,
        input: String,
        shell: (suspend (String) -> Result<String>)? = null,
    ): List<Line> {
        val raw = input.trim()
        if (raw.isEmpty()) return emptyList()
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return emptyList()
        val cmd = tokens[0].lowercase(Locale.US)
        val args = tokens.drop(1)
        val out = mutableListOf<Line>()

        fun add(text: String) { out.add(Line(text)) }
        fun warn(text: String) { out.add(Line(text, LineType.Warn)) }
        fun err(text: String) { out.add(Line(text, LineType.Error)) }
        fun sys(text: String) { out.add(Line(text, LineType.System)) }

        try {
            when (cmd) {
                "help", "?", "man" -> {
                    sys("离线控制台命令（服务端未运行也可用）：")
                    COMMANDS.forEach { add("  ${it.usage.padEnd(28)} ${it.description}") }
                    add("")
                    add("提示：sh 前缀可执行 Linux 命令，例如 sh ls -l /mnt/world")
                }

                "info" -> {
                    val props = ServerProperties.load(instance.dir)
                    val mods = AddonManager.installed(instance, AddonKind.MOD)
                    sys("实例：${instance.name}")
                    add(
                        "  核心：${instance.coreType.displayName}" +
                            (if (instance.coreBuild.isBlank()) "" else "（${instance.coreBuild}）") +
                            "\n  MC 版本：${instance.mcVersion.ifBlank { "未知" }}" +
                            "\n  内存上限：${instance.memoryMb} MB · Java ${instance.javaMajor}" +
                            "\n  端口：${props["server-port"] ?: "未配置"}" +
                            "\n  MOTD：${props["motd"] ?: "未配置"}" +
                            "\n  在线模式：${if (props["online-mode"] == "true") "正版验证" else "离线模式（免正版）"}" +
                            "\n  EULA：${if (EulaHandler.isAccepted(instance.dir)) "已接受" else "未接受（sh 不会影响，下次启动会要求接受）"}" +
                            "\n  模组：${mods.size} 个（启用 ${mods.count { AddonManager.isEnabled(it) }}）" +
                            "\n  目录：${instance.dir.absolutePath}",
                    )
                }

                "ls", "dir" -> {
                    val target = resolveInInstance(instance, args.firstOrNull() ?: ".")
                    if (target == null) {
                        err("路径不在实例目录内：${args.firstOrNull()}")
                    } else if (!target.exists()) {
                        err("不存在：${args.firstOrNull()}")
                    } else if (target.isFile) {
                        add(formatFile(target))
                    } else {
                        val children = target.listFiles()?.sortedWith(
                            compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.US) }),
                        ).orEmpty()
                        if (children.isEmpty()) {
                            add("（空目录）")
                        } else {
                            sys("${relative(instance, target)}/（${children.size} 项）")
                            children.take(80).forEach { f ->
                                if (f.isDirectory) {
                                    add("  ${f.name}/")
                                } else {
                                    add("  ${f.name}  ${human(f.length())}")
                                }
                            }
                            if (children.size > 80) add("  …还有 ${children.size - 80} 项")
                        }
                    }
                }

                "cat", "less", "head" -> {
                    val path = args.firstOrNull()
                    if (path == null) {
                        err("用法：cat <文件> [行数]")
                    } else {
                        catFile(instance, path, args.getOrNull(1)?.toIntOrNull() ?: 40, ::add, ::err)
                    }
                }

                "grep", "find" -> {
                    val needle = args.firstOrNull()
                    if (needle.isNullOrBlank()) {
                        err("用法：grep <文本> [目录]")
                    } else {
                        grep(instance, needle, args.getOrNull(1), ::sys, ::add, ::err)
                    }
                }

                "props", "prop" -> handleProps(instance, args, ::add, ::warn, ::err)

                "mods", "mod" -> handleMods(instance, args, ::sys, ::add, ::warn, ::err)

                "wl", "whitelist" -> handleWhitelist(instance, args, ::sys, ::add, ::warn, ::err)

                "eula" -> {
                    val accepted = EulaHandler.isAccepted(instance.dir)
                    if (args.firstOrNull()?.lowercase(Locale.US) == "accept") {
                        if (accepted) {
                            add("EULA 已经是接受状态。")
                        } else {
                            EulaHandler.accept(instance.dir)
                            add("已写入 eula=true（下次启动不会再因 EULA 退出）。")
                        }
                    } else {
                        add(if (accepted) "EULA：已接受" else "EULA：未接受（用 `eula accept` 写入）")
                    }
                }

                "jar" -> {
                    val jar = instance.jarFile
                    sys("核心 jar：${jar.name}")
                    add(
                        "  ${human(jar.length())} · ${jugdeJarState(jar)}" +
                            "\n  路径：${jar.absolutePath}" +
                            (instance.installerJar?.let { "\n  安装器：${it.name}（${human(it.length())}）" } ?: "") +
                            (if (instance.forgeInstalled()) "\n  Forge/NeoForge：已安装完成" else "") +
                            (if (instance.forgeInstallIncomplete()) "\n  Forge/NeoForge：安装**未完成**（库不全，启动会报 Missing required libraries）" else ""),
                    )
                }

                "sh", "shell", "!" -> {
                    // 保留用户原始引号：tokenize 会吃掉引号，而 shell 命令需要它们
                    val command = raw.trim().split(Regex("\\s+"), limit = 2).getOrElse(1) { "" }.trim()
                    if (command.isEmpty()) {
                        err("用法：sh <Linux 命令>，例如 sh ls -l")
                    } else if (shell == null) {
                        err("Linux 环境还没准备好（部署完成后再试）。其余离线命令不受影响。")
                    } else {
                        sys("$ $command")
                        val result = shell(command)
                        val text = result.getOrNull()
                        if (text.isNullOrBlank()) {
                            add(if (result.isSuccess) "（无输出）" else "（命令失败，无输出）")
                        } else {
                            text.trimEnd().lines().take(MAX_OUTPUT_LINES).forEach { add(it) }
                        }
                        if (result.isFailure) err(result.exceptionOrNull()?.message ?: "命令执行失败")
                    }
                }

                "clear" -> sys("（清屏由界面完成）")

                else -> {
                    // 认不出来：既可能是打错的本地命令，也可能是习惯性输入 MC 命令（say/list/op）
                    warn("离线状态下不认识「$cmd」。服务端运行后 MC 命令才可用；现在输入 help 看本地命令。")
                }
            }
        } catch (e: Exception) {
            err("执行失败：${e.message ?: e.javaClass.simpleName}")
        }
        return out.take(MAX_OUTPUT_LINES).map { line ->
            if (line.text.length <= MAX_LINE_CHARS) {
                line
            } else {
                line.copy(text = line.text.take(MAX_LINE_CHARS) + "…")
            }
        }
    }

    // ── 各命令实现 ──

    private fun handleProps(
        instance: ServerInstance,
        args: List<String>,
        add: (String) -> Unit,
        warn: (String) -> Unit,
        err: (String) -> Unit,
    ) {
        val props = ServerProperties.load(instance.dir)
        if (args.isEmpty()) {
            if (props.isEmpty()) {
                warn("server.properties 还不存在（首次启动后生成）")
                return
            }
            add("server.properties（${props.size} 项）：")
            props.forEach { (k, v) -> add("  $k=$v") }
            return
        }
        // props set <key> <value>  /  props <key>=<value>  /  props <key>
        val text = args.joinToString(" ")
        val (key, value) = when {
            args.size >= 3 && args[0].equals("set", true) ->
                args[1] to args.drop(2).joinToString(" ")
            text.contains('=') ->
                text.substringBefore('=').trim() to text.substringAfter('=').trim()
            else -> args[0] to null
        }
        if (key.isBlank()) {
            err("用法：props <键> 或 props <键>=<值>")
            return
        }
        if (value == null) {
            add("$key=${props[key] ?: "（未设置）"}")
            return
        }
        val updated = LinkedHashMap(props)
        updated[key] = value
        ServerProperties.save(instance.dir, updated)
        add("已写入 $key=$value（重启服务端后生效）")
    }

    private fun handleMods(
        instance: ServerInstance,
        args: List<String>,
        sys: (String) -> Unit,
        add: (String) -> Unit,
        warn: (String) -> Unit,
        err: (String) -> Unit,
    ) {
        val files = AddonManager.installed(instance, AddonKind.MOD)
        val sub = args.firstOrNull()?.lowercase(Locale.US)
        if (files.isEmpty()) {
            warn("mods/ 目录里还没有模组（用「本地导入」或搜索安装）")
            return
        }
        when (sub) {
            null, "list" -> {
                sys("已安装模组（${files.size} 个）：")
                files.forEachIndexed { index, file ->
                    val enabled = AddonManager.isEnabled(file)
                    val meta = ModMetadataReader.read(file)
                    val flag = if (enabled) "启用" else "停用"
                    var line = "  [${index + 1}] ${meta.displayName}" +
                        (if (meta.version.isBlank()) "" else " v${meta.version}") +
                        " · $flag · ${meta.loader.label}"
                    if (enabled) {
                        val notes = ModCompatibility.check(instance, AddonKind.MOD, meta)
                        if (notes.isNotEmpty()) line += " ⚠"
                    }
                    add(line)
                }
                add("用 `mods info <序号>` 看详情，`mods disable <序号>` 停用")
            }

            "info" -> {
                val target = pickMod(files, args.getOrNull(1))
                if (target == null) {
                    err("没找到这个模组：${args.getOrNull(1)}")
                    return
                }
                val meta = ModMetadataReader.read(target)
                sys("${meta.displayName}" + (if (meta.version.isBlank()) "" else " v${meta.version}"))
                add(
                    "  文件：${target.name}（${human(target.length())}）" +
                        "\n  模组 id：${meta.modId}" +
                        "\n  加载器：${meta.loader.label}" +
                        "\n  MC 范围：${meta.gameVersionRange.ifBlank { "未声明" }}" +
                        "\n  作者：${meta.authorText.ifBlank { "未声明" }}" +
                        "\n  依赖：${meta.depends.joinToString(", ").ifBlank { "无" }}" +
                        "\n  状态：${if (AddonManager.isEnabled(target)) "启用" else "停用"}",
                )
                if (meta.description.isNotBlank()) add("  简介：${meta.description.take(300)}")
                ModCompatibility.check(instance, AddonKind.MOD, meta).forEach {
                    if (it.level == ModCompatibility.Level.Warn) warn(it.text) else add("  · ${it.text}")
                }
            }

            "enable", "disable", "on", "off" -> {
                val target = pickMod(files, args.getOrNull(1))
                if (target == null) {
                    err("没找到这个模组：${args.getOrNull(1)}")
                    return
                }
                val wantEnabled = sub == "enable" || sub == "on"
                val currently = AddonManager.isEnabled(target)
                if (currently == wantEnabled) {
                    add("${target.name} 已经是${if (wantEnabled) "启用" else "停用"}状态")
                    return
                }
                val now = AddonManager.toggleEnabled(target)
                if (now == wantEnabled) {
                    add("${target.name} 已${if (wantEnabled) "启用" else "停用"}（重启服务端生效）")
                } else {
                    err("改名失败（可能有同名文件占用），当前仍是${if (now) "启用" else "停用"}")
                }
            }

            else -> err("用法：mods [list|info <序号>|enable <序号>|disable <序号>]")
        }
    }

    private fun handleWhitelist(
        instance: ServerInstance,
        args: List<String>,
        sys: (String) -> Unit,
        add: (String) -> Unit,
        warn: (String) -> Unit,
        err: (String) -> Unit,
    ) {
        val sub = args.firstOrNull()?.lowercase(Locale.US)
        when (sub) {
            null, "list" -> {
                val entries = WhitelistFile.read(instance.dir)
                if (entries.isEmpty()) {
                    add("白名单为空（加人：wl add <玩家名>）")
                } else {
                    sys("白名单（${entries.size} 人）：")
                    entries.forEach { add("  ${it.name}  ${it.uuid}") }
                }
            }
            "add" -> {
                val name = args.getOrNull(1)
                when {
                    name.isNullOrBlank() -> err("用法：wl add <玩家名>")
                    !WhitelistFile.isValidName(name) ->
                        err("玩家名不合法（3–16 位，字母/数字/下划线）")
                    else -> when (val r = WhitelistFile.add(instance.dir, name)) {
                        is WhitelistFile.AddResult.Added -> add("已加入白名单：$name（${r.uuid}）")
                        WhitelistFile.AddResult.AlreadyThere -> add("$name 已经在白名单里")
                        WhitelistFile.AddResult.Invalid -> err("玩家名不合法：$name")
                        WhitelistFile.AddResult.TooLong -> err("玩家名太长：$name")
                    }
                }
            }
            "remove", "rm" -> {
                val name = args.getOrNull(1)
                if (name.isNullOrBlank()) {
                    err("用法：wl remove <玩家名>")
                } else if (WhitelistFile.remove(instance.dir, name)) {
                    add("已从白名单移除：$name")
                } else {
                    warn("白名单里没有：$name")
                }
            }
            "clear" -> {
                WhitelistFile.clear(instance.dir)
                add("白名单已清空")
            }
            else -> err("用法：wl [list|add <玩家名>|remove <玩家名>|clear]")
        }
    }

    // ── 文件辅助 ──

    private fun catFile(
        instance: ServerInstance,
        path: String,
        limit: Int,
        add: (String) -> Unit,
        err: (String) -> Unit,
    ) {
        val file = resolveInInstance(instance, path)
        if (file == null) {
            err("路径不在实例目录内：$path")
            return
        }
        if (!file.isFile) {
            err("不是文件或不存在：$path")
            return
        }
        if (file.length() > MAX_CAT_BYTES) {
            err("文件太大（${human(file.length())}），用 `sh tail -n 50 文件名` 查看尾部")
            return
        }
        val lines = runCatching { file.readLines() }.getOrElse {
            err("读取失败：${it.message ?: "未知错误"}")
            return
        }
        // 二进制检测：有 NUL 就认为不是文本（cat 一个 region 文件只会刷屏乱码）
        if (lines.take(20).any { it.any { c -> c == '\u0000' } }) {
            err("看起来是二进制文件：$path")
            return
        }
        add("${relative(instance, file)}（共 ${lines.size} 行，显示前 ${minOf(limit, lines.size)} 行）：")
        lines.take(limit.coerceIn(1, 400)).forEach { add(it) }
        if (lines.size > limit) add("…（其余 ${lines.size - limit} 行未显示）")
    }

    private fun grep(
        instance: ServerInstance,
        needle: String,
        startPath: String?,
        sys: (String) -> Unit,
        add: (String) -> Unit,
        err: (String) -> Unit,
    ) {
        val start = resolveInInstance(instance, startPath ?: ".") ?: run {
            err("路径不在实例目录内：$startPath")
            return
        }
        val needleLower = needle.lowercase(Locale.US)
        val hits = mutableListOf<String>()
        var scanned = 0
        val maxHits = 60
        val maxScan = 400

        fun visit(dir: File, depth: Int) {
            if (hits.size >= maxHits || scanned >= maxScan || depth > 4) return
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (f in children) {
                if (hits.size >= maxHits || scanned >= maxScan) return
                if (f.isDirectory) {
                    // 世界文件与备份没必要扫（几十 GB、二进制）
                    if (f.name.lowercase(Locale.US) in SKIP_DIRS) continue
                    visit(f, depth + 1)
                    continue
                }
                if (f.length() > 4L * 1024 * 1024) continue
                val lower = f.name.lowercase(Locale.US)
                if (TEXT_EXT.none { lower.endsWith(it) }) continue
                scanned++
                runCatching {
                    f.useLines { seq ->
                        seq.forEachIndexed { idx, line ->
                            if (line.contains(needle, ignoreCase = true)) {
                                if (hits.size < maxHits) {
                                    hits += "${relative(instance, f)}:${idx + 1}: ${line.trim().take(200)}"
                                }
                            }
                        }
                    }
                }
            }
        }
        if (start.isDirectory) visit(start, 0) else {
            runCatching {
                start.useLines { seq ->
                    seq.forEachIndexed { idx, line ->
                        if (line.contains(needle, ignoreCase = true) && hits.size < maxHits) {
                            hits += "${relative(instance, start)}:${idx + 1}: ${line.trim().take(200)}"
                        }
                    }
                }
            }
        }
        sys("搜索「$needle」：扫描 $scanned 个文本文件，命中 ${hits.size} 处")
        if (hits.isEmpty()) add("（没有匹配）") else hits.forEach { add("  $it") }
        if (hits.size >= maxHits) add("（只显示前 $maxHits 处）")
        if (needleLower.isBlank()) err("搜索词为空")
    }

    /** 按序号（1 起）或文件名/模组名片段挑一个模组 */
    private fun pickMod(files: List<File>, key: String?): File? {
        if (key.isNullOrBlank()) return null
        key.toIntOrNull()?.let { idx -> return files.getOrNull(idx - 1) }
        val lower = key.lowercase(Locale.US)
        return files.firstOrNull { it.name.lowercase(Locale.US).contains(lower) }
            ?: files.firstOrNull { ModMetadataReader.read(it).modId.lowercase(Locale.US) == lower }
            ?: files.firstOrNull {
                ModMetadataReader.read(it).displayName.lowercase(Locale.US).contains(lower)
            }
    }

    /** 解析实例内的相对路径；越界（`../`、绝对路径跳出）返回 null */
    internal fun resolveInInstance(instance: ServerInstance, raw: String): File? = runCatching {
        val base = instance.dir.canonicalFile
        val cleaned = raw.trim()
        val relative = cleaned.trimStart('/', '\\')
        val file = File(base, relative.ifEmpty { "." })
        val canon = file.canonicalFile
        if (canon.path == base.path || canon.path.startsWith(base.path + File.separator)) canon else null
    }.getOrNull()

    private fun relative(instance: ServerInstance, file: File): String {
        val base = runCatching { instance.dir.canonicalFile.path }.getOrDefault(instance.dir.absolutePath)
        val path = runCatching { file.canonicalFile.path }.getOrDefault(file.absolutePath)
        val rel = if (path.startsWith(base)) {
            path.removePrefix(base).trimStart(File.separatorChar).ifEmpty { "." }
        } else {
            path
        }
        // 统一用 `/`：Android 上本来就是 Linux 分隔符，单测在 Windows 上跑时也保持一致
        return rel.replace(File.separatorChar, '/')
    }

    private fun formatFile(f: File): String = "${f.name}  ${human(f.length())}"

    internal fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    private fun jugdeJarState(jar: File): String = when {
        !jar.isFile -> "不存在"
        jar.length() < 64 * 1024 -> "偏小，可能下载不完整"
        else -> "就绪"
    }

    private val SKIP_DIRS = setOf("world", "world_nether", "world_the_end", "backups", "cache", "libraries")

    private val TEXT_EXT = listOf(
        ".log", ".txt", ".json", ".yml", ".yaml", ".properties", ".toml",
        ".cfg", ".conf", ".md", ".sh", ".ini", ".json5", ".snbt",
    )

    /** 简易 shell 分词：支持单/双引号（`props motd="欢迎 来到"` 这类） */
    internal fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in input) {
            when {
                escaped -> {
                    sb.append(c)
                    escaped = false
                }
                c == '\\' && quote != '\'' -> escaped = true
                quote != null && c == quote -> quote = null
                quote != null -> sb.append(c)
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        tokens += sb.toString()
                        sb.clear()
                    }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) tokens += sb.toString()
        return tokens
    }
}
