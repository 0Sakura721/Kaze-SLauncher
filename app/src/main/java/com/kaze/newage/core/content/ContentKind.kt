package com.kaze.newage.core.content

/**
 * 内容中心里可以浏览/安装的内容类型。
 *
 * 只列**服务端真的用得上**的三类：模组、插件、资源包。
 * 光影（shader）是纯客户端资源，装在服务器上没有任何效果，所以刻意不做 ——
 * 与其摆一个装完没用的入口，不如把位置让给"服务器资源包"这种真能下发到玩家的东西。
 */
enum class ContentKind(
    /** Modrinth 的 project_type */
    val modrinthType: String,
    /** 中文标签（走 t() 翻译） */
    val label: String,
    /** 安装目录（相对实例根） */
    val dirName: String,
    /** 是否受加载器限制（模组按 Fabric/Forge 分，资源包不分） */
    val loaderAware: Boolean,
) {
    MOD("mod", "模组", "mods", true),
    PLUGIN("plugin", "插件", "plugins", true),
    RESOURCEPACK("resourcepack", "资源包", "resourcepacks", false),
    ;

    val isAddon: Boolean get() = this == MOD || this == PLUGIN
}
