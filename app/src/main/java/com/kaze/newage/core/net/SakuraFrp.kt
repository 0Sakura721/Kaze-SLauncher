package com.kaze.newage.core.net

import com.kaze.newage.util.Downloader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 樱花映射（SakuraFrp）的一条隧道 */
@Serializable
data class SakuraTunnel(
    val id: Long = 0,
    val name: String = "",
    val node: Long = 0,
    /** tcp / udp / http / https / wol / etcp / eudp */
    val type: String = "",
    val online: Boolean = false,
    val status: Int = 0,
    val status_reason: String = "",
    val remote: String = "",
    val local_ip: String = "",
    val local_port: Int = 0,
) {
    val summary: String
        get() = buildString {
            append(type.uppercase())
            if (remote.isNotBlank()) append(" · ").append(remote)
            append(" · 本地 ").append(local_ip.ifBlank { "127.0.0.1" }).append(':').append(local_port)
            if (status_reason.isNotBlank()) append(" · ").append(status_reason)
        }
}

/** 樱花映射账号（`/user/info` 的宽松解析结果） */
data class SakuraUser(
    val name: String,
    val id: String,
    val raw: JsonObject,
)

/** frpc 客户端二进制信息（`/system/clients` 里 frpc 分类的 arm64 资产） */
data class SakuraFrpcClient(
    val version: String,
    val url: String,
    val md5: String,
    val size: Long,
)

/**
 * 樱花映射开放 API v4 客户端。
 *
 * **登录方式说明**：这套 API 只支持**访问密钥**（Bearer Token），没有用户名/密码登录接口
 * （网页登录另有 2FA/风控，官方也没开放给第三方）。所以"在软件内登录樱花映射"=
 * 让用户在樱花面板复制「访问密钥」粘进来，应用用它换取账号信息、隧道列表与**现成的 frpc 配置**
 * （`POST /tunnel/config` 直接返回可加载的 toml/ini，不需要用户手写）。
 *
 * 访问密钥只存在本机 SharedPreferences（加密），不会发往除 api.natfrp.com 以外的地址。
 */
object SakuraFrpApi {

    const val BASE = "https://api.natfrp.com/v4"

    /** 与下载到的 frpc 二进制版本保持一致（配置必须按目标 frpc 版本生成） */
    const val FRPC_VERSION = "0.51.0-sakura-15.1"

    private const val USER_AGENT = "KazeSLauncher/0.10 (Android; Minecraft server launcher)"

    /**
     * 解析用的 Json 配置。
     *
     * `coerceInputValues = true` 是这轮修的关键：樱花 `/tunnels` 会把
     * `status_reason` / `note` 这类字段**返回 null**，而数据类里它们是非空 String + 默认值；
     * 不开这个选项时 kotlinx 会抛 `Unexpected 'null' value instead of string literal`
     * —— 用户看到的就像"密钥识别不了"，其实登录已经成功了。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 校验访问密钥并取账号信息；失败抛出带可读原因的异常 */
    fun userInfo(token: String): SakuraUser {
        val body = request("/user/info", token, post = false)
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw RuntimeException("接口返回的不是 JSON：${body.take(120)}")
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
            ?: obj["username"]?.jsonPrimitive?.contentOrNull
            ?: obj["email"]?.jsonPrimitive?.contentOrNull
            ?: "已登录"
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
        return SakuraUser(name, id, obj)
    }

    /**
     * 隧道列表（含在线状态、本地端口、远程地址）。
     *
     * 先走正式反序列化；万一字段类型又变（比如某天 `online` 变成数字），
     * 退化成逐字段宽松解析 —— 宁可少一两个字段，也不能让整个登录流程失败。
     */
    fun tunnels(token: String): List<SakuraTunnel> =
        parseTunnels(request("/tunnels", token, post = false))

    /** 纯解析入口（单测用：null 字段、类型漂移都不该让登录失败） */
    internal fun parseTunnels(body: String): List<SakuraTunnel> =
        runCatching { json.decodeFromString<List<SakuraTunnel>>(body) }
            .getOrElse { lenientTunnels(body, it) }

    private fun lenientTunnels(body: String, cause: Throwable): List<SakuraTunnel> {
        val root = json.parseToJsonElement(body)
        if (root !is kotlinx.serialization.json.JsonArray) throw cause
        return root.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            SakuraTunnel(
                id = o.long("id"),
                name = o.str("name"),
                node = o.long("node"),
                type = o.str("type"),
                online = o.bool("online"),
                status = o.int("status"),
                status_reason = o.str("status_reason"),
                remote = o.str("remote"),
                local_ip = o.str("local_ip"),
                local_port = o.int("local_port"),
            )
        }
    }

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    private fun JsonObject.long(key: String): Long =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.getOrNull() ?: 0L

    private fun JsonObject.int(key: String): Int =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }.getOrNull() ?: 0

    private fun JsonObject.bool(key: String): Boolean =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull() ?: false

    /**
     * 生成可直接交给 frpc 的配置文件（toml）。
     *
     * [query] 是"启动目标"：隧道 id（如 `114514`）、节点 id（`n233`），多个用逗号分隔。
     * 配置由服务端按 [FRPC_VERSION] 生成 —— 本地下载的正是这个版本的二进制，不会对不上。
     */
    fun tunnelConfig(token: String, query: String): String {
        if (query.isBlank()) throw RuntimeException("启动目标为空")
        val form = "query=" + enc(query) + "&frpc=" + enc(FRPC_VERSION)
        return request("/tunnel/config", token, post = true, form = form)
    }

    /**
     * frpc 二进制下载信息（官方分发的 linux_arm64 版本）。
     *
     * `/system/clients` 里 `frpc` 分类的 archs 有 `linux_arm64`；
     * 这个地址在 `nya.globalslb.net`（国内可直连，不依赖 GitHub）。
     */
    fun frpcClient(): SakuraFrpcClient {
        val body = request("/system/clients?download=true", token = null, post = false)
        val root = json.parseToJsonElement(body) as JsonObject
        val frpc = root["frpc"] as? JsonObject ?: throw RuntimeException("接口里没有 frpc 分类")
        val version = frpc["ver"]?.jsonPrimitive?.contentOrNull ?: FRPC_VERSION
        val archs = frpc["archs"] as? JsonObject ?: throw RuntimeException("没有 frpc 下载资产")
        val arm64 = archs["linux_arm64"] as? JsonObject
            ?: throw RuntimeException("没有 linux_arm64 的 frpc（请反馈给樱花映射）")
        return SakuraFrpcClient(
            version = version,
            url = arm64["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            md5 = arm64["hash"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            size = arm64["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        )
    }

    /** 下载 frpc 到目标文件（校验 md5，失败重下一次） */
    fun downloadFrpc(client: SakuraFrpcClient, dest: java.io.File, onProgress: (Float, String) -> Unit): java.io.File {
        if (client.url.isBlank()) throw RuntimeException("frpc 下载地址为空")
        dest.parentFile?.mkdirs()
        val part = java.io.File(dest.absolutePath + ".part")
        onProgress(0f, "下载 frpc ${client.version}…")
        Downloader.download(
            client.url,
            part,
            onProgress = { done, total ->
                val p = if (total > 0) done.toFloat() / total else 0f
                onProgress(p, "下载 frpc ${(done / 1024 / 1024)}MB")
            },
            validate = { f ->
                f.length() > 1024 && (client.md5.isBlank() || md5Of(f)?.equals(client.md5, true) == true)
            },
        )
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        dest.setExecutable(true)
        onProgress(1f, "frpc 已就绪")
        return dest
    }

    private fun md5Of(file: java.io.File): String? = runCatching {
        java.security.MessageDigest.getInstance("MD5").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun request(path: String, token: String?, post: Boolean, form: String? = null): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, text/plain, text/toml")
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (post) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write((form ?: "").toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    val obj = json.parseToJsonElement(body) as JsonObject
                    obj["message"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw RuntimeException(
                    when (code) {
                        401, 403 -> "访问密钥无效或已过期（HTTP $code）"
                        404 -> "接口不存在（HTTP 404）"
                        else -> "樱花映射返回 HTTP $code：${message ?: body.take(160)}"
                    },
                )
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}
