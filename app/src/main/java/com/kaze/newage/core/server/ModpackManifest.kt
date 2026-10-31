package com.kaze.newage.core.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 整合包清单解析：识别三种真实格式，并抽出「装什么、装到哪、从哪下」。
 *
 * ## 三种格式
 * 1. **Modrinth `.mrpack`** —— 核心清单 `modrinth.index.json`。清单**直接给出每个文件的
 *    下载 URL 与哈希**（sha1 / sha512），所以能免密钥下载并逐文件校验。
 *    包本身很小（只存引用），代价是首次安装必须联网。
 * 2. **CurseForge `.zip`** —— 核心清单 `manifest.json`。清单里只有
 *    **CurseForge 的 projectID / fileID**，实际文件要去 CurseForge 下载。
 *    ⚠️ CurseForge 的下载接口**必须带 API Key**（实测 `api.curseforge.com` 无 key 返回 403，
 *    网站那个 `curseforge.com/api/v1/.../download` 被 Cloudflare 拦成 403）。
 *    所以这里只解析并如实告知，不假装能下。**但**：CurseForge 的**服务端包**通常把
 *    mods 实体放在 `overrides/` 里（`files` 为空）—— 那种包走"通用懒人包"路径即可。
 * 3. **通用懒人包 / 已实体化的包** —— 没有上面两个清单，或清单为空但 mods 就在包里。
 *    解压即用，不需要联网。
 *
 * ## 为什么不猜文件内容
 * 清单里的 `path` 来自远端（Modrinth 清单是**不可信数据**），可能带 `../`。写入前必须
 * 由 [ModpackImporter.writeRemoteFile] 做边界校验，不能直接把 path 拼进 File 就写。
 */
object ModpackManifest {

    /** 加载器 */
    enum class Loader { VANILLA, FABRIC, FORGE, NEOFORGE, QUILT, UNKNOWN }

    /** 一个文件在服务端侧的适用性（照 Modrinth 的 env 语义） */
    enum class Side {
        /** 服务端也要（env.server = required / optional，或清单没写 env） */
        SERVER_OK,

        /** env.server = unsupported：纯客户端 mod，服务端装了会崩或没用 */
        CLIENT_ONLY,
    }

    /** 需要下载的一个文件 */
    data class RemoteFile(
        /** 相对实例根目录的路径（如 `mods/foo.jar`）；来自远端，写入前要校验边界 */
        val path: String,
        val urls: List<String>,
        val sha512: String?,
        val sha1: String?,
        val size: Long,
        val side: Side,
    )

    /** Modrinth 清单 */
    data class Modrinth(
        val name: String,
        val versionId: String,
        val mcVersion: String,
        val loader: Loader,
        /** 加载器版本（fabric-loader / forge / neoforge 的版本号）；空 = 清单没给 */
        val loaderVersion: String,
        val files: List<RemoteFile>,
        /** 需要摊平覆盖的目录（按顺序）：`overrides`、`server-overrides` */
        val overrideDirs: List<String>,
    ) {
        /** 服务端该装的文件（排除 env.server = unsupported） */
        val serverFiles: List<RemoteFile> get() = files.filter { it.side == Side.SERVER_OK }
        val skippedClientOnly: Int get() = files.count { it.side == Side.CLIENT_ONLY }
    }

    /** CurseForge 清单里的一条引用 */
    data class CurseForgeRef(val projectId: Long, val fileId: Long, val required: Boolean)

    /** CurseForge 清单 */
    data class CurseForge(
        val name: String,
        val mcVersion: String,
        val loader: Loader,
        /** 形如 `forge-47.2.0` 里的版本号；空 = 清单没给 */
        val loaderVersion: String,
        val refs: List<CurseForgeRef>,
        val overrideDir: String,
    )

    /** 识别出的格式 */
    sealed interface Format {
        data class Mrpack(val manifest: Modrinth) : Format
        data class Curse(val manifest: CurseForge) : Format
        /** 通用懒人包（或 CurseForge 服务端包这种"文件已实体在包里"的形态） */
        data object Plain : Format
    }

    /** 清单文件名 */
    const val MODRINTH_INDEX = "modrinth.index.json"
    const val CURSEFORGE_MANIFEST = "manifest.json"

    /** 单个清单文件的大小上限（正常几 KB ~ 几百 KB；再大就不是清单了） */
    private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 识别目录里是哪种整合包。
     *
     * 判定顺序有意义：**先看 Modrinth**。有些 `.mrpack` 里同时存在 `manifest.json`
     * （导出工具顺手塞的 CurseForge 兼容清单），但真正决定怎么装的是 modrinth 那份。
     */
    fun detect(root: File): Format {
        readManifest(root, MODRINTH_INDEX)?.let { text ->
            parseModrinth(text)?.let { return Format.Mrpack(it) }
        }
        readManifest(root, CURSEFORGE_MANIFEST)?.let { text ->
            parseCurseForge(text)?.let { return Format.Curse(it) }
        }
        return Format.Plain
    }

    private fun readManifest(root: File, name: String): String? = runCatching {
        val f = File(root, name)
        if (!f.isFile || f.length() > MAX_MANIFEST_BYTES || f.length() == 0L) null else f.readText()
    }.getOrNull()

    // ── Modrinth ──

    /**
     * 解析 `modrinth.index.json`。返回 null 表示"不是一份能用的 Modrinth 清单"
     * （缺 versionId / 没有 files / 结构不对），由调用方回退到别的格式。
     */
    internal fun parseModrinth(text: String): Modrinth? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject

        val files = root["files"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            // path 缺失的条目没法装，直接跳过（远端数据，不能假设字段齐全）
            val path = o["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val urls = o["downloads"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content.takeIf { u -> u.startsWith("http") } }
                .orEmpty()
            val hashes = o["hashes"]?.jsonObject
            val env = o["env"]?.jsonObject
            val serverEnv = env?.get("server")?.jsonPrimitive?.content
            RemoteFileRaw(
                path = path,
                urls = urls,
                sha512 = hashes?.get("sha512")?.jsonPrimitive?.content,
                sha1 = hashes?.get("sha1")?.jsonPrimitive?.content,
                size = o["fileSize"]?.jsonPrimitive?.longOrNull ?: 0L,
                // env.server == "unsupported" = 纯客户端 mod。服务端装了不但没用，
                // 有些还会直接崩（它引用了客户端才有的类）。
                clientOnly = serverEnv?.equals("unsupported", ignoreCase = true) == true,
            )
        }.orEmpty()

        val deps = root["dependencies"]?.jsonObject
        val mc = deps?.get("minecraft")?.jsonPrimitive?.content.orEmpty()
        val (loader, loaderVersion) = loaderOf(deps)

        val versionId = root["versionId"]?.jsonPrimitive?.content.orEmpty()
        val name = root["name"]?.jsonPrimitive?.content.orEmpty()

        // 一份既没有 files、又没有 versionId 的 JSON 不是 Modrinth 清单
        if (files.isEmpty() && versionId.isEmpty()) return@runCatching null

        Modrinth(
            name = name,
            versionId = versionId,
            mcVersion = mc,
            loader = loader,
            loaderVersion = loaderVersion,
            files = files.map {
                RemoteFile(it.path, it.urls, it.sha512, it.sha1, it.size,
                    if (it.clientOnly) Side.CLIENT_ONLY else Side.SERVER_OK)
            },
            overrideDirs = buildList {
                // 服务端安装要覆盖两份：overrides/（通用）与 server-overrides/（仅服务端）
                add("overrides")
                add("server-overrides")
            },
        )
    }.getOrNull()

    /** Modrinth 的 dependencies → 加载器与版本 */
    internal fun loaderOf(deps: JsonObject?): Pair<Loader, String> {
        if (deps == null) return Loader.VANILLA to ""
        fun v(k: String) = deps[k]?.jsonPrimitive?.content.orEmpty()
        v("forge").takeIf { it.isNotEmpty() }?.let { return Loader.FORGE to it }
        v("neoforge").takeIf { it.isNotEmpty() }?.let { return Loader.NEOFORGE to it }
        // 键名是 "fabric-loader"，值是 loader 版本
        v("fabric-loader").takeIf { it.isNotEmpty() }?.let { return Loader.FABRIC to it }
        v("quilt-loader").takeIf { it.isNotEmpty() }?.let { return Loader.QUILT to it }
        return Loader.VANILLA to ""
    }

    // ── CurseForge ──

    /**
     * 解析 `manifest.json`。返回 null 表示不是 CurseForge 清单。
     *
     * 注意 `files` 可能为空 —— 那是**服务端包**的正常形态：mods/config 都实体放在
     * `overrides/` 里，清单不引用任何远端文件。
     */
    internal fun parseCurseForge(text: String): CurseForge? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val mcObj = root["minecraft"]?.jsonObject
        val mcVersion = mcObj?.get("version")?.jsonPrimitive?.content.orEmpty()

        val refs = root["files"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val pid = o["projectID"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val fid = o["fileID"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            CurseForgeRef(pid, fid, o["required"]?.jsonPrimitive?.booleanOrNull ?: true)
        }.orEmpty()

        // 没有 minecraft 段的就不是整合包清单（可能是别的东西恰好叫 manifest.json）
        if (mcVersion.isEmpty() && root["manifestType"]?.jsonPrimitive?.content.isNullOrEmpty()) {
            return@runCatching null
        }

        val loaders = mcObj?.get("modLoaders")?.jsonArray.orEmpty()
        val primary = loaders.firstOrNull {
            it.jsonObject["primary"]?.jsonPrimitive?.booleanOrNull == true
        }?.jsonObject ?: loaders.firstOrNull()?.jsonObject
        val loaderId = primary?.get("id")?.jsonPrimitive?.content.orEmpty()
        val (loader, loaderVersion) = parseLoaderId(loaderId)

        CurseForge(
            name = root["name"]?.jsonPrimitive?.content.orEmpty(),
            mcVersion = mcVersion,
            loader = loader,
            loaderVersion = loaderVersion,
            refs = refs,
            // 清单可以自定义 overrides 目录名，默认是 overrides
            overrideDir = root["overrides"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: "overrides",
        )
    }.getOrNull()

    /**
     * CurseForge 的 modLoader id 形如 `forge-47.2.0`、`neoforge-21.1.9`、
     * `fabric-0.15.11`。老包的 id 里还会带 MC 版本（`forge-1.12.2-14.23.5.2860`），
     * 所以取**最后一段**作为加载器版本。
     */
    internal fun parseLoaderId(id: String): Pair<Loader, String> {
        if (id.isBlank()) return Loader.VANILLA to ""
        val dash = id.indexOf('-')
        if (dash <= 0) return Loader.VANILLA to ""
        val kind = id.substring(0, dash).lowercase()
        val rest = id.substring(dash + 1)
        val version = rest.split('-').last().trim()
        val loader = when {
            kind.startsWith("neoforge") -> Loader.NEOFORGE
            kind.startsWith("forge") -> Loader.FORGE
            kind.startsWith("fabric") -> Loader.FABRIC
            kind.startsWith("quilt") -> Loader.QUILT
            kind.startsWith("vanilla") -> Loader.VANILLA
            else -> Loader.UNKNOWN
        }
        return loader to version
    }

    /** 供 [ModpackImporter] 复用的中间模型（避免两处各写一套 JSON 解析） */
    internal data class RemoteFileRaw(
        val path: String,
        val urls: List<String>,
        val sha512: String?,
        val sha1: String?,
        val size: Long,
        val clientOnly: Boolean,
    )
}