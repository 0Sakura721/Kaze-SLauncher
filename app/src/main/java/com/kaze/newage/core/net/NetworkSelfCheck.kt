package com.kaze.newage.core.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * 开服前的「网络与时间」自检。
 *
 * ## 为什么需要它
 * Forge / NeoForge 安装器要联网下载依赖，失败时抛的是 Java 原生异常。真机上最常见的
 * 两类根因（TLS 握手失败）都不是"网络不通"，而是：
 *
 * 1. **系统时间偏差过大**：TLS 证书校验依赖本机时钟，偏差超出证书有效期就握手失败；
 * 2. **代理软件接管 DNS（fake-ip）**：解析出 `198.18.x.x` 这类保留地址，
 *    TLS 实际在和代理握手，代理的中间人证书又不在 Java 8 的信任库里。
 *
 * 两者在应用里都"看起来是网络问题"，用户只会反复重试。这里把三项检查做成可读结论：
 * 时间偏差、DNS 解析结果是否正常、TLS 能否真正握手。
 *
 * 只读、无副作用：不发业务请求，只做 DNS 查询与一次 TLS 握手探测。
 */
object NetworkSelfCheck {

    /** 单项检查结果 */
    data class Item(
        val name: String,
        /** true=通过，false=有问题，null=无法判断 */
        val ok: Boolean?,
        val detail: String,
    )

    data class Report(
        val items: List<Item>,
        /** 存在明确问题时给一句总结与建议；全部正常为 null */
        val advice: String?,
    ) {
        val hasProblem: Boolean get() = items.any { it.ok == false }
    }

    /** 探测用的目标：Forge 与 Mojang 是安装时的必经域名 */
    private val HOSTS = listOf("files.minecraftforge.net", "launchermeta.mojang.com")

    /** 这些网段出现在解析结果里，说明 DNS 被代理接管（真实 DNS 不会返回） */
    private fun looksHijacked(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        val (a, b) = p[0] to p[1]
        return when {
            a == 198 && b in 18..19 -> true     // RFC 2544 基准测试段：Clash 等 fake-ip 默认池
            a == 127 -> true                    // 回环
            a == 10 -> true                     // 私网
            a == 172 && b in 16..31 -> true     // 私网
            a == 192 && b == 168 -> true        // 私网
            a == 0 -> true
            else -> false
        }
    }

    /**
     * 取服务器的当前时间（毫秒），用于估算本机时钟偏差。
     *
     * 用 HTTP 响应头的 `Date` —— 它由服务器生成、与时区无关（GMT），不需要解析额外接口。
     * 拿不到返回 null（离线、被拦截、解析失败），此时时间检查会标成「无法判断」而不是误报。
     *
     * @param url 任意可访问的 HTTPS 地址；默认用 Forge 站点（安装时的必经域名，
     *   用它也能顺带确认"这个域名到底通不通"）
     */
    fun fetchServerTimeMillis(url: String = "https://files.minecraftforge.net/"): Long? =
        runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", "KazeSLauncher/selfcheck")
                conn.responseCode
                val raw = conn.getHeaderField("Date") ?: return@runCatching null
                // HTTP 日期格式固定为 RFC 1123（GMT），用 JDK 自带解析，避免引入依赖
                val fmt = java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz",
                    java.util.Locale.US,
                )
                fmt.parse(raw)?.time
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

    /**
     * 执行自检。
     *
     * @param nowMillis 设备当前时间（毫秒），用于估算时钟偏差
     * @param httpDateMillis 从 HTTP 响应头 `Date` 解析出的服务端时间；null = 拿不到，不判定时间
     */
    fun run(nowMillis: Long, httpDateMillis: Long? = null): Report {
        val items = mutableListOf<Item>()

        // ── ① 系统时间 ──
        if (httpDateMillis != null) {
            val skewSec = (nowMillis - httpDateMillis) / 1000
            val absMin = kotlin.math.abs(skewSec) / 60
            items += if (kotlin.math.abs(skewSec) <= 300) {
                Item("系统时间", true, "与服务器相差 ${absMin} 分钟，正常")
            } else {
                Item(
                    "系统时间",
                    false,
                    "比服务器快/慢 ${absMin} 分钟 —— TLS 证书校验会失败，请在系统设置里开启「自动设置时间」",
                )
            }
        } else {
            items += Item("系统时间", null, "无法获取服务器时间，跳过比对")
        }

        // ── ② DNS 解析 ──
        val resolved = mutableListOf<Pair<String, String>>()
        var hijacked = false
        var dnsFail = false
        for (host in HOSTS) {
            val ip = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
            if (ip == null) {
                dnsFail = true
                items += Item("DNS · $host", false, "解析失败")
            } else {
                resolved += host to ip
                if (looksHijacked(ip)) hijacked = true
            }
        }
        if (resolved.isNotEmpty()) {
            val text = resolved.joinToString("\n") { (h, ip) -> "$h → $ip" }
            items += if (hijacked) {
                Item(
                    "DNS 解析",
                    false,
                    "$text\n解析到保留/私网地址，说明 DNS 被代理软件的 fake-ip 模式接管 —— " +
                        "把 *.minecraftforge.net、*.mojang.com、*.minecraft.net 设为直连",
                )
            } else {
                Item("DNS 解析", true, text)
            }
        }

        // ── ③ TLS 握手 ──
        val tlsHost = "files.minecraftforge.net"
        val tlsError = runCatching {
            // 先建一条明文连接，再用**默认** SSLSocketFactory 在其上建立 TLS：
            // 这样证书校验会真实执行（不是仅探测端口通不通），与安装器遇到的是同一类失败。
            Socket().use { raw ->
                raw.connect(InetSocketAddress(tlsHost, 443), 8000)
                raw.soTimeout = 8000
                val factory =
                    javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                factory.createSocket(raw, tlsHost, 443, true).use { s ->
                    (s as SSLSocket).startHandshake()
                }
            }
            null
        }.getOrElse { it }

        items += if (tlsError == null) {
            Item("TLS 握手", true, "$tlsHost:443 握手成功")
        } else {
            val msg = tlsError.message ?: tlsError.javaClass.simpleName
            Item(
                "TLS 握手",
                false,
                "$tlsHost:443 失败：$msg" +
                    if (msg.contains("certificate", true) || msg.contains("handshake", true)) {
                        "\n证书/握手错误通常由「系统时间不准」或「代理中间人」造成"
                    } else "",
            )
        }

        // ── 总结 ──
        val advice = when {
            items.any { it.name == "系统时间" && it.ok == false } ->
                "先校准系统时间（设置 → 日期与时间 → 自动设置），再重试安装。"
            hijacked ->
                "关闭代理的 fake-ip 模式，或把 Forge / Mojang 域名设为直连后重试。"
            dnsFail ->
                "DNS 解析失败：换网络，或把 DNS 改为 223.5.5.5 / 119.29.29.29。"
            tlsError != null ->
                "TLS 无法连通：换网络（Wi-Fi ↔ 移动数据）后重试；仍失败请检查代理设置。"
            else -> null
        }

        return Report(items, advice)
    }

    /** 把结果格式化成可复制的文本（贴进诊断日志/反馈） */
    fun format(report: Report): String = buildString {
        report.items.forEach { item ->
            val mark = when (item.ok) {
                true -> "✓"
                false -> "✗"
                null -> "?"
            }
            append(mark).append(' ').append(item.name).append("：")
                .append(item.detail.replace("\n", "\n    "))
                .append('\n')
        }
        report.advice?.let { append("\n建议：").append(it).append('\n') }
    }
}
