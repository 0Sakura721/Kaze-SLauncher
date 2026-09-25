package com.kaze.newage.core.update

import com.kaze.newage.util.Downloader
import org.json.JSONArray
import org.json.JSONObject

/**
 * 检查更新：GitHub 生态常规方案——
 * 版本信息走 GitHub Releases API，APK 下载走 GitHub 原链 + 多个国内加速镜像，
 * 由 Downloader 并发测速选最快源、失败自动回退（断点续传）。
 *
 * 双通道：
 *  - preview（默认）：/releases 列表取最新一条（含 prerelease 预览版）
 *  - stable：/releases/latest（仅正式版，GitHub 对纯 prerelease 仓库恒返 404）
 */
object UpdateChecker {

    const val REPO = "0Sakura721/Kaze-SLauncher"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val API_LIST = "https://api.github.com/repos/$REPO/releases"

    /** GitHub 下载加速镜像（社区常用线路，前缀直拼 GitHub 原链） */
    private val MIRRORS = listOf(
        "https://github.moeyy.xyz/",
        "https://mirror.ghproxy.com/",
        "https://hub.gitmirror.com/",
        "https://github.boki.moe/",
        "https://github.ednovas.xyz/",
        "https://github.limoruirui.com/",
        "https://github.abskoop.workers.dev/",
        "https://github.tbedu.top/",
        "https://gh.llkk.cc/",
        "https://gh.nxnow.top/",
        "https://ghproxy.monkeyray.net/",
        "https://gitproxy.mrhjx.cn/",
        "https://gh.zwy.one/",
    )

    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val body: String,
        val apkUrl: String,
        /**
         * 发布方给出的 APK SHA-256（GitHub asset 的 `digest` 字段，形如 `sha256:ab12…`）。
         *
         * APK 是从多个**第三方加速镜像**下载的（见 [sources]），任一镜像被控制就能返回一个
         * 「魔数合法、体积足够」的篡改包，而应用会引导用户安装它。这个值取自
         * `api.github.com`（直连、可信），是这条链路上唯一的完整性依据。
         *
         * 取不到时为 null（见 [matchesDigest] 的处理）。
         */
        val apkSha256: String? = null,
    )

    /**
     * 查询最新 Release；无任何 Release（HTTP 404）返回 null（= 暂无更新）；网络/解析失败抛异常。
     * @param channel preview（含预览版，默认）| stable（仅正式版）
     */
    fun check(channel: String = "preview"): ReleaseInfo? {
        val url = if (channel == "stable") API_LATEST else API_LIST
        val text = try {
            Downloader.downloadText(url, timeoutMs = 20000)
        } catch (e: Exception) {
            // 404 = 仓库没有符合该通道的 Release（GitHub 对无 release 的 /releases/latest 恒返 404）
            if (e.message?.contains("404") == true) return null
            throw e
        }
        val json: JSONObject = if (channel == "stable") {
            JSONObject(text)
        } else {
            val arr = JSONArray(text)
            if (arr.length() == 0) return null
            arr.getJSONObject(0)
        }
        val tag = json.optString("tag_name", "").removePrefix("v")
        val assets = json.optJSONArray("assets") ?: return null
        // 按设备架构选对应 APK（release 三版本：arm64-v8a / armeabi-v7a / universal）
        val asset = pickApkAsset(assets) ?: return null
        return ReleaseInfo(
            tag = tag,
            name = json.optString("name", tag),
            body = json.optString("body", "").trim(),
            apkUrl = asset.optString("browser_download_url"),
            apkSha256 = parseSha256(asset.optString("digest", "")),
        )
    }

    /**
     * 解析 GitHub 的 `digest` 字段（形如 `sha256:ab12…`）。
     * 只认 sha256 且必须是 64 位十六进制，其余（空/其它算法/格式异常）一律返回 null。
     */
    internal fun parseSha256(digest: String): String? {
        val v = digest.trim()
        if (!v.startsWith("sha256:", ignoreCase = true)) return null
        val hex = v.substringAfter(':').trim().lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
    }

    /** 当前设备架构在发布命名中的后缀（asset 名形如 Kaze-SLauncher-v0.1.1-<arch>.apk） */
    fun archSuffix(): String = when {
        android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64-v8a", true) || it.contains("aarch64", true) } ->
            "arm64-v8a"
        android.os.Build.SUPPORTED_ABIS.any { it.contains("armeabi-v7a", true) || it.contains("armeabi", true) } ->
            "armeabi-v7a"
        else -> "universal"
    }

    /**
     * 按架构挑下载资产。
     *
     * 命名自 v0.3.0 起变了：v7a 包带 `-experimental` 后缀（例：
     * `Kaze-SLauncher-v0.3.0-armeabi-v7a-experimental.apk`），且**不再发布 universal**。
     * 旧逻辑是「精确后缀 → -arm64 → -universal → 任意 .apk」，两个后果：
     *  - 带 -experimental 的包精确后缀匹配不上；
     *  - universal 没了之后会掉到「任意 .apk」，可能把 arm64 包发给 v7a 设备，
     *    装上去直接 INSTALL_FAILED_NO_MATCHING_ABIS。
     * 改为：精确 → 名字里含本机架构 → 旧命名兼容 → universal（老版本还有）→
     * 只剩一个且**不是别的架构**时才用。任何时候都不会拿到装不上的架构包。
     *
     * 返回整个 asset 对象（而不只是 URL）：这样 URL 与它的 `digest` 一定成对取到，
     * 不会出现「URL 取 A、哈希取 B」的错配。
     */
    private fun pickApkAsset(assets: JSONArray): JSONObject? {
        val items = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("browser_download_url").isNotBlank() }
        val picked = pickAssetName(
            items.map { item ->
                item.optString("name").ifBlank {
                    item.optString("browser_download_url").substringAfterLast('/')
                }
            },
            archSuffix(),
        ) ?: return null
        return items.firstOrNull { item ->
            val n = item.optString("name").ifBlank {
                item.optString("browser_download_url").substringAfterLast('/')
            }
            n == picked
        }
    }

    /**
     * 选包规则本体（纯函数，单独测）：
     *  1. 精确后缀 `-<arch>.apk`
     *  2. 名字里含本机架构（覆盖 `-arm64-v8a-experimental` 这类修饰后缀）
     *  3. 旧命名 `-arm64.apk`（仅 arm64）
     *  4. `-universal.apk`（0.2.0 及更早还有）
     *  5. 只剩一个且不是别的架构时才用
     *
     * 底线：**永远不返回属于别的架构的包**——装上去只会 INSTALL_FAILED_NO_MATCHING_ABIS。
     */
    internal fun pickAssetName(names: List<String>, arch: String): String? {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val foreignArch = when (arch) {
            "arm64-v8a" -> listOf("armeabi", "armhf", "v7a")
            "armeabi-v7a" -> listOf("arm64", "aarch64")
            else -> emptyList()
        }
        apks.firstOrNull { it.endsWith("-$arch.apk", ignoreCase = true) }?.let { return it }
        apks.firstOrNull { it.contains(arch, ignoreCase = true) }?.let { return it }
        if (arch == "arm64-v8a") {
            apks.firstOrNull { it.endsWith("-arm64.apk", ignoreCase = true) }?.let { return it }
        }
        apks.firstOrNull { it.endsWith("-universal.apk", ignoreCase = true) }?.let { return it }
        return apks.filter { name -> foreignArch.none { name.contains(it, ignoreCase = true) } }
            .singleOrNull()
    }

    /** GitHub 原链 + 全部镜像（下载时由 Downloader 测速择优） */
    fun sources(apkUrl: String): List<String> =
        listOf(apkUrl) + MIRRORS.map { it + apkUrl }

    /**
     * 版本号比较：latest 比 current 新 → true。
     * 支持 0.1.0 / v1.2.3-beta.1 / 26.2 形式；关键修复：预发布段不再数字化归零——
     * 同主版本下「正式版 > 预发布」，否则 beta 用户永远收不到同号转正的提示。
     */
    fun isNewer(latest: String, current: String): Boolean {
        fun split(v: String): Pair<List<Long>, List<String>> {
            val main = v.substringBefore('-').trimStart('v', 'V')
            val pre = v.substringAfter('-', "").split('.', ' ').filter { it.isNotEmpty() }
            val nums = Regex("\\d+").findAll(main).map { it.value.toLong() }.toList()
            return nums to pre
        }
        fun rank(tok: String): Long = when (tok.lowercase()) {
            "dev" -> 0L
            "alpha", "a" -> 1L
            "beta", "b" -> 2L
            "preview", "rc", "cr", "milestone" -> 3L
            else -> Long.MAX_VALUE // 未知段视作最"正式"
        }
        fun comparePre(a: List<String>, b: List<String>): Int {
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val at = a.getOrNull(i)
                val bt = b.getOrNull(i)
                if (at == bt) continue
                if (at == null) return -1          // beta < beta.1（缺段更早）
                if (bt == null) return 1
                val ar = at.toLongOrNull()
                val br = bt.toLongOrNull()
                val cmp = when {
                    ar != null && br != null -> ar.compareTo(br)
                    ar != null -> 1                 // 数字段视为更接近正式
                    br != null -> -1
                    else -> rank(at).compareTo(rank(bt))
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
        val (lv, lp) = split(latest)
        val (cv, cp) = split(current)
        val n = maxOf(lv.size, cv.size)
        for (i in 0 until n) {
            val x = lv.getOrNull(i) ?: 0L
            val y = cv.getOrNull(i) ?: 0L
            if (x != y) return x > y
        }
        return when {
            lp.isEmpty() && cp.isNotEmpty() -> true   // latest 正式 vs current 预发布 → 已转正
            lp.isNotEmpty() && cp.isEmpty() -> false  // latest 预发布 vs current 正式 → 不是更新
            else -> comparePre(lp, cp) > 0
        }
    }
}
