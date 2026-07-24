package com.mcserver.launcher.server

import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * 镜像下载源选项。
 */
data class MirrorOption(
    val key: String,
    val name: String,
    val url: String,
    val region: String = "" // CN/US/EU 等
)

/**
 * 镜像测速结果。
 */
data class MirrorTestResult(
    val key: String,
    val name: String,
    val url: String,
    val latencyMs: Long,
    val isBest: Boolean,
    val error: String? = null
)

/**
 * 镜像代理配置。
 */
data class MirrorProxy(
    val key: String,
    val name: String,
    val prefix: String,
    /** 拼接方式: "direct" = prefix/url, "param" = prefix/?url=url */
    val type: String = "direct",
    val region: String = "CN"
)

/**
 * 通用镜像测速工具。
 *
 * 支持 35+ 个 GitHub 镜像代理源，可对任意 GitHub 文件 URL 自动生成全部镜像。
 * 也提供预设的 Ubuntu 24.04 rootfs 镜像站列表。
 */
object MirrorSpeedTester {

    // ════════════════════════════════════════════════════════════
    //  35+ GitHub 镜像代理配置表（来自 operit.app）
    // ════════════════════════════════════════════════════════════

    /**
     * 全部 GitHub 镜像代理配置。
     * 分为两类：
     * - type="direct"：直接拼接，https://ghproxy.net/原始URL
     * - type="param"：参数传递，https://h233.workers.dev/?url=原始URL
     */
    val MIRROR_PROXIES: List<MirrorProxy> = listOf(
        // ── 标准反向代理（direct 拼接） ──
        MirrorProxy("github", "GitHub 官方", "https://github.com", type = "direct", region = "US"),
        MirrorProxy("ghproxy", "ghproxy 加速", "https://ghproxy.net", type = "direct", region = "CN"),
        MirrorProxy("gh_dl", "gh.dl 加速", "https://gh.dl.csk.moe", type = "direct", region = "CN"),
        MirrorProxy("mirror_ghproxy", "mirror.ghproxy 加速", "https://mirror.ghproxy.com", type = "direct", region = "CN"),
        MirrorProxy("ghproxy_cfd", "ghproxy.cfd 加速", "https://ghproxy.cfd", type = "direct", region = "CN"),
        MirrorProxy("gh_idayer", "gh.idayer 加速", "https://gh.idayer.com", type = "direct", region = "CN"),
        MirrorProxy("gh_ghproxy", "gh.ghproxy 加速", "https://gh.ghproxy.net", type = "direct", region = "CN"),
        MirrorProxy("gh_1888866", "gh.1888866 加速", "https://gh.1888866.xyz", type = "direct", region = "CN"),
        MirrorProxy("gitclone", "gitclone 加速", "https://gitclone.com", type = "direct", region = "CN"),
        MirrorProxy("ghps", "ghps 加速", "https://ghps.cc", type = "direct", region = "CN"),
        MirrorProxy("ghproxy_com", "gh-proxy 加速", "https://gh-proxy.com", type = "direct", region = "CN"),
        MirrorProxy("moeyy", "Moeyy 加速", "https://github.moeyy.xyz", type = "direct", region = "CN"),
        MirrorProxy("gh_boki", "gh.boki 加速", "https://gh.boki.moe", type = "direct", region = "CN"),
        MirrorProxy("gh_monlor", "gh.monlor 加速", "https://gh.monlor.com", type = "direct", region = "CN"),
        MirrorProxy("fastgit", "FastGit 加速", "https://fastgit.cc", type = "direct", region = "CN"),
        MirrorProxy("gitmirror", "GitMirror 加速", "https://gitmirror.com", type = "direct", region = "CN"),
        MirrorProxy("ghfast", "ghfast 加速", "https://ghfast.top", type = "direct", region = "CN"),
        MirrorProxy("kgithub", "KGitHub 加速", "https://kkgithub.com", type = "direct", region = "CN"),

        // ── Workers.dev 代理（?url= 参数传递） ──
        MirrorProxy("h233", "H233 加速", "https://h233.workers.dev", type = "param", region = "CN"),
        MirrorProxy("jasonzeng", "Jasonzeng 加速", "https://jasonzeng.workers.dev", type = "param", region = "CN"),
        MirrorProxy("ednovas", "Ednovas 加速", "https://ednovas.workers.dev", type = "param", region = "CN"),
        MirrorProxy("crashmc", "Crashmc 加速", "https://crashmc.workers.dev", type = "param", region = "CN"),
        MirrorProxy("yylx", "Yylx 加速", "https://yylx.workers.dev", type = "param", region = "CN"),
        MirrorProxy("mrhjx", "Mrhjx 加速", "https://mrhjx.workers.dev", type = "param", region = "CN"),
        MirrorProxy("cxkpro", "Cxkpro 加速", "https://cxkpro.workers.dev", type = "param", region = "CN"),
        MirrorProxy("xxoo0", "Xxoo0 加速", "https://xxoo0.workers.dev", type = "param", region = "CN"),
        MirrorProxy("limoruirui", "Limoruirui 加速", "https://limoruirui.workers.dev", type = "param", region = "CN"),
        MirrorProxy("likk", "Likk 加速", "https://likk.workers.dev", type = "param", region = "CN"),
        MirrorProxy("npee", "Npee 加速", "https://npee.workers.dev", type = "param", region = "CN"),
        MirrorProxy("nxnow", "Nxnow 加速", "https://nxnow.workers.dev", type = "param", region = "CN"),
        MirrorProxy("zwy", "Zwy 加速", "https://zwy.workers.dev", type = "param", region = "CN"),
        MirrorProxy("monkeyray", "Monkeyray 加速", "https://monkeyray.workers.dev", type = "param", region = "CN"),
        MirrorProxy("xx9527", "Xx9527 加速", "https://xx9527.workers.dev", type = "param", region = "CN"),
        MirrorProxy("workers", "Workers 加速", "https://workers.workers.dev", type = "param", region = "CN"),
        MirrorProxy("tbedu", "Tbedu 加速", "https://tbedu.workers.dev", type = "param", region = "CN"),
        MirrorProxy("firewall_lxstd", "Firewall.lxstd 加速", "https://firewall.lxstd.workers.dev", type = "param", region = "CN"),
        MirrorProxy("geekertao", "Geekertao 加速", "https://geekertao.workers.dev", type = "param", region = "CN"),
        MirrorProxy("chjina", "Chjina 加速", "https://chjina.workers.dev", type = "param", region = "CN"),
        MirrorProxy("hwinzniej", "Hwinzniej 加速", "https://hwinzniej.workers.dev", type = "param", region = "CN"),
    )

    // ════════════════════════════════════════════════════════════
    //  通用镜像生成函数
    // ════════════════════════════════════════════════════════════

    /**
     * 根据 GitHub 原始文件 URL，生成全部镜像源的 [MirrorOption] 列表。
     *
     * @param githubUrl GitHub 上的原始文件 URL
     * @return 包含所有镜像源的列表（包括 GitHub 官方）
     */
    fun buildGitHubFileMirrors(githubUrl: String): List<MirrorOption> {
        return MIRROR_PROXIES.map { proxy ->
            val url = when (proxy.type) {
                "param" -> "${proxy.prefix}/?url=$githubUrl"
                else -> "${proxy.prefix}/$githubUrl"
            }
            MirrorOption(proxy.key, proxy.name, url, proxy.region)
        }
    }

    /**
     * 根据 GitHub repo、release tag 和文件名，生成全部镜像源列表。
     */
    fun buildGitHubReleaseMirrors(
        repo: String,
        tag: String,
        fileName: String
    ): List<MirrorOption> {
        val rawUrl = "https://github.com/$repo/releases/download/$tag/$fileName"
        return buildGitHubFileMirrors(rawUrl)
    }

    // ════════════════════════════════════════════════════════════
    //  预设镜像列表（基于 buildGitHubFileMirrors 动态生成）
    // ════════════════════════════════════════════════════════════

    /** proot aarch64 多源镜像（35+ 个 GitHub 代理） */
    val PROOT_MIRRORS_AARCH64: List<MirrorOption> by lazy {
        buildGitHubReleaseMirrors(
            repo = "proot-me/proot-static-build",
            tag = "v5.4.0",
            fileName = "proot_5.4.0_aarch64"
        )
    }

    /** proot armhf 多源镜像（35+ 个 GitHub 代理） */
    val PROOT_MIRRORS_ARMHF: List<MirrorOption> by lazy {
        buildGitHubReleaseMirrors(
            repo = "proot-me/proot-static-build",
            tag = "v5.4.0",
            fileName = "proot_5.4.0_armhf"
        )
    }

    // ════════════════════════════════════════════════════════════
    //  Ubuntu 24.04 rootfs 镜像站
    // ════════════════════════════════════════════════════════════

    /** Ubuntu 24.04 aarch64 rootfs 多源镜像 */
    val UBUNTU_ROOTFS_MIRRORS_AARCH64 = listOf(
        MirrorOption("ubuntu", "Ubuntu 官方", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz", "EU"),
        MirrorOption("tuna", "清华镜像", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz", "CN"),
        MirrorOption("ustc", "中科大镜像", "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz", "CN"),
        MirrorOption("aliyun", "阿里云镜像", "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz", "CN"),
    )

    /** Ubuntu 24.04 armhf rootfs 多源镜像 */
    val UBUNTU_ROOTFS_MIRRORS_ARMHF = listOf(
        MirrorOption("ubuntu", "Ubuntu 官方", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-armhf.tar.gz", "EU"),
        MirrorOption("tuna", "清华镜像", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-armhf.tar.gz", "CN"),
        MirrorOption("ustc", "中科大镜像", "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-armhf.tar.gz", "CN"),
        MirrorOption("aliyun", "阿里云镜像", "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-armhf.tar.gz", "CN"),
    )

    // ════════════════════════════════════════════════════════════
    //  核心测速方法
    // ════════════════════════════════════════════════════════════

    /**
     * 并发测试所有镜像延迟，返回按延迟排序的结果。
     * 关键改进：丢弃不可达源（latencyMs == Long.MAX_VALUE）后排序，
     * 避免一个快源 + 几十个超时源时"最优点"实际是第一个 MAX 的尴尬。
     */
    suspend fun testMirrors(
        mirrors: List<MirrorOption>,
        concurrent: Boolean = true
    ): List<MirrorTestResult> = withContext(Dispatchers.IO) {
        val results: List<MirrorTestResult> = if (concurrent && mirrors.size > 1) {
            mirrors.map { mirror ->
                async {
                    runCatching {
                        val latency = testLatency(mirror.url)
                        MirrorTestResult(mirror.key, mirror.name, mirror.url, latency, false)
                    }.getOrElse { e ->
                        MirrorTestResult(mirror.key, mirror.name, mirror.url, Long.MAX_VALUE, false, e.message)
                    }
                }
            }.awaitAll()
        } else {
            mirrors.map { mirror ->
                runCatching {
                    val latency = testLatency(mirror.url)
                    MirrorTestResult(mirror.key, mirror.name, mirror.url, latency, false)
                }.getOrElse { e ->
                    MirrorTestResult(mirror.key, mirror.name, mirror.url, Long.MAX_VALUE, false, e.message)
                }
            }
        }

        val reachable = results.filter { it.error == null && it.latencyMs < Long.MAX_VALUE }
        val unreachable = results.filter { it.error != null || it.latencyMs >= Long.MAX_VALUE }
        val bestLatency = reachable.minOfOrNull { it.latencyMs } ?: Long.MAX_VALUE
        // 可达源按延迟升序排在前，不可达源（按原顺序）排在后
        val sorted = reachable.sortedBy { it.latencyMs } + unreachable
        sorted.map { it.copy(isBest = it.latencyMs == bestLatency && it.latencyMs < Long.MAX_VALUE) }
    }

    /**
     * 按测速顺序返回有效 URL 列表（延迟升序、丢弃不可达）。
     * 用于下载时按顺序回退到下一个镜像。
     */
    fun reachableUrlsInOrder(results: List<MirrorTestResult>): List<String> =
        results.filter { it.error == null && it.latencyMs < Long.MAX_VALUE }
            .sortedBy { it.latencyMs }
            .map { it.url }

    /**
     * 同步测试单个 URL 的延迟（HEAD 请求）。
     */
    fun testLatency(url: String, timeoutMs: Int = 5000): Long {
        return try {
            val start = System.currentTimeMillis()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", "MCServerLauncher/1.0")
            conn.connect()
            conn.responseCode
            conn.disconnect()
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * 便捷方法：为给定的 GitHub URL 测试所有镜像源，返回排序结果。
     */
    suspend fun testGitHubFileMirrors(githubUrl: String): List<MirrorTestResult> {
        val mirrors = buildGitHubFileMirrors(githubUrl)
        return testMirrors(mirrors)
    }
}
