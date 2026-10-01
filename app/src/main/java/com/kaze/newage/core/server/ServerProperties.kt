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

    /**
     * 写入（未管理的键原样保留）。原子写：先写临时文件再 rename——写入中断不产生半截配置文件
     *  （否则下次载入为空 → 按默认 25565 重新生成,端口可能与他人冲突）
     *
     * **以原文件为骨架重写**：注释、空行、键的顺序都保留。
     * 旧实现是"从零拼一份新的"（[load] 只留键值），于是用户手写的注释、分组标题、
     * 被注释掉的备用配置在一次保存后全部消失 —— 而详情页改任意一个开关就会触发一次 save，
     * 用户下次用外部编辑器打开 server.properties 会发现批注没了。
     */
    @Synchronized
    fun save(dir: File, props: Map<String, String>) {
        val file = File(dir, "server.properties")
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        val written = mutableSetOf<String>()
        val original = runCatching {
            if (file.isFile) file.readLines() else emptyList()
        }.getOrDefault(emptyList())
        if (original.isEmpty()) {
            sb.append("# Minecraft server properties\n")
            sb.append("# 由 Kaze SLauncher 管理（手动编辑同名文件会被覆盖）\n")
        }
        for (line in original) {
            val trimmed = line.trim()
            val eq = trimmed.indexOf('=')
            val key = if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!") || eq <= 0) {
                null
            } else {
                trimmed.substring(0, eq).trim().ifEmpty { null }
            }
            val newValue = key?.let { props[it] }
            when {
                // 注释 / 空行 / 认不出的行：一字不动
                key == null -> sb.append(line).append('\n')
                newValue != null -> {
                    sb.append(key).append('=').append(newValue).append('\n')
                    written.add(key)
                }
                // 本次不改的键：保留原行（连同它周围的注释与位置）
                else -> sb.append(line).append('\n')
            }
        }
        // 追加原文件中未管理的键，避免丢配置
        val existing = load(dir)
        for ((k, v) in existing) {
            if (k !in written) {
                sb.append(k).append('=').append(v).append('\n')
                written.add(k)
            }
        }
        // props 里有、文件里没有的键（新实例首次写入就是这条路径）
        for ((k, v) in props) {
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
        // 看门狗强杀同样关掉。MC 默认 max-tick-time=60000：单 tick 超过 60 秒就判定
        // "Considering it to be crashed, server will forcibly shutdown" 并杀掉服务端。
        // 手机上这个阈值根本不够 —— 真机日志：首次进世界时 spawn 区准备 205 秒、
        // 之后 JVM 用了 3GB 堆而设备已吃掉 6GB 交换分区，一次 tick 花了 73.67 秒，
        // 于是服务端被自己的看门狗处决（Done 之后才崩，世界本身是好的）。
        // 这和上面 pause-when-empty-seconds 是同一类问题：手机上的"慢"不等于"死"。
        // -1 = MC 官方语义禁用；真卡死了用户直接点停止即可。
        "max-tick-time" to "-1",
    )

    /**
     * 为新实例分配空闲端口（25565 起，跳过已占用的）。
     *
     * 本方法自身的 `@Synchronized` **只保护"读一遍别人的配置"这一步**，不够：
     * 调用方 [ensureInitial] 是"先算空闲端口、再写进 server.properties"两步，
     * 两个实例并发创建时两边都会在对方写盘之前算出同一个空闲端口，然后各写各的 →
     * 第二个实例启动就是 `Address already in use`。合并进 [portAllocLock] 才是完整临界区。
     */
    @Synchronized
    fun findFreePort(instances: List<ServerInstance>): Int {
        val used = instances.mapNotNull { inst ->
            load(inst.dir)["server-port"]?.toIntOrNull()
        }.toSet()
        var port = DEFAULT_PORT
        while (port in used) port++
        return port
    }

    /** 端口"分配 + 写盘"的组合锁，见 [ensureInitial] */
    private val portAllocLock = Any()

    /** 若实例目录无 server.properties，写入默认模板（端口自动分配，motd 用实例名）；
     *  已有文件若缺失 pause-when-empty-seconds 键则补写 -1（旧实例兼容：MC 26 无键默认 60s
     *  自动暂停，proot 下暂停唤醒会卡死 → Watchdog 崩溃循环，见记忆 2026-08-18）。
     *  用户主动设置过（键存在且 >0）则保留不覆盖。 */
    fun ensureInitial(instance: ServerInstance, allInstances: List<ServerInstance>) {
        val file = File(instance.dir, "server.properties")
        if (!file.exists()) {
            // 「算空闲端口」与「把端口写进 server.properties」必须在同一个临界区里。
            // 只靠 findFreePort 自己的 @Synchronized 挡不住：它返回时端口还没落盘，
            // 另一个协程紧接着也会算出同一个端口。串行化之后，后进来的那次 load()
            // 能看到前一个实例刚写下的端口，于是自动往后顺延。
            synchronized(portAllocLock) {
                // 等锁期间别的协程可能已经把文件建好了（同一实例被并发 ensureInitial）
                if (!file.exists()) {
                    val port = findFreePort(allInstances.filter { it.id != instance.id })
                    save(instance.dir, defaults(port, instance.name))
                }
            }
            return
        }
        val existing = load(instance.dir)
        // 两个键的补写合并成一次 save：分开写会各触发一次文件重写与一次 FS 通知
        var patched = false
        if (existing["pause-when-empty-seconds"] == null) {
            existing["pause-when-empty-seconds"] = "-1"
            patched = true
        }
        // 旧实例（首次启动后 MC 自己生成的 server.properties）没有 max-tick-time，
        // 会回落到默认 60000 → 手机上必然被看门狗处决，所以这里也要补。
        if (existing["max-tick-time"] == null) {
            existing["max-tick-time"] = "-1"
            patched = true
        }
        if (patched) {
            save(instance.dir, existing)
        }
    }
}
