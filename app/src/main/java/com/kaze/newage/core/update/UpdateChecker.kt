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
        val apkUrl = (0 until assets.length())
            .map { assets.getJSONObject(it).optString("browser_download_url") }
            .firstOrNull { it.endsWith(".apk", ignoreCase = true) }
            ?: return null
        return ReleaseInfo(
            tag = tag,
            name = json.optString("name", tag),
            body = json.optString("body", "").trim(),
            apkUrl = apkUrl,
        )
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
