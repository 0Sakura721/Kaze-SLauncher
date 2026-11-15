package com.kaze.newage.core.addons

import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 服务器模组体检：找出**客户端专用**、装在服务端纯属浪费（甚至会崩）的模组。
 *
 * 为什么需要：Fabric 生态里大量模组是渲染/界面类的（Sodium、Iris、ModMenu、JEI 的界面、
 * 小地图…）。玩家把"整合包"整套丢进 `mods/` 很常见，服务端会为这些模组白吃内存，
 * 部分模组还会因为缺少客户端类在启动时直接抛 `NoClassDefFoundError`。
 *
 * 判定用三条证据，**宁可漏报也不误报**：
 *  1. `fabric.mod.json` 的 `environment: "client"`（作者自己声明的，最可靠）；
 *  2. 已知客户端专用模组名单（Sodium/Iris/ModMenu 这类）；
 *  3. Modrinth 的 `server_side == "unsupported"`（网络查不到就跳过，不影响结论）。
 *
 * 只有明确命中才标 CLIENT_ONLY；剩下的标 NEEDED（服务端需要/双端）或 UNKNOWN。
 * 停用动作始终由用户勾选后触发 —— 自动停用可能把"作者标了 client 但其实双端都要"的模组误伤。
 */
object ModAudit {

    enum class Verdict { CLIENT_ONLY, NEEDED, UNKNOWN }

    data class Item(
        val file: File,
        val meta: ModMetadata,
        val verdict: Verdict,
        val reason: String,
    ) {
        val enabled: Boolean get() = AddonManager.isEnabled(file)
        val clientOnly: Boolean get() = verdict == Verdict.CLIENT_ONLY
    }

    /**
     * 已知的客户端专用模组（按 modId）。只收"几乎不可能在服务端有作用"的：
     * 渲染、界面、输入、小地图、光影、材质类。像 Lithium/Carpet 这种双端优化的**不能**进来。
     */
    private val KNOWN_CLIENT_ONLY: Set<String> = setOf(
        "sodium", "iris", "modmenu", "appleskin", "jade", "entityculling",
        "xaeros_minimap", "xaeros_worldmap", "journeymap", "betterf3",
        "continuity", "lambdynamiclights", "sodium-extra", "indium",
        "reeses-sodium-options", "fabricskyboxes", "zoomify", "ok_zoomer",
        "mouse-tweaks", "boat-item-view", "emi", "roughly-enough-items",
        "sodiumdynamiclights", "no-telemetry", "dark-loading-screen",
        "capes", "wavey-capes", "immediatelyfast", "fastanim",
    )

    /**
     * 体检（阻塞式：要读 jar、可能还会查 Modrinth）。
     *
     * @param includeDisabled 是否把已停用的也列出来（默认只看启用中的，避免重复操作）
     * @param useNetwork 是否用 Modrinth 的 server_side 标记兜底（离线时传 false）
     */
    suspend fun audit(
        instance: ServerInstance,
        includeDisabled: Boolean = false,
        useNetwork: Boolean = true,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): List<Item> = withContext(Dispatchers.IO) {
        val files = AddonManager.installed(instance, AddonKind.MOD)
            .filter { includeDisabled || AddonManager.isEnabled(it) }
        val result = ArrayList<Item>(files.size)
        files.forEachIndexed { index, file ->
            onProgress(index.toFloat() / files.size.coerceAtLeast(1), file.name)
            val meta = ModMetadataReader.read(file)
            val (verdict, reason) = classify(file, meta, useNetwork)
            result += Item(file, meta, verdict, reason)
        }
        onProgress(1f, "完成")
        result.sortedWith(compareByDescending<Item> { it.clientOnly }.thenBy { it.meta.displayName.lowercase(Locale.US) })
    }

    private fun classify(file: File, meta: ModMetadata, useNetwork: Boolean): Pair<Verdict, String> {
        if (meta.environment.equals("client", ignoreCase = true)) {
            return Verdict.CLIENT_ONLY to "作者声明只支持客户端（environment=client）"
        }
        val id = meta.modId.lowercase(Locale.US)
        if (id in KNOWN_CLIENT_ONLY) {
            return Verdict.CLIENT_ONLY to "已知客户端专用模组（渲染/界面类）"
        }
        if (useNetwork) {
            val project = lookupProject(file)
            if (project != null) {
                if (project.server_side == "unsupported" && project.client_side != "unsupported") {
                    return Verdict.CLIENT_ONLY to "Modrinth 标记：服务端不需要（server_side=unsupported）"
                }
                if (project.server_side == "required" || project.server_side == "optional") {
                    return Verdict.NEEDED to "Modrinth 标记：服务端需要（server_side=${project.server_side}）"
                }
            }
        }
        return Verdict.UNKNOWN to "无法判断（没有明确声明，或不在 Modrinth 上）"
    }

    private fun lookupProject(file: File): ModrinthProject? = runCatching {
        val sha1 = Downloader.sha1Of(file) ?: return null
        val version = ModrinthApi.versionByHash(sha1, "sha1")
        if (version.project_id.isBlank()) return null
        ModrinthApi.project(version.project_id)
    }.getOrNull()

    /**
     * 停用选中项，返回实际停用成功的数量。
     *
     * 只做 `toggleEnabled`（改名 .disabled），**不删除文件** —— 误判时用户随时能开回来。
     */
    fun disable(items: List<Item>): Int {
        var count = 0
        items.forEach { item ->
            if (!item.enabled) return@forEach
            val disabled = File(item.file.absolutePath + ".disabled")
            // toggleEnabled 返回的是**切换后的状态**（false = 已停用），不能当成功标志；
            // 而且切换后原路径已不存在，判断必须看 `.disabled` 那个文件。
            AddonManager.toggleEnabled(item.file)
            if (disabled.isFile && !item.file.exists()) count++
        }
        return count
    }
}
