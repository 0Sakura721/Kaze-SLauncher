package com.kaze.newage.core.content

/**
 * 精选推荐（PCL2 / ZL2 启动器那种"推荐列表"）。
 *
 * 为什么用 slug 而不是项目 id：slug 是人能读、能在 Modrinth 网页地址里核对的东西
 * （`modrinth.com/mod/sodium`），维护推荐表时不会写错一位十六进制。
 * 运行时走一次 `/v2/projects?ids=[...]` 批量接口，**查不到的 slug 会被服务端略过**，
 * 所以某个项目改名/下架时推荐列表只是少一项，不会整块崩掉。
 *
 * 每条都带一句"为什么推荐它"：只有名字的推荐列表对新手没有意义，
 * 而这正是服务端管理员最需要的部分（该装什么、装了有什么用）。
 */
object RecommendedProjects {

    data class Entry(val slug: String, val reason: String)

    val MODS: List<Entry> = listOf(
        Entry("sodium", "渲染优化，帧数大幅提升（客户端）"),
        Entry("lithium", "服务端逻辑优化，明显降低 MSPT"),
        Entry("iris", "光影加载器，需配合 Sodium"),
        Entry("fabric-api", "绝大多数 Fabric 模组的必需前置"),
        Entry("jei", "物品与配方查询，服务器也常装"),
        Entry("modmenu", "模组列表与配置入口"),
        Entry("krypton", "网络协议栈优化，降低延迟抖动"),
        Entry("ferrite-core", "压缩方块/物品数据，省内存"),
        Entry("spark", "性能分析，`/spark tps` 看卡顿"),
        Entry("appleskin", "显示饱和度与食物效果（客户端）"),
    )

    val PLUGINS: List<Entry> = listOf(
        Entry("essentialsx", "基础指令与经济，几乎必备"),
        Entry("luckperms", "权限组管理，替代 OP 一把梭"),
        Entry("worldguard", "领地保护，防拆家"),
        Entry("worldedit", "批量建造与地形编辑"),
        Entry("placeholderapi", "变量占位符，聊天/计分板插件前置"),
        Entry("chunky", "区块预生成，减少跑图卡顿"),
        Entry("coreprotect", "方块改动记录与回滚"),
        Entry("multiverse-core", "多世界管理"),
    )

    val RESOURCEPACKS: List<Entry> = listOf(
        Entry("faithful-32x", "原版风格 32x，清晰又不花哨"),
        Entry("faithful-64x", "原版风格 64x，更细腻"),
        Entry("fresh-animations", "更流畅的动画（低配友好）"),
    )

    fun entries(kind: ContentKind): List<Entry> = when (kind) {
        ContentKind.MOD -> MODS
        ContentKind.PLUGIN -> PLUGINS
        ContentKind.RESOURCEPACK -> RESOURCEPACKS
    }

    fun slugs(kind: ContentKind): List<String> = entries(kind).map { it.slug }

    /** 推荐理由（界面展示用）；找不到返回空串 */
    fun reasonOf(kind: ContentKind, slug: String): String =
        entries(kind).firstOrNull { it.slug == slug }?.reason.orEmpty()
}
