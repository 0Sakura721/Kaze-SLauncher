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
 *  - preview（默认）：全部 Release 里取发布日期最新的一条（含 prerelease 预览版）
 *  - stable：同一份列表里过滤掉 prerelease 后取发布日期最新的一条
 *
 * ## 更新判定以「发布日期」为准，不再比较版本号
 * 版本号比较（0.1.2 → 0.1.3-fix 这类）需要枚举后缀语义，规则越叠越多还容易漏。
 * 改为：候选 Release 的 **`published_at`** 晚于「用户正在运行的那个 Release 的
 * `published_at`」→ 有更新。基线的确定：
 *  1. 首选：Releases 列表里 tag 与当前 versionName 匹配的那条的发布日期；
 *  2. 匹配不到（本地构建 / tag 改名）：退回用**安装时间**兜底。
 * 两个日期都来自 GitHub 服务端时间，不受设备时钟影响。
 */
object UpdateChecker {

    const val REPO = "0Sakura721/Kaze-SLauncher"
    private const val API_LIST = "https://api.github.com/repos/$REPO/releases?per_page=50"

    /**
     * GitHub 下载加速镜像（社区常用线路，前缀直拼 GitHub 原链）。
     *
     * ## 顺序按实测来，不是"看起来像"
     * 同一台机器、同一个 30 MB 的包实测（取前 1 MB，10s 上限）：
     * ```
     *  gh.xxooo.cf            2.4s ✓     gh.nxnow.top            4.1s ✓
     *  gitproxy.mrhjx.cn      2.1s ✓     gh.zwy.one              5.7s ✓
     *  github.ednovas.xyz     2.4s ✓     cdn.gh-proxy.com        5.4s ✓
     *  gh-proxy.com           2.7s ✓     ghproxy.net             6.2s ✓
     *  ghproxy.monkeyray.net  3.2s ✓     github.boki.moe         4.3s ✓
     *  ghfast.top             3.4s ✓
     *  ── 以上可用 ──
     *  gh.llkk.cc / ghproxy.cc / gh.6yit.com / gh.jasonzeng.dev /
     *  ghproxy.cfd / github.moeyy.xyz / hub.gitmirror.com       ✗ 全部失败
     *  直连 github.com                                          40 KB/s ← 最慢
     * ```
     *
     * ⚠️ 镜像可用性**抖动很大**：同一批源用 2 MB 探测时只剩 2 个通过，
     * 换个时间/换探测长度结果就不同。所以这里保留一批（而不是只留最快的两个），
     * 由 [com.kaze.newage.util.Downloader.probeFastest] 每次下载前现测现选。
     *
     * ## 一个测出来的**否定结论**：不要做多线程分块下载
     * 同一镜像同一时刻整包下载：
     * ```
     *  1 连接  7.2 MB/s   ← 最快
     *  4 连接  3.5 MB/s
     *  8 连接  2.0 MB/s
     * ```
     * 镜像按**连接数**限速，连接越多每条越慢、总速反而下降。
     * 谁想加"6 线程 Range 下载"之前，请先复测这一条。
     */
    private val MIRRORS = listOf(
        "https://gitproxy.mrhjx.cn/",
        "https://gh.xxooo.cf/",
        "https://github.ednovas.xyz/",
        "https://gh-proxy.com/",
        "https://ghproxy.monkeyray.net/",
        "https://ghfast.top/",
        "https://gh.nxnow.top/",
        "https://gh.zwy.one/",
        "https://cdn.gh-proxy.com/",
        "https://github.boki.moe/",
        "https://ghproxy.net/",
        // 以下为历史线路，当前实测不通，留作不同网络环境下的兜底
        "https://gh.llkk.cc/",
        "https://hub.gitmirror.com/",
        "https://github.limoruirui.com/",
        "https://github.abskoop.workers.dev/",
        "https://github.tbedu.top/",
        "https://github.moeyy.xyz/",
        "https://mirror.ghproxy.com/",
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
        /**
         * 该 Release 附带的**增量补丁**资产（可能为空）。
         *
         * 命名约定：`patch-<from>-to-<to>-<abi>.zip` + 同名 `.json`（元数据，含 `baseSha256`）。
         * 这里只收集 URL（不额外发请求）—— 是否可用要等真正下载时才判断：
         * 得先拿到 `.json` 里的 `baseSha256` 与本机已装 APK 的 sha256 比对。
         */
        val patchAssets: List<PatchAsset> = emptyList(),
        /** 发布时间（GitHub `published_at`，epoch 毫秒）——更新判定以此为准 */
        val publishedAt: Long = 0,
        /** 发布日期的显示文本（本地时区的 yyyy-MM-dd；解析失败为空串） */
        val publishedAtText: String = "",
        /** GitHub 的 prerelease 标记（stable 通道会过滤掉） */
        val prerelease: Boolean = false,
    )

    /** 一对补丁资产：`.json` 元数据 + 对应的 `.zip` */
    data class PatchAsset(
        val name: String,
        val jsonUrl: String,
        val zipUrl: String,
    )

    /**
     * 查询更新：拉 Releases 列表 → 按通道过滤 → 取发布日期最新的一条 →
     * 与「当前运行的 Release」的发布日期比较，**没有更新的返回 null**（网络/解析失败抛异常）。
     *
     * @param channel preview（含预览版，默认）| stable（仅正式版）
     * @param currentVersion 当前安装包的 versionName（与 tag 匹配来确定基线日期）
     * @param installedAt 当前安装包的安装时间（versionName 匹配不到任何 Release 时的兜底基线）
     */
    fun check(channel: String = "preview", currentVersion: String = "", installedAt: Long = 0L): ReleaseInfo? {
        val text = try {
            Downloader.downloadText(API_LIST, timeoutMs = 20000)
        } catch (e: Exception) {
            // 404 = 仓库不存在或没有任何 Release（老版本兼容，理论上列表接口返回空数组）
            if (e.message?.contains("404") == true) return null
            throw e
        }
        val arr = JSONArray(text)
        val releases = (0 until arr.length())
            .mapNotNull { parseRelease(arr.optJSONObject(it) ?: return@mapNotNull null) }
        return selectUpdate(releases, channel, currentVersion, installedAt)
    }

    /** 单条 Release JSON → [ReleaseInfo]；无 tag / 草稿 / 没有可用 APK 资产返回 null */
    internal fun parseRelease(json: JSONObject): ReleaseInfo? {
        val tag = json.optString("tag_name", "").removePrefix("v")
        if (tag.isBlank()) return null
        if (json.optBoolean("draft", false)) return null
        val assets = json.optJSONArray("assets") ?: return null
        // 按设备架构选对应 APK（release 三版本：arm64-v8a / armeabi-v7a / universal）
        val asset = pickApkAsset(assets) ?: return null
        val publishedAt = parseDate(json.optString("published_at", ""))
            ?: parseDate(json.optString("created_at", ""))
            ?: 0L
        return ReleaseInfo(
            tag = tag,
            name = json.optString("name", tag),
            body = json.optString("body", "").trim(),
            apkUrl = asset.optString("browser_download_url"),
            apkSha256 = parseSha256(asset.optString("digest", "")),
            patchAssets = pickPatchAssets(assets),
            publishedAt = publishedAt,
            publishedAtText = formatDate(publishedAt),
            prerelease = json.optBoolean("prerelease", false),
        )
    }

    /**
     * 更新选择本体（纯函数，单独测）：
     *  1. 通道过滤（stable 剔除 prerelease）；
     *  2. 候选 = 发布日期最新的一条；
     *  3. 基线 = tag 与 currentVersion 匹配的那条 Release 的发布日期，匹配不到用 installedAt；
     *  4. 候选日期 **严格晚于** 基线才算更新（同日重发不提示）。
     */
    internal fun selectUpdate(
        releases: List<ReleaseInfo>,
        channel: String,
        currentVersion: String,
        installedAt: Long = 0L,
    ): ReleaseInfo? {
        val applicable = releases.filter { channel != "stable" || !it.prerelease }
        val candidate = applicable.filter { it.publishedAt > 0 }.maxByOrNull { it.publishedAt }
            ?: return null
        val baseline = releases
            .firstOrNull { sameRelease(it.tag, currentVersion) }
            ?.publishedAt
            ?: installedAt
        return candidate.takeIf { it.publishedAt > baseline }
    }

    /** tag 与 versionName 是否指同一个版本（去 v 前缀、忽略大小写与首尾空白） */
    internal fun sameRelease(tag: String, versionName: String): Boolean {
        fun norm(s: String) = s.trim().trimStart('v', 'V').lowercase()
        val t = norm(tag)
        return t.isNotEmpty() && t == norm(versionName)
    }

    /** GitHub 的 ISO-8601 时间（2026-10-02T18:22:27Z）→ epoch 毫秒；解析失败 null */
    internal fun parseDate(iso: String): Long? =
        iso.trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** epoch 毫秒 → 本地时区的 yyyy-MM-dd 显示文本；解析失败空串 */
    internal fun formatDate(epochMs: Long): String = runCatching {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(epochMs))
    }.getOrDefault("")

    /**
     * 从 release 资产里挑出增量补丁（`patch-*.json`，且配对的 `patch-*.zip` 必须也在）。
     *
     * 纯函数、单独测：命名对不上时宁可不返回，也不要让 `UpdateInstaller` 去请求一个
     * 不存在的 URL —— 那会白白拖慢更新，失败原因还看不出来。
     *
     * 这里**不**按 ABI 过滤：一个 release 里两个 ABI 的补丁都可能存在，而"该用哪一份"
     * 取决于本机已装 APK 的 sha256（`UpdateInstaller` 比对 `baseSha256` 才准），
     * 名字里带不带 arch 只是辅助信息。
     */
    internal fun pickPatchAssets(assets: JSONArray): List<PatchAsset> {
        val urls = HashMap<String, String>()
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.isNotBlank() && url.isNotBlank()) urls[name] = url
        }
        return urls.keys
            .filter { it.startsWith("patch-") && it.endsWith(".json") }
            .sorted()
            .mapNotNull { jsonName ->
                val zipName = jsonName.removeSuffix(".json") + ".zip"
                val zipUrl = urls[zipName] ?: return@mapNotNull null
                PatchAsset(jsonName, urls.getValue(jsonName), zipUrl)
            }
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
        // ⚠️ 直连 github.com 放**最后**，只当兜底：实测它只有 ~40 KB/s（镜像 700~900 KB/s），
        // 放最前面时"能下但极慢"，用户感受就是更新卡住了。
        MIRRORS.map { it + apkUrl } + apkUrl
}
