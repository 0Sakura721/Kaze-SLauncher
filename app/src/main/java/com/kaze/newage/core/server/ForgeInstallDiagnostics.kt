package com.kaze.newage.core.server

/**
 * Forge / NeoForge 安装器失败的**原因归类**。
 *
 * ## 为什么需要它
 * 安装器的失败信息是 Java 原生异常，对手机用户毫无可操作性。以最常见的两类为例：
 *
 * ```
 * Error checking https://files.minecraftforge.net/: Remote host terminated the handshake
 * Failed to validate certificates for https://files.minecraftforge.net/
 * this typically means you have an outdated java
 * ```
 *
 * 用户照着这句话去「更新 Java」是走不通的 —— Android 上 Java 8 是随 rootfs 装的固定版本，
 * 应用并不会、也不该去换它。真实原因通常是下面两者之一（或叠加）：
 *
 * 1. **系统时间不对**：TLS 证书校验依赖本机时钟。设备时间偏差超过证书有效期范围
 *    （常见于长期不联网、或手动改过时间的设备），握手会在 ClientHello 后立刻断开，
 *    报出来的正是 `Remote host terminated the handshake`。
 * 2. **代理软件的 fake-ip / MITM**：日志里解析出的地址若是 `198.18.x.x`（RFC 2544
 *    基准测试保留段）或 `10.x`/`127.0.0.1` 之类被劫持的值，说明 DNS 被代理接管，
 *    TLS 实际握手对象是代理而非 Forge 官方；代理的中间人证书不在 Java 8 的信任库里。
 *
 * 这个对象只做**纯文本归类**（不联网、不读文件），所以可以直接单测。
 */
object ForgeInstallDiagnostics {

    /** 归因结果：原因 + 给用户的可执行建议 */
    data class Verdict(val cause: String, val advice: String)

    private val TLS_HANDSHAKE = listOf(
        "Remote host terminated the handshake",
        "Failed to validate certificates",
        "PKIX path building failed",
        "unable to find valid certification path",
        "SSLHandshakeException",
        "certificate_unknown",
        "handshake_failure",
    )

    private val DNS_FAIL = listOf(
        "UnknownHostException",
        "Name or service not known",
        "Temporary failure in name resolution",
        "No address associated with hostname",
    )

    private val TIMEOUT = listOf(
        "Read timed out",
        "connect timed out",
        "Connection timed out",
        "SocketTimeoutException",
    )

    /** RFC 2544 基准测试保留段：真实 DNS 绝不会返回，只可能是代理 fake-ip */
    private val FAKE_IP_PREFIXES = listOf("198.18.", "198.19.")

    /**
     * 从安装器输出里判断失败原因。
     *
     * @param output 安装器完整输出（stdout）
     * @param localClockSkewSec 本机时钟与服务端的偏差（秒）；null = 未知，不参与判断
     * @return 归因结果；识别不出具体原因时返回 null（调用方退回通用提示）
     */
    fun diagnose(output: String, localClockSkewSec: Long? = null): Verdict? {
        if (output.isBlank()) return null
        val lower = output.lowercase()

        // ① TLS 握手失败：再细分「时钟」还是「代理」
        if (TLS_HANDSHAKE.any { it.lowercase() in lower }) {
            // 1a. 明显的 fake-ip 痕迹 → 代理
            if (FAKE_IP_PREFIXES.any { it in output } || hasLoopbackOrPrivateResolved(output)) {
                return Verdict(
                    cause = "TLS 握手被本机代理拦截",
                    advice = "日志里解析出的地址是 198.18.x.x / 私有网段的“假地址”，" +
                        "说明 DNS 被代理软件（Clash、Surge、Quantumult 等）的 fake-ip 模式接管了，" +
                        "Forge 实际是在和代理握手，而代理的证书 Java 8 不认。\n" +
                        "  → 把代理切成「直连 / redir-host」模式，或把 *.minecraftforge.net、" +
                        "*.mojang.com、*.minecraft.net 加入直连规则后重试。",
                )
            }
            // 1b. 时钟偏差已知且很大 → 时钟
            if (localClockSkewSec != null && kotlin.math.abs(localClockSkewSec) > 300) {
                val m = kotlin.math.abs(localClockSkewSec) / 60
                return Verdict(
                    cause = "系统时间偏差 ${m} 分钟",
                    advice = "TLS 证书校验依赖本机时钟，偏差过大会让握手在第一步就失败。" +
                        "请在系统设置里开启「自动设置时间」并联网校时后重试。",
                )
            }
            // 1c. 无其他线索：仍然优先指向时钟（Android 上 Java 8 无法「升级」）
            return Verdict(
                cause = "TLS 证书校验失败",
                advice = "安装器提示“outdated java”，但 Android 上的 Java 8 是随运行环境装的固定版本，" +
                    "换 Java 走不通。请按顺序排查：\n" +
                    "  1) 系统时间是否准确（设置 → 日期与时间 → 自动设置）；\n" +
                    "  2) 是否开着代理（Clash/Surge 等），尝试关闭或把 *.minecraftforge.net、" +
                    "*.mojang.com 设为直连；\n" +
                    "  3) 换网络（Wi-Fi 与移动数据互换）后重试。",
            )
        }

        // ② DNS 解析失败
        if (DNS_FAIL.any { it.lowercase() in lower }) {
            return Verdict(
                cause = "域名解析失败",
                advice = "设备没能解析 Forge / Mojang 的域名。检查网络是否可用，" +
                    "或把 DNS 改为 223.5.5.5 / 119.29.29.29 后重试；" +
                    "开着代理时也可能出现，试一次直连。",
            )
        }

        // ③ 超时
        if (TIMEOUT.any { it.lowercase() in lower }) {
            return Verdict(
                cause = "下载依赖超时",
                advice = "网络到 Forge / Mojang 的线路太慢。换一个网络（Wi-Fi ↔ 移动数据）后重试；" +
                    "安装器是幂等的，重跑会把缺的依赖补齐。",
            )
        }

        return null
    }

    /**
     * 粗判输出里是否出现了「不该被解析到的地址」。
     *
     * Forge 安装器会打印形如 `Host: launchermeta.mojang.com [198.18.0.59]` 的解析结果。
     * 私网段（10./172.16-31./192.168.）与回环出现在这里，同样说明 DNS 被接管。
     */
    private fun hasLoopbackOrPrivateResolved(output: String): Boolean {
        // 只匹配方括号里的 IP，避免把版本号、时间等数字误判成地址
        val re = Regex("""\[(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})]""")
        return re.findAll(output).any { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@any false
            val b = m.groupValues[2].toIntOrNull() ?: return@any false
            when {
                a == 127 -> true                              // 回环
                a == 10 -> true                               // 私网
                a == 172 && b in 16..31 -> true               // 私网
                a == 192 && b == 168 -> true                  // 私网
                a == 0 -> true                                // 0.0.0.0
                else -> false
            }
        }
    }
}
