package com.mcserver.launcher.server

import com.mcserver.launcher.McApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 玩家管理器 — 管理在线玩家、OP 列表、白名单、封禁列表。
 * 借鉴 MCSManager 和 Pterodactyl 面板的玩家管理设计。
 */
object PlayerManager {

    private val serverDir: File get() = ProotServerManager.serverDir(McApplication.instance)

    data class OpEntry(val name: String, val uuid: String, val level: Int = 4, val bypassesPlayerLimit: Boolean = false)
    data class WhitelistEntry(val name: String, val uuid: String)
    data class BanEntry(val name: String, val uuid: String, val reason: String = "", val created: String = "", val source: String = "", val expires: String = "forever")

    /** 读取 ops.json */
    suspend fun getOps(): List<OpEntry> = withContext(Dispatchers.IO) {
        try {
            val file = File(serverDir, "ops.json")
            if (!file.exists()) return@withContext emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                OpEntry(
                    name = obj.optString("name", ""),
                    uuid = obj.optString("uuid", ""),
                    level = obj.optInt("level", 4),
                    bypassesPlayerLimit = obj.optBoolean("bypassesPlayerLimit", false)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 添加 OP */
    suspend fun addOp(name: String, level: Int = 4): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(serverDir, "ops.json")
            val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            val newOp = JSONObject().apply {
                put("uuid", generateDummyUuid(name))
                put("name", name)
                put("level", level)
                put("bypassesPlayerLimit", false)
            }
            arr.put(newOp)
            file.writeText(arr.toString(2))
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 移除 OP */
    suspend fun removeOp(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(serverDir, "ops.json")
            if (!file.exists()) return@withContext Result.success(Unit)
            val arr = JSONArray(file.readText())
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("name", "") != name) newArr.put(obj)
            }
            file.writeText(newArr.toString(2))
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 读取白名单 */
    suspend fun getWhitelist(): List<WhitelistEntry> = withContext(Dispatchers.IO) {
        try {
            val file = File(serverDir, "whitelist.json")
            if (!file.exists()) return@withContext emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                WhitelistEntry(
                    name = obj.optString("name", ""),
                    uuid = obj.optString("uuid", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 添加白名单 */
    suspend fun addWhitelist(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(serverDir, "whitelist.json")
            val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            arr.put(JSONObject().apply {
                put("uuid", generateDummyUuid(name))
                put("name", name)
            })
            file.writeText(arr.toString(2))
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 移除白名单 */
    suspend fun removeWhitelist(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(serverDir, "whitelist.json")
            if (!file.exists()) return@withContext Result.success(Unit)
            val arr = JSONArray(file.readText())
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("name", "") != name) newArr.put(obj)
            }
            file.writeText(newArr.toString(2))
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 读取封禁列表 */
    suspend fun getBans(): List<BanEntry> = withContext(Dispatchers.IO) {
        try {
            val file = File(serverDir, "banned-players.json")
            if (!file.exists()) return@withContext emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                BanEntry(
                    name = obj.optString("name", ""),
                    uuid = obj.optString("uuid", ""),
                    reason = obj.optString("reason", ""),
                    created = obj.optString("created", ""),
                    source = obj.optString("source", ""),
                    expires = obj.optString("expires", "forever")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 添加封禁 */
    suspend fun addBan(name: String, reason: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(serverDir, "banned-players.json")
            val arr = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            arr.put(JSONObject().apply {
                put("uuid", generateDummyUuid(name))
                put("name", name)
                put("reason", reason)
                put("created", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date()))
                put("source", "MCServer Launcher")
                put("expires", "forever")
            })
            file.writeText(arr.toString(2))
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 移除封禁 */
    suspend fun removeBan(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(serverDir, "banned-players.json")
            if (!file.exists()) return@withContext Result.success(Unit)
            val arr = JSONArray(file.readText())
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("name", "") != name) newArr.put(obj)
            }
            file.writeText(newArr.toString(2))
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 生成基于用户名的伪 UUID（离线模式用，格式遵循 MC 规范） */
    private fun generateDummyUuid(name: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val hash = md.digest("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x30).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = hash.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
