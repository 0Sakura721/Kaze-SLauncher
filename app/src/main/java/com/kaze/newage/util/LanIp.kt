package com.kaze.newage.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 本机网络地址工具：手机开服后，「朋友怎么连进来」是用户最先要的问题。
 */
object LanIp {

    /**
     * 本机在局域网里的 IPv4（非回环的第一个；WLAN 与热点接口都能取到）。
     * 取不到（飞行模式 / 纯蜂窝 / 权限异常）返回 null —— 调用方隐藏局域网地址相关内容即可，
     * 本机 127.0.0.1 地址始终可用。
     */
    fun lanIpv4(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        Collections.list(interfaces)
            .flatMap { n -> Collections.list(n.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
