package com.mcserver.launcher.core.instance

import com.mcserver.launcher.data.ServerInstance

/** 服务端核心类型 */
enum class CoreType(
    val label: String,
    val desc: String,
    val urlKey: String,   // 下载目录标识
    val vanilla: Boolean, // 是否原版（决定 EULA 与下载来源）
) {
    PAPER("Paper", "高性能插件服，Spigot 分支，插件生态最佳", "paper", false),
    VANILLA("Vanilla", "Mojang 官方原版服务端", "vanilla", true),
    FABRIC("Fabric", "轻量模组服，加载器 + 模组生态", "fabric", false),
    SPIGOT("Spigot", "经典插件服（需自备 jar）", "spigot", false),
    FORGE("Forge", "重型模组服，模组生态最全", "forge", false),
    PURPUR("Purpur", "Paper 的分支，性能与配置项更多", "purpur", false),
    ;

    companion object {
        fun fromKey(key: String?): CoreType =
            entries.firstOrNull { it.urlKey == key } ?: PAPER
    }
}

/** 实例仓库：JSON 持久化 + CRUD */
object InstanceStore {

    fun list(): List<ServerInstance> = try {
        val f = com.mcserver.launcher.data.AppPaths.instancesJson()
        if (!f.exists()) emptyList()
        else decode(f.readText())
    } catch (e: Exception) {
        com.mcserver.launcher.util.KLog.e("读取实例列表失败", e)
        emptyList()
    }

    fun add(inst: ServerInstance): Boolean = try {
        val list = list().toMutableList()
        list.removeAll { it.id == inst.id }
        list.add(inst)
        save(list)
        true
    } catch (e: Exception) {
        com.mcserver.launcher.util.KLog.e("保存实例失败", e)
        false
    }

    fun remove(id: String): Boolean = try {
        save(list().filterNot { it.id == id })
        true
    } catch (e: Exception) {
        com.mcserver.launcher.util.KLog.e("删除实例失败", e)
        false
    }

    fun find(id: String): ServerInstance? = list().firstOrNull { it.id == id }

    private fun save(list: List<ServerInstance>) {
        com.mcserver.launcher.data.AppPaths.instancesJson().writeText(encode(list))
    }

    // ---------- 极简 JSON 编解码（不引入依赖，自足可控） ----------
    private fun encode(list: List<ServerInstance>): String {
        val sb = StringBuilder("{\"instances\":[")
        list.forEachIndexed { i, it ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(q(it.id))
                .append(",\"name\":").append(q(it.name))
                .append(",\"coreType\":").append(q(it.coreType.urlKey))
                .append(",\"mcVersion\":").append(q(it.mcVersion))
                .append(",\"coreFileName\":").append(q(it.coreFileName))
                .append(",\"jvmArgs\":").append(q(it.jvmArgs))
                .append(",\"agreeEula\":").append(it.agreeEula)
                .append(",\"createdAt\":").append(it.createdAt)
                .append('}')
        }
        return sb.append("]}").toString()
    }

    private fun decode(json: String): List<ServerInstance> {
        val result = mutableListOf<ServerInstance>()
        val body = json.substringAfter('[').substringBeforeLast(']')
        if (body.isBlank()) return result
        // 按对象边界切分
        var depth = 0; var start = -1
        body.forEachIndexed { i, c ->
            when (c) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        parseOne(body.substring(start, i + 1))?.let(result::add)
                    }
                }
            }
        }
        return result
    }

    private fun parseOne(obj: String): ServerInstance? = try {
        fun field(key: String): String? {
            val idx = obj.indexOf("\"$key\"")
            if (idx < 0) return null
            val colon = obj.indexOf(':', idx + key.length + 2)
            if (colon < 0) return null
            val v = obj.substring(colon + 1).trim()
            return if (v.startsWith('"')) {
                val end = obj.indexOf('"', colon + 2)
                if (end < 0) null else obj.substring(colon + 2, end)
            } else v.substringBefore(',')
        }
        ServerInstance(
            id = field("id") ?: java.util.UUID.randomUUID().toString(),
            name = field("name") ?: "服务器",
            coreType = CoreType.fromKey(field("coreType")),
            mcVersion = field("mcVersion") ?: "",
            coreFileName = field("coreFileName") ?: "",
            jvmArgs = field("jvmArgs") ?: "-Xmx2G",
            agreeEula = field("agreeEula")?.toBooleanStrictOrNull() ?: true,
            createdAt = field("createdAt")?.toLongOrNull() ?: 0L,
        )
    } catch (e: Exception) {
        com.mcserver.launcher.util.KLog.w("解析实例条目失败: ${e.message}")
        null
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}