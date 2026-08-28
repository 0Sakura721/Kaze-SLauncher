package com.kaze.newage.core.server

import com.kaze.newage.data.model.ServerInstance
import java.io.File

/**
 * server.properties 读写工具（Java Properties 格式）。
 * 支持：读取（保留注释与未知键）、按需写入、为新建实例分配空闲端口。
 */
object ServerProperties {

    private val DEFAULT_PORT = 25565

    /** 读取为有序 Map（保留原文件顺序；缺失返回空） */
    fun load(dir: File): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val file = File(dir, "server.properties")
        if (!file.exists()) return map
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (key.isNotEmpty()) map[key] = value
            }
        }
        return map
    }

    /** 写入（追加未知键）。原子写：先写临时文件再 rename——写入中断不产生半截配置文件
     *  （否则下次载入为空 → 按默认 25565 重新生成,端口可能与他人冲突） */
    @Synchronized
    fun save(dir: File, props: Map<String, String>) {
        val file = File(dir, "server.properties")
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.append("# Minecraft server properties\n")
        sb.append("# 由 Kaze SLauncher 管理（手动编辑同名文件会被覆盖）\n")
        val written = mutableSetOf<String>()
        for ((k, v) in props) {
            sb.append(k).append('=').append(v).append('\n')
            written.add(k)
        }
        // 追加原文件中未管理的键，避免丢配置
        val existing = load(dir)
        for ((k, v) in existing) {
            if (k !in written) {
                sb.append(k).append('=').append(v).append('\n')
                written.add(k)
            }
        }
        val tmp = File(dir, "server.properties.tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(file)) {
            // rename 失败（跨设备/占用等）：删旧再搬
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** 默认模板（新实例初始化用） */
    fun defaults(port: Int, motd: String): LinkedHashMap<String, String> = linkedMapOf(
        "motd" to motd,
        "server-port" to port.toString(),
        "max-players" to "20",
        "gamemode" to "survival",
        "difficulty" to "easy",
        "pvp" to "true",
        // 默认关闭正版验证（用户要求）：局域网/无正版账号场景可直接进服；实例详情页可改回
        "online-mode" to "false",
        "white-list" to "false",
        "allow-flight" to "false",
        "enable-command-block" to "false",
        "hardcore" to "false",
        // 移动端默认降低：世界准备/实体 tick 随距离² 增长，8/6 显著加快启动且省电；
        // 需更大视野可在实例详情页调回（用户核心诉求 = 启动速度）
        "view-distance" to "8",
        "simulation-distance" to "6",
        "spawn-protection" to "16",
        // 空服自动暂停默认关闭：proot 环境下暂停后唤醒会卡死（Can't keep up 数万 ms →
        // Watchdog 判崩溃强杀 → 自动重启循环，用户日志实锤 7 次）。-1 = MC 官方语义禁用。
        "pause-when-empty-seconds" to "-1",
    )

    /** 为新实例分配空闲端口（25565 起，跳过已占用的）。同步：两个实例并发创建时
     *  ensureInitial 会竞态看到同一份列表 → 撞端口；加对象锁串行化 */
    @Synchronized
    fun findFreePort(instances: List<ServerInstance>): Int {
        val used = instances.mapNotNull { inst ->
            load(inst.dir)["server-port"]?.toIntOrNull()
        }.toSet()
        var port = DEFAULT_PORT
        while (port in used) port++
        return port
    }

    /** 若实例目录无 server.properties，写入默认模板（端口自动分配，motd 用实例名）；
     *  已有文件若缺失 pause-when-empty-seconds 键则补写 -1（旧实例兼容：MC 26 无键默认 60s
     *  自动暂停，proot 下暂停唤醒会卡死 → Watchdog 崩溃循环，见记忆 2026-08-18）。
     *  用户主动设置过（键存在且 >0）则保留不覆盖。 */
    fun ensureInitial(instance: ServerInstance, allInstances: List<ServerInstance>) {
        val file = File(instance.dir, "server.properties")
        if (!file.exists()) {
            val port = findFreePort(allInstances.filter { it.id != instance.id })
            save(instance.dir, defaults(port, instance.name))
            return
        }
        val existing = load(instance.dir)
        if (existing["pause-when-empty-seconds"] == null) {
            existing["pause-when-empty-seconds"] = "-1"
            save(instance.dir, existing)
        }
    }
}
