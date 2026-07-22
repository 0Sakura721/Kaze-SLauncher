package com.mcserver.launcher.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mcserver.launcher.utils.L
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Minecraft RCON 协议客户端。
 * 借鉴 Pterodactyl / MCSManager / AMP 等面板的 RCON 集成设计。
 *
 * RCON 协议规范：https://wiki.vg/RCON
 *
 * 数据包格式（小端序）：
 *   - Int32: 请求 ID
 *   - Int32: 类型（2=命令, 3=登录, 0=响应）
 *   - String: 负载（null-terminated）
 *   - Byte: 填充 0x00
 */
class RconClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 25575,
    private val password: String = ""
) {

    companion object {
        /** 生成一个安全的随机 RCON 密码（16 字符，字母数字） */
        fun generatePassword(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = SecureRandom()
            return (1..16).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }

        private const val TYPE_RESPONSE = 0
        private const val TYPE_COMMAND = 2
        private const val TYPE_LOGIN = 3
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var requestId = 0

    val isConnected: Boolean get() {
        val s = socket
        return s?.isConnected == true && !s.isClosed
    }

    /**
     * 连接到 RCON 服务器并认证。
     * @return true 表示连接并认证成功
     */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()

            val s = Socket(host, port).apply {
                soTimeout = 5000
            }
            socket = s
            input = DataInputStream(s.getInputStream())
            output = DataOutputStream(s.getOutputStream())

            // 发送登录请求
            requestId = 1
            sendPacket(TYPE_LOGIN, password)

            // 读取登录响应
            val response = readPacket()
            if (response == null) {
                disconnect()
                return@withContext Result.failure(Exception("RCON 认证失败：无响应"))
            }

            // 登录成功时 requestId 应匹配；失败时 requestId 为 -1
            if (response.first == -1) {
                disconnect()
                return@withContext Result.failure(Exception("RCON 认证失败：密码错误"))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(Exception("RCON 连接失败：${e.message}"))
        }
    }

    /**
     * 发送命令并获取响应。
     * @return 命令执行结果字符串，失败时返回 null
     */
    suspend fun sendCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) {
                return@withContext Result.failure(Exception("RCON 未连接"))
            }

            requestId++
            sendPacket(TYPE_COMMAND, command)

            // 读取响应（可能有多个包，收集所有响应直到遇到 requestId == currentRequestId 的响应）
            val responseParts = mutableListOf<String>()
            var lastRequestId = -1
            val targetRequestId = requestId

            // 设置较短的读取超时，避免无限等待
            socket?.soTimeout = 3000
            try {
                while (lastRequestId != targetRequestId) {
                    val response = readPacket() ?: break
                    lastRequestId = response.first
                    if (response.second.isNotEmpty()) {
                        responseParts.add(response.second)
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
                // 超时：可能服务器没有更多响应数据，使用已收集的部分
            }

            val result = responseParts.joinToString("\n").trim()
            // 恢复默认超时
            socket?.soTimeout = 5000
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(Exception("RCON 命令失败：${e.message}"))
        }
    }

    /** 断开连接 */
    fun disconnect() {
        try { input?.close() } catch (e: Exception) { L.w("RconClient", "close input failed", e) }
        try { output?.close() } catch (e: Exception) { L.w("RconClient", "close output failed", e) }
        try { socket?.close() } catch (e: Exception) { L.w("RconClient", "close socket failed", e) }
        input = null
        output = null
        socket = null
    }

    /** 发送一个 RCON 数据包 */
    private fun sendPacket(type: Int, payload: String) {
        val out = output ?: return
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val packetSize = 10 + payloadBytes.size
        val buffer = ByteBuffer.allocate(4 + packetSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(packetSize)
        buffer.putInt(requestId)
        buffer.putInt(type)
        buffer.put(payloadBytes)
        buffer.put(0)
        buffer.put(0)

        out.write(buffer.array())
        out.flush()
    }

    /** 读取一个 RCON 数据包，返回 (requestId, payload) */
    private fun readPacket(): Pair<Int, String>? {
        val inp = input ?: return null
        return try {
            val sizeBytes = ByteArray(4)
            inp.readFully(sizeBytes)
            val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int

            val data = ByteArray(size)
            inp.readFully(data)

            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val reqId = buffer.int
            val type = buffer.int

            val payloadBytes = mutableListOf<Byte>()
            while (buffer.hasRemaining()) {
                val b = buffer.get()
                if (b == 0.toByte()) break
                payloadBytes.add(b)
            }
            val payload = String(payloadBytes.toByteArray(), Charsets.UTF_8)

            reqId to payload
        } catch (e: Exception) {
            null
        }
    }
}
