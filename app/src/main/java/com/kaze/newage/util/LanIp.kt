package com.kaze.newage.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 本机网络地址工具：手机开服后，「朋友怎么连进来」是用户最先要的问题。
 *
 * ## issue #15「IP 地址好像不太好找」的两个真实原因
 * 1. **原来取"第一个非回环地址"** —— 手机同时开蜂窝数据 / VPN 时，列表里可能先出现
 *    运营商地址（`10.x` 这类 CGNAT）或 VPN 的 `tun0` 地址。那个地址给朋友是**连不上**的，
 *    而用户以为自己抄对了。所以这里按「朋友最可能连得上」的顺序排候选：
 *    私网段 + 常见内网接口（Wi-Fi / 热点 / 以太网）优先，运营商与 VPN 排最后。
 * 2. **调用方把它 `remember` 住了** —— 进页面时没连 Wi-Fi 就永远是 null（局域网那行
 *    根本不显示），之后连上了也补不出来。这里提供 [snapshot] 给界面定期重取，
 *    并且每次调用都重新探测（内部不缓存）。
 */
object LanIp {

    /** 一个候选地址及其所在接口名 */
    data class Candidate(val address: String, val iface: String)

    /**
     * 按"朋友最可能连得上"的顺序排出候选地址（每次调用重新探测，不缓存）。
     *
     * 排序依据（从前往后）：
     *  1. 私网段 + 常见内网接口名（`wlan*` / `ap*` / `swlan*` / `eth*` / `rndis*`）
     *  2. 私网段 + 其它非隧道接口
     *  3. 非私网且非运营商段（家庭宽带做了端口转发时才有意义）
     *  4. 运营商 CGNAT（`100.*`）
     *  5. VPN / 蜂窝隧道（`tun*` / `ppp*` / `rmnet*` …）
     *
     * 回环与链路本地（`169.254/16`）直接排除。
     */
    fun candidates(): List<Candidate> = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
        Collections.list(interfaces)
            .flatMap { n ->
                val ifaceName = n.name.orEmpty()
                Collections.list(n.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { a ->
                        val host = a.hostAddress ?: return@mapNotNull null
                        if (a.isLoopbackAddress) return@mapNotNull null
                        if (host.startsWith("169.254.")) return@mapNotNull null   // 链路本地
                        Candidate(host, ifaceName)
                    }
            }
            // 同一地址可能挂在多个接口上：保留排名更靠前的那个接口名
            .sortedBy { rank(it) }
            .distinctBy { it.address }
    }.getOrDefault(emptyList())

    /** 越小越优先 */
    private fun rank(c: Candidate): Int {
        val priv = isPrivate(c.address)
        val home = isHomeIface(c.iface)
        val tunnel = isTunnel(c.iface)
        val cgnat = c.address.startsWith("100.")
        return when {
            priv && home -> 0
            priv && !tunnel -> 1
            !priv && !cgnat && !tunnel -> 2
            cgnat -> 4
            tunnel -> 5
            else -> 3
        }
    }

    /**
     * 最可能被朋友连上的地址（[candidates] 的第一个）。
     *
     * 取不到（飞行模式 / 纯蜂窝 / 权限异常）返回 null —— 调用方仍应展示"没有局域网地址"
     * 的说明，而不是把整块内容藏掉（藏掉正是 issue 里"找不到 IP"的来源之一）。
     * 本机 `127.0.0.1` 地址始终可用。
     */
    fun lanIpv4(): String? = candidates().firstOrNull()?.address

    /**
     * 界面展示用：首选地址 + 其余候选。
     *
     * [primary] 为 null 表示当前没有任何可用局域网地址。
     */
    data class Snapshot(val primary: String?, val others: List<Candidate>) {
        val hasAny: Boolean get() = primary != null
    }

    fun snapshot(): Snapshot {
        val all = candidates()
        return Snapshot(all.firstOrNull()?.address, all.drop(1).take(4))
    }

    /** 常见"手机自己的 Wi-Fi / 热点 / 以太网"接口名（各厂商命名不一，用前缀匹配） */
    internal fun isHomeIface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap") ||
            n.startsWith("eth") || n.startsWith("rndis") || n.startsWith("softap")
    }

    /** VPN / 蜂窝数据口：给朋友用的地址通常不该是这些 */
    internal fun isTunnel(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("rmnet") ||
            n.startsWith("ccmni") || n.startsWith("clat") || n.startsWith("v4-")
    }

    /** RFC1918 私网段 */
    internal fun isPrivate(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val a = parts[0]
        val b = parts[1]
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}