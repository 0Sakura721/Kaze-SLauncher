package com.mcserver.launcher.server

/**
 * JVM 启动参数模板 — 提供业界最佳实践的 JVM flags。
 * 借鉴 Pterodactyl Egg 和 Aikar's Flags 的设计。
 */
object JvmFlags {

    data class JvmTemplate(
        val id: String,
        val name: String,
        val description: String,
        val flags: String,
        val recommendedFor: String = "通用"
    )

    /**
     * 所有预设的 JVM 参数模板
     */
    val templates: List<JvmTemplate> = listOf(
        JvmTemplate(
            id = "default",
            name = "默认 (G1GC)",
            description = "适合大多数场景的平衡配置，使用 G1 垃圾回收器",
            flags = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200",
            recommendedFor = "通用（Paper / Purpur / Spigot）"
        ),
        JvmTemplate(
            id = "aikar",
            name = "Aikar's Flags",
            description = "社区公认的最佳 JVM 参数，由 Aikar 维护，适合大型服务器",
            flags = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
                    "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch " +
                    "-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M " +
                    "-XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 " +
                    "-XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 " +
                    "-XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem " +
                    "-XX:MaxTenuringThreshold=1",
            recommendedFor = "Paper / Purpur（推荐 4GB+ 内存）"
        ),
        JvmTemplate(
            id = "lowmem",
            name = "低内存优化",
            description = "针对内存 ≤ 2GB 的设备优化，减少 GC 暂停",
            flags = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=150 " +
                    "-XX:+DisableExplicitGC -XX:G1HeapRegionSize=4M " +
                    "-XX:InitiatingHeapOccupancyPercent=30",
            recommendedFor = "内存 ≤ 2GB 的设备"
        ),
        JvmTemplate(
            id = "highperf",
            name = "高性能模式",
            description = "最大化吞吐量，适合高 TPS 需求的大型服务器",
            flags = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
                    "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch " +
                    "-XX:G1NewSizePercent=40 -XX:G1MaxNewSizePercent=50 -XX:G1HeapRegionSize=16M " +
                    "-XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 " +
                    "-XX:InitiatingHeapOccupancyPercent=15 -XX:+PerfDisableSharedMem " +
                    "-XX:MaxTenuringThreshold=1 -XX:+UseLargePages",
            recommendedFor = "高端设备（6GB+ 内存）"
        ),
        JvmTemplate(
            id = "forge",
            name = "Forge / Mod 优化",
            description = "针对 Forge / NeoForge Mod 服务器的优化参数",
            flags = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
                    "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC " +
                    "-XX:G1NewSizePercent=20 -XX:G1MaxNewSizePercent=30 " +
                    "-Dfml.readTimeout=180 -Dfml.queryResult=confirm " +
                    "-Dterminal.ansi=true",
            recommendedFor = "Forge / NeoForge（Mod 服）"
        ),
        JvmTemplate(
            id = "fabric",
            name = "Fabric 优化",
            description = "针对 Fabric 服务器的轻量优化",
            flags = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
                    "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC " +
                    "-Dterminal.ansi=true",
            recommendedFor = "Fabric"
        ),
        JvmTemplate(
            id = "custom",
            name = "自定义",
            description = "手动输入自定义 JVM 参数",
            flags = "",
            recommendedFor = "高级用户"
        )
    )

    /**
     * 根据 ID 获取模板
     */
    fun getTemplate(id: String): JvmTemplate {
        return templates.firstOrNull { it.id == id } ?: templates.first()
    }
}
