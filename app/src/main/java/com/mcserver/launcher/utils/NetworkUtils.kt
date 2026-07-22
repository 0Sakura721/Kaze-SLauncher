package com.mcserver.launcher.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * 网络状态工具 — 检测网络连接状态、获取本地 IP。
 * 借鉴 Pterodactyl 和 MCSManager 的网络诊断功能。
 */
object NetworkUtils {

    /**
     * 网络连接状态
     */
    enum class NetworkState {
        /** 已连接（WiFi / 移动数据） */
        CONNECTED,
        /** 未连接 */
        DISCONNECTED,
        /** 正在连接 */
        CONNECTING
    }

    /**
     * 获取当前网络状态
     */
    fun getNetworkState(context: Context): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkState.DISCONNECTED
        val network = cm.activeNetwork ?: return NetworkState.DISCONNECTED
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkState.DISCONNECTED

        return when {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkState.CONNECTED
            else -> NetworkState.CONNECTING
        }
    }

    /**
     * 网络状态实时监听 Flow
     */
    fun observeNetworkState(context: Context): Flow<NetworkState> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            trySend(NetworkState.DISCONNECTED)
            close()
            return@callbackFlow
        }

        // 发送初始状态
        trySend(getNetworkState(context))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(NetworkState.CONNECTED)
            }

            override fun onLost(network: Network) {
                trySend(NetworkState.DISCONNECTED)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(getNetworkState(context))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, callback)

        awaitClose {
            cm.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    /**
     * 获取设备的局域网 IP 地址
     */
    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.flatMap { iface ->
                iface.inetAddresses.asSequence()
            }?.firstOrNull { addr ->
                !addr.isLoopbackAddress && addr is java.net.Inet4Address
            }?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 测试与指定主机端口的连通性
     */
    fun testConnectivity(host: String, port: Int, timeoutMs: Int = 3000): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取网络类型描述
     */
    fun getNetworkTypeDescription(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "未知"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "无连接"

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其他"
        }
    }

    private const val HTTP_TIMEOUT = 15000

    private fun HttpURLConnection.setup(
        method: String,
        headers: Map<String, String>
    ) {
        connectTimeout = HTTP_TIMEOUT
        readTimeout = HTTP_TIMEOUT
        requestMethod = method
        headers.forEach { setRequestProperty(it.key, it.value) }
    }

    fun httpGet(urlString: String, headers: Map<String, String> = emptyMap()): Result<String> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.setup("GET", headers)

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { reader ->
                    Result.success(reader.readText())
                }
            } else {
                Result.failure(Exception("HTTP ${conn.responseCode}: ${conn.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    fun httpGetInputStream(urlString: String, headers: Map<String, String> = emptyMap()): Result<InputStream> {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.setup("GET", headers)

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                Result.success(conn.inputStream)
            } else {
                conn.disconnect()
                Result.failure(Exception("HTTP ${conn.responseCode}: ${conn.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun httpGetWithRedirect(urlString: String, maxRedirects: Int = 5, headers: Map<String, String> = emptyMap()): Result<String> {
        var url = URL(urlString)
        var redirectCount = 0
        var conn: HttpURLConnection? = null

        return try {
            while (redirectCount < maxRedirects) {
                conn?.disconnect()
                conn = url.openConnection() as HttpURLConnection
                conn.setup("GET", headers)
                conn.instanceFollowRedirects = false

                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_SEE_OTHER
                ) {
                    val newUrl = conn.getHeaderField("Location") ?: return Result.failure(Exception("重定向缺少 Location"))
                    url = URL(newUrl)
                    redirectCount++
                } else if (code == HttpURLConnection.HTTP_OK) {
                    return conn.inputStream.bufferedReader().use { reader ->
                        Result.success(reader.readText())
                    }
                } else {
                    return Result.failure(Exception("HTTP $code"))
                }
            }
            Result.failure(Exception("重定向次数过多"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    fun httpPost(urlString: String, body: String, headers: Map<String, String> = emptyMap()): Result<String> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.setup("POST", headers)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            conn.outputStream.bufferedWriter().use { writer -> writer.write(body) }

            if (conn.responseCode == HttpURLConnection.HTTP_OK || conn.responseCode == HttpURLConnection.HTTP_CREATED) {
                conn.inputStream.bufferedReader().use { reader ->
                    Result.success(reader.readText())
                }
            } else {
                Result.failure(Exception("HTTP ${conn.responseCode}: ${conn.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}
