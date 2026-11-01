package com.kaze.newage.core.addons

import com.kaze.newage.data.model.ServerInstance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 已装附加组件的**来源台账**：文件名 → 它来自哪个 Modrinth 项目、哪个版本。
 *
 * ## 为什么需要它
 * 原来 `install()` 把上游的原始文件名直接落到 `plugins/`（`EssentialsX-2.22.0.jar`），
 * **不记任何来源**。于是"检查插件更新"这件事根本无从下手 —— 光看文件名不知道它属于
 * 哪个项目、装的是哪个版本。
 *
 * ## 两条识别路径
 * 1. **台账**（本文件，`<实例>/plugins/.kaze-addons.json`）：安装时写入，最准，
 *    还记了 `versionId`，能直接判断"有没有新版"。
 * 2. **哈希回查**（[resolveByHash]）：台账里没有的（老版本装的、用户手动拖进去的），
 *    用文件的 SHA-512 问 Modrinth `/version_file/{hash}` —— 只要这个 jar 是从
 *    Modrinth 下的，就能反查出项目与版本。回查成功后**顺手补写台账**，下次不用再算。
 *
 * 两条都失败时才报"无法识别来源"，并提示用户重装一次。
 */
object AddonRegistry {

    private const val FILE_NAME = ".kaze-addons.json"

    @Serializable
    data class Entry(
        /** 磁盘上的文件名（不含目录） */
        val fileName: String,
        /** Modrinth 项目 id */
        val projectId: String,
        /** 项目 slug（给人看的） */
        val slug: String = "",
        /** 装的是哪个版本（Modrinth version id） */
        val versionId: String = "",
        /** 版本号字符串，如 2.22.0 */
        val versionNumber: String = "",
        /** 安装时的 loader（paper / fabric / forge…） */
        val loader: String = "",
        /** 安装时的 MC 版本 */
        val gameVersion: String = "",
    )

    @Serializable
    private data class Stored(val entries: List<Entry> = emptyList())

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun fileOf(instance: ServerInstance, kind: AddonKind): File =
        File(AddonManager.addonDir(instance, kind), FILE_NAME)

    /** 读台账（文件不存在 / 损坏都返回空表，绝不因为台账坏了影响插件使用） */
    fun read(instance: ServerInstance, kind: AddonKind): List<Entry> = runCatching {
        val f = fileOf(instance, kind)
        if (!f.exists()) return@runCatching emptyList()
        json.decodeFromString<Stored>(f.readText()).entries
    }.getOrDefault(emptyList())

    /** 覆盖写台账（失败只影响"下次要重新识别"，不影响插件本身） */
    fun write(instance: ServerInstance, kind: AddonKind, entries: List<Entry>): Boolean = runCatching {
        val f = fileOf(instance, kind)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), Stored(entries.distinctBy { it.fileName })))
        if (f.exists()) f.delete()
        tmp.renameTo(f)
    }.getOrDefault(false)

    /** 记一条（同文件名覆盖旧记录） */
    fun remember(instance: ServerInstance, kind: AddonKind, entry: Entry): Boolean {
        val cur = read(instance, kind).filterNot { it.fileName == entry.fileName }
        return write(instance, kind, cur + entry)
    }

    /** 删一条（插件被删/被换掉时） */
    fun forget(instance: ServerInstance, kind: AddonKind, fileName: String): Boolean {
        val cur = read(instance, kind)
        val next = cur.filterNot { it.fileName == fileName }
        if (next.size == cur.size) return true
        return write(instance, kind, next)
    }

    /**
     * 按哈希回查来源（台账里没有的存量插件）。
     *
     * Modrinth 的 `/version_file/{hash}?algorithm=sha512` 只要这个 jar 是从它那儿下的
     * 就能返回版本信息 —— 于是老用户不需要重装插件也能获得更新检查。
     *
     * @return 识别成功则返回 Entry（并已补写台账）；识别不了返回 null
     */
    fun resolveByHash(instance: ServerInstance, kind: AddonKind, file: File): Entry? {
        if (!file.exists() || file.length() == 0L) return null
        val hash = runCatching { com.kaze.newage.util.Downloader.sha512Of(file) }.getOrNull() ?: return null
        val version = runCatching { ModrinthApi.versionByHash(hash) }.getOrNull() ?: return null
        val projectId = version.project_id
        if (projectId.isBlank()) return null
        val entry = Entry(
            fileName = file.name,
            projectId = projectId,
            slug = "",
            versionId = version.id,
            versionNumber = version.version_number,
            loader = AddonManager.loaderFor(instance, kind),
            gameVersion = instance.mcVersion,
        )
        remember(instance, kind, entry)
        return entry
    }

    /**
     * 把台账与磁盘对齐：删掉已经不存在的文件记录。
     *
     * 不做的话，用户手动删了插件之后台账里还留着，检查更新会把它当成"缺失的插件"报出来。
     */
    fun prune(instance: ServerInstance, kind: AddonKind): List<Entry> {
        val entries = read(instance, kind)
        val onDisk = runCatching {
            AddonManager.addonDir(instance, kind).listFiles()?.map { it.name }?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
        val alive = entries.filter { it.fileName in onDisk }
        if (alive.size != entries.size) write(instance, kind, alive)
        return alive
    }
}