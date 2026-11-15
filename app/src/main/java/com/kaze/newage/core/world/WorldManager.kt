package com.kaze.newage.core.world

import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.Downloader
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** 一个世界存档（实例目录下的一个含 `level.dat` 的文件夹） */
data class WorldInfo(
    val dir: File,
    /** 目录名（也就是 server.properties 的 level-name） */
    val name: String,
    /** 存档里的世界名（level.dat 的 LevelName）；读不出来时等于 [name] */
    val displayName: String,
    /** 存档的 MC 版本（level.dat 的 Version.Name） */
    val versionName: String,
    /** `icon.png`（世界选择界面里那张 64×64 小图） */
    val iconFile: File?,
    val sizeBytes: Long,
    val lastPlayed: Long,
    val active: Boolean,
)

/**
 * 世界存档管理：列出、导入、下载、切换、删除。
 *
 * 为什么值得单独做：手机开服最常被问的两件事是"怎么换个地图"和"地图放哪"。
 * 旧版本只能靠文件管理器往 `Android/data` 里塞 —— 多数机型根本进不去。
 * 这里把"下载 zip → 解出 level.dat → 备份旧世界 → 切换 level-name"整条链路做掉，
 * 并且**永远先备份**：存档是本应用里唯一真正不可再生的数据。
 *
 * 世界图标来自存档自带的 `icon.png`（服务端生成世界时会写一张），
 * 所以列表里能看到玩家真正的世界预览图，而不是一个统一占位符。
 */
object WorldManager {

    private val NOT_WORLD_DIRS = setOf(
        "backups", "libraries", "logs", "plugins", "mods", "config", "cache",
        "crash-reports", "versions", "resourcepacks", "datapacks", "skills", ".kaze",
    )

    /** 判断目录是否是世界：必须有 level.dat */
    fun isWorldDir(dir: File): Boolean = dir.isDirectory && File(dir, "level.dat").isFile

    /** 当前 server.properties 里的主世界名（默认 world） */
    fun activeLevelName(instance: ServerInstance): String =
        ServerProperties.load(instance.dir)["level-name"]?.takeIf { it.isNotBlank() } ?: "world"

    /** 列出实例里的世界（主世界排最前，其余按最后游玩时间倒序） */
    fun worlds(instance: ServerInstance): List<WorldInfo> {
        val activeName = activeLevelName(instance)
        val dirs = instance.dir.listFiles()?.filter { d ->
            d.isDirectory && d.name !in NOT_WORLD_DIRS &&
                // Bukkit 的 nether/end 子世界也带 level.dat，列表里只显示主世界与用户导入的世界
                !d.name.endsWith("_nether") && !d.name.endsWith("_the_end") &&
                isWorldDir(d)
        }.orEmpty()
        return dirs.map { toInfo(it, activeName) }
            .sortedWith(compareByDescending<WorldInfo> { it.active }.thenByDescending { it.lastPlayed })
    }

    private fun toInfo(dir: File, activeName: String): WorldInfo {
        val info = LevelDat.read(File(dir, "level.dat"))
        return WorldInfo(
            dir = dir,
            name = dir.name,
            displayName = info?.levelName?.takeIf { it.isNotBlank() } ?: dir.name,
            versionName = info?.versionName.orEmpty(),
            iconFile = File(dir, "icon.png").takeIf { it.isFile },
            sizeBytes = dirSizeCapped(dir),
            lastPlayed = info?.lastPlayed?.takeIf { it > 0 } ?: dir.lastModified(),
            active = dir.name == activeName,
        )
    }

    /**
     * 世界体积：有上限的遍历。
     *
     * 一个跑了很久的服，world 能到几十 GB，逐个文件 sum 会让"进页面"卡在 IO 上。
     * 这里最多数 2 万个文件就停，显示的是"至少这么大"——比让界面转圈几十秒好。
     */
    private fun dirSizeCapped(dir: File): Long {
        var total = 0L
        var count = 0
        dir.walkTopDown().maxDepth(6).forEach { f ->
            if (f.isFile) {
                total += f.length()
                count++
            }
            if (count >= 20_000) return total
        }
        return total
    }

    // ── 导入 / 下载 ──

    /**
     * 导入一个世界 zip（常见地图包的形态：根目录或单层文件夹里放 `level.dat` + `region/`）。
     *
     * @return 导入后的世界（**不会**自动切换成当前世界；切换是另一个显式动作）
     */
    fun importZip(instance: ServerInstance, zipFile: File, nameHint: String = ""): WorldInfo {
        if (!Downloader.isZip(zipFile)) throw RuntimeException("不是有效的 zip 存档（可能是下载不完整）")
        val baseName = sanitizeWorldName(nameHint.ifBlank { zipFile.name.removeSuffix(".zip") })
        val target = uniqueWorldDir(instance, baseName)
        val tmp = File(instance.dir, ".kaze-import-" + System.currentTimeMillis())
        tmp.mkdirs()
        try {
            ZipFile(zipFile).use { zip ->
                var count = 0
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    // 只认识世界存档需要的目录，顺带挡住 zip-slip
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) return@forEach
                    val out = File(tmp, name)
                    val canonicalTmp = tmp.canonicalFile
                    val canonicalOut = out.canonicalFile
                    if (!canonicalOut.path.startsWith(canonicalTmp.path + File.separator)) {
                        throw RuntimeException("存档里有越界路径：$name")
                    }
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { ins ->
                        FileOutputStream(out).use { outs -> ins.copyTo(outs, 128 * 1024) }
                    }
                    count++
                    if (count > 200_000) throw RuntimeException("存档文件太多，已中止")
                }
            }
            // 找到真正含 level.dat 的那一层：可能是根，也可能被包了一层文件夹
            val root = findWorldRoot(tmp) ?: throw RuntimeException("压缩包里没有 level.dat，不像是个世界存档")
            // 目标目录**不能预建**：rename 到一个已存在的空目录在 Windows 上会失败，
            // 失败后用户看到的是"导入成功但世界是空的"（真正的原因被吞掉）。
            if (!root.renameTo(target)) {
                root.copyRecursively(target, overwrite = true)
                runCatching { root.deleteRecursively() }
            }
            if (!File(target, "level.dat").isFile) {
                throw RuntimeException("存档解压失败（level.dat 没有落到目标目录）")
            }
        } finally {
            runCatching { tmp.deleteRecursively() }
        }
        return toInfo(target, activeLevelName(instance))
    }

    /** 从链接下载世界 zip 再导入（URL 由用户从地图站复制，省掉"下载→找文件"两步） */
    fun downloadZip(
        instance: ServerInstance,
        url: String,
        nameHint: String = "",
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): WorldInfo {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw RuntimeException("只支持 https 直链（地图站里的下载地址）")
        }
        val dir = File(instance.dir, ".kaze-maps").apply { mkdirs() }
        val file = File(dir, "download-" + System.currentTimeMillis() + ".zip")
        try {
            Downloader.download(
                url,
                file,
                onProgress = { done, total ->
                    val p = if (total > 0) done.toFloat() / total else 0f
                    onProgress(p, "下载中 ${ContentHuman.bytes(done)} / ${ContentHuman.bytes(total)}")
                },
                validate = { Downloader.isZip(it) },
            )
            return importZip(instance, file, nameHint.ifBlank { url.substringAfterLast('/') })
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun findWorldRoot(dir: File): File? {
        if (File(dir, "level.dat").isFile) return dir
        val children = dir.listFiles()?.filter { it.isDirectory }.orEmpty()
        // 单层包装（"MyMap/MyMap/level.dat" 或 "MyMap/world/level.dat"）才往里找
        children.forEach { child ->
            if (File(child, "level.dat").isFile) return child
            child.listFiles()?.firstOrNull { it.isDirectory && File(it, "level.dat").isFile }
                ?.let { return it }
        }
        return null
    }

    private fun uniqueWorldDir(instance: ServerInstance, base: String): File {
        var dir = File(instance.dir, base)
        var n = 2
        while (dir.exists()) {
            dir = File(instance.dir, "$base-$n")
            n++
        }
        // 刻意不 mkdirs()：这个路径要作为 rename 的目标，必须不存在（见 importZip）
        return dir
    }

    // ── 切换 / 删除 ──

    /**
     * 把 [world] 切换成服务器的当前世界。
     *
     * 步骤刻意保守：旧世界**改名备份**（`world-backup-时间`）而不是删除；
     * 目标世界改名成配置里的 level-name（默认 `world`）而不是改配置 ——
     * 很多插件/脚本硬编码了 `world` 这个目录名。
     */
    fun activate(instance: ServerInstance, world: WorldInfo): WorldInfo {
        val levelName = activeLevelName(instance)
        if (world.name == levelName) return world
        val current = File(instance.dir, levelName)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        if (current.isDirectory) {
            val backup = File(instance.dir, "world-backup-$stamp")
            if (!current.renameTo(backup)) {
                throw RuntimeException("旧世界改名备份失败（可能仍被服务端占用，请先停止服务器）")
            }
        }
        if (!world.dir.renameTo(current)) {
            throw RuntimeException("世界目录切换失败（目标目录可能被占用）")
        }
        // 重新写一次 level-name，确保配置与磁盘一致（用户可能手工改过）
        val props = ServerProperties.load(instance.dir)
        props["level-name"] = levelName
        ServerProperties.save(instance.dir, props)
        return toInfo(current, levelName)
    }

    fun delete(instance: ServerInstance, world: WorldInfo): Boolean {
        if (world.active) return false
        return runCatching { world.dir.deleteRecursively() }.getOrDefault(false)
    }

    /** 世界目录名只保留安全字符（它同时是 level-name，会被写进 server.properties） */
    internal fun sanitizeWorldName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            // `..` 单独处理：虽然有分隔符替换后它已经逃不出去，但目录名/level-name 里
            // 出现一段点号没有意义，还会让某些工具把它当相对路径
            .replace("..", "_")
            .replace(Regex("""\s+"""), "_")
            .take(40)
        return cleaned.ifBlank { "world-" + System.currentTimeMillis() % 100000 }
    }
}

/**
 * 极简 NBT 读取器：只为从 `level.dat` 里拿到世界名、版本号、最后游玩时间。
 *
 * 不引 NBT 库的原因与 TOML 那边一样：只需要三个字段，而引入完整 NBT 解析
 * 会给 APK 增加几百 KB。这里只实现"读 + 跳过"（compound / list / 数组），
 * 未知类型直接放弃 —— 读不出来时上层会退化成目录名。
 */
private object LevelDat {

    data class Info(val levelName: String, val versionName: String, val lastPlayed: Long)

    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val MAX_DEPTH = 12

    fun read(file: File): Info? = runCatching {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        GZIPInputStream(file.inputStream()).use { gz ->
            val input = DataInputStream(gz)
            val root = readRoot(input) ?: return null
            val data = root["Data"] as? Map<*, *> ?: return null
            val name = data["LevelName"] as? String ?: ""
            val version = (data["Version"] as? Map<*, *>)?.get("Name") as? String ?: ""
            val lastPlayed = (data["LastPlayed"] as? Long) ?: 0L
            Info(name, version, lastPlayed)
        }
    }.getOrNull()

    private fun readRoot(input: DataInputStream): Map<String, Any?>? {
        val type = input.readUnsignedByte()
        if (type != TAG_COMPOUND) return null
        input.readUTF() // 根节点名为空串
        @Suppress("UNCHECKED_CAST")
        return readPayload(input, type, 0) as? Map<String, Any?>
    }

    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12

    private fun readPayload(input: DataInputStream, type: Int, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw RuntimeException("NBT 嵌套过深")
        return when (type) {
            TAG_BYTE -> input.readByte()
            TAG_SHORT -> input.readShort()
            TAG_INT -> input.readInt()
            TAG_LONG -> input.readLong()
            TAG_FLOAT -> input.readFloat()
            TAG_DOUBLE -> input.readDouble()
            TAG_BYTE_ARRAY -> ByteArray(input.readInt().coerceIn(0, MAX_BYTES.toInt())).also(input::readFully)
            TAG_STRING -> input.readUTF()
            TAG_LIST -> {
                val itemType = input.readUnsignedByte()
                val size = input.readInt().coerceIn(0, 1_000_000)
                val list = ArrayList<Any?>(minOf(size, 1024))
                repeat(size) { list.add(readPayload(input, itemType, depth + 1)) }
                list
            }
            TAG_COMPOUND -> {
                val map = LinkedHashMap<String, Any?>()
                while (true) {
                    val childType = input.readUnsignedByte()
                    if (childType == TAG_END) break
                    val name = input.readUTF()
                    map[name] = readPayload(input, childType, depth + 1)
                }
                map
            }
            TAG_INT_ARRAY -> IntArray(input.readInt().coerceIn(0, 1_000_000)).also { arr ->
                repeat(arr.size) { arr[it] = input.readInt() }
            }
            TAG_LONG_ARRAY -> LongArray(input.readInt().coerceIn(0, 1_000_000)).also { arr ->
                repeat(arr.size) { arr[it] = input.readLong() }
            }
            else -> throw RuntimeException("未知 NBT 类型 $type")
        }
    }
}

/** 内容中心共用的体积格式化（世界/资源包/基岩包都用它） */
internal object ContentHuman {
    fun bytes(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}
