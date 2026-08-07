package com.mcserver.launcher.core.server

import com.mcserver.launcher.util.Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minecraft RCON 客户端(SERVERDATA 协议)。
 *
 * 服务器控制台指令的标准通道。stdin 方案在非 TTY 下不可靠
 * (JLine 控制台线程不读管道),RCON 走 TCP 端口,稳定可靠。
 *
 * 协议:每个包 = 长度(4, LE) + 请求ID(4, LE) + 类型(4, LE) + 负载 + 2 字节空。
 * 类型:3=认证, 2=命令, 0=认证响应。
 */
class RconClient(private val host: String = "127.0.0.1", private val port: Int, private val password: String) {

    private var socket: Socket? = null
    private var requestId = 0

    /** 连接并认证,成功返回 true */
    fun connect(timeoutMs: Int = 3000): Boolean {
        var s: Socket? = null
        try {
            s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 5000
            val out = DataOutputStream(s.getOutputStream())
            val input = DataInputStream(s.getInputStream())
            writePacket(out, 3, password)
            // 某些服务器在 auth 响应前会先发一个空 type=0 包(SERVERDATA_RESPONSE_VALUE)
            // 需要跳过它,直到收到 type=2(SERVERDATA_AUTH_RESPONSE)
            var attempts = 0
            while (attempts < 3) {
                val (id, type) = readPacket(input) ?: run { s.close(); socket = null; return false }
                if (type == 2) {
                    // auth 失败时 id == -1
                    if (id == -1) { s.close(); socket = null; return false }
                    break
                }
                // type == 0:空响应包,跳过继续读
                attempts++
            }
            if (attempts >= 3) { s.close(); socket = null; return false }
            socket = s
            return true
        } catch (e: Exception) {
            Logger.w("RCON connect failed: ${e.message}")
            try { s?.close() } catch (_: Exception) { }
            socket = null
            return false
        }
    }

    /** 发送命令,返回服务器响应文本(可能为空);失败返回 null */
    fun command(cmd: String): String? {
        val s = socket ?: return null
        return try {
            val out = DataOutputStream(s.getOutputStream())
            val input = DataInputStream(s.getInputStream())
            writePacket(out, 2, cmd)
            // 读取响应:可能有多个分片包,连续读取直到超时或收到空包
            val sb = StringBuilder()
            var attempts = 0
            while (attempts < 5) {
                val (_, _, payload) = readPacket(input) ?: break
                if (payload.isEmpty()) break
                sb.append(payload)
                attempts++
            }
            sb.toString()
        } catch (e: Exception) {
            Logger.w("RCON command failed: ${e.message}")
            try { s.close() } catch (_: Exception) { }
            socket = null
            null
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    private fun writePacket(out: DataOutputStream, type: Int, payload: String) {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val length = 4 + 4 + payloadBytes.size + 2
        out.writeIntLE(length)
        out.writeIntLE(requestId++)
        out.writeIntLE(type)
        out.write(payloadBytes)
        out.writeShort(0)
        out.flush()
    }

    /** 读一个包,返回 (id, type, payload);失败返回 null */
    private fun readPacket(input: DataInputStream): Triple<Int, Int, String>? {
        return try {
            val length = input.readIntLE()
            // 提高上限到 1448(RCON 标准最大单包),同时允许更大响应通过多包拼接
            if (length < 10 || length > 1448) return null
            val id = input.readIntLE()
            val type = input.readIntLE()
            val payloadBytes = ByteArray(length - 10)
            input.readFully(payloadBytes)
            input.readShort() // 尾部 2 字节空
            Triple(id, type, String(payloadBytes, Charsets.UTF_8))
        } catch (_: IOException) { null }
    }
}

private fun DataOutputStream.writeIntLE(v: Int) {
    write((v ushr 0) and 0xFF)
    write((v ushr 8) and 0xFF)
    write((v ushr 16) and 0xFF)
    write((v ushr 24) and 0xFF)
}

private fun DataInputStream.readIntLE(): Int {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (b0 == -1 || b1 == -1 || b2 == -1 || b3 == -1) throw IOException("EOF")
    return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
}
