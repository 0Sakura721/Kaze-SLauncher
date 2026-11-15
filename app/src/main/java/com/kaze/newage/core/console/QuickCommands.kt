package com.kaze.newage.core.console

/**
 * 常用指令面板的数据（控制台里的「一键指令」）。
 *
 * 为什么要有：服务端控制台最常用的命令其实就那二三十条，但拼写（`gamerule keepInventory true`）
 * 和大小写很容易记错，打错一条轻则没反应、重则改错规则。把这些做成可点击的胶囊，
 * 常用操作从"回忆 + 打字"变成"点一下"。
 *
 * 分组按**使用场景**而不是按字母：管理员想的是"我要关刷怪 / 我要改时间"，
 * 不是"命令首字母是 g"。
 *
 * [QuickCommandItem.fillOnly] 用于 `say` 这种必须带文本的命令：点击只把文本填进输入框，
 * 不会直接发出去（否则会发出一条半截的广播）。
 */
data class QuickCommandItem(
    val label: String,
    val command: String,
    val fillOnly: Boolean = false,
)

data class QuickCommandGroup(
    val title: String,
    val icon: GroupIcon,
    val items: List<QuickCommandItem>,
) {
    /** 分组图标只表达"哪一类"，用枚举避免 core 层依赖 Compose 的 ImageVector */
    enum class GroupIcon { Players, Time, World, Rules, Maintenance }
}

object QuickCommands {

    val GROUPS: List<QuickCommandGroup> = listOf(
        QuickCommandGroup(
            title = "玩家与权限",
            icon = QuickCommandGroup.GroupIcon.Players,
            items = listOf(
                QuickCommandItem("在线玩家", "list"),
                QuickCommandItem("开启白名单", "whitelist on"),
                QuickCommandItem("关闭白名单", "whitelist off"),
                QuickCommandItem("重载白名单", "whitelist reload"),
                QuickCommandItem("查看封禁", "banlist"),
                QuickCommandItem("广播（自己填内容）", "say ", fillOnly = true),
            ),
        ),
        QuickCommandGroup(
            title = "时间与天气",
            icon = QuickCommandGroup.GroupIcon.Time,
            items = listOf(
                QuickCommandItem("白天", "time set day"),
                QuickCommandItem("正午", "time set noon"),
                QuickCommandItem("夜晚", "time set night"),
                QuickCommandItem("午夜", "time set midnight"),
                QuickCommandItem("晴天", "weather clear"),
                QuickCommandItem("下雨", "weather rain"),
                QuickCommandItem("雷暴", "weather thunder"),
                QuickCommandItem("冻结时间", "gamerule doDaylightCycle false"),
                QuickCommandItem("恢复时间", "gamerule doDaylightCycle true"),
            ),
        ),
        QuickCommandGroup(
            title = "世界设置",
            icon = QuickCommandGroup.GroupIcon.World,
            items = listOf(
                QuickCommandItem("设置重生点", "setworldspawn"),
                QuickCommandItem("查看种子", "seed"),
                QuickCommandItem("清理掉落物", "kill @e[type=item]"),
                QuickCommandItem("清理经验球", "kill @e[type=experience_orb]"),
                QuickCommandItem("清理船/矿车", "kill @e[type=boat]"),
            ),
        ),
        QuickCommandGroup(
            title = "游戏规则",
            icon = QuickCommandGroup.GroupIcon.Rules,
            items = listOf(
                QuickCommandItem("死亡不掉落", "gamerule keepInventory true"),
                QuickCommandItem("恢复掉落", "gamerule keepInventory false"),
                QuickCommandItem("关闭刷怪", "gamerule doMobSpawning false"),
                QuickCommandItem("恢复刷怪", "gamerule doMobSpawning true"),
                QuickCommandItem("禁止爆炸破坏", "gamerule mobGriefing false"),
                QuickCommandItem("恢复爆炸破坏", "gamerule mobGriefing true"),
                QuickCommandItem("禁止火焰蔓延", "gamerule doFireTick false"),
                QuickCommandItem("允许火焰蔓延", "gamerule doFireTick true"),
                QuickCommandItem("立即重生", "gamerule doImmediateRespawn true"),
                QuickCommandItem("默认生存", "defaultgamemode survival"),
                QuickCommandItem("默认创造", "defaultgamemode creative"),
                QuickCommandItem("难度：和平", "difficulty peaceful"),
                QuickCommandItem("难度：普通", "difficulty normal"),
                QuickCommandItem("难度：困难", "difficulty hard"),
            ),
        ),
        QuickCommandGroup(
            title = "服务器维护",
            icon = QuickCommandGroup.GroupIcon.Maintenance,
            items = listOf(
                QuickCommandItem("立即存档", "save-all"),
                QuickCommandItem("暂停自动存档", "save-off"),
                QuickCommandItem("恢复自动存档", "save-on"),
                QuickCommandItem("重载数据包/插件", "reload"),
                QuickCommandItem("TPS（Paper 系）", "tps"),
                QuickCommandItem("区块预生成", "chunky start"),
            ),
        ),
    )

    /** 面板里一共多少条（标题栏上显示"共 N 条"，让用户知道值不值得展开） */
    val totalCount: Int get() = GROUPS.sumOf { it.items.size }
}
