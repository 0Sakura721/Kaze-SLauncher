package com.mcserver.launcher.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "首页", Icons.Filled.Dns)
    data object Console : Screen("console", "控制台", Icons.Filled.Terminal)
    data object Management : Screen("management", "管理", Icons.Filled.ManageAccounts)
    data object Settings : Screen("settings", "设置", Icons.Filled.Settings)

    // 管理子页（不直接出现在底部导航栏）
    data object ServerConfig : Screen("server_config", "服务器配置", Icons.Filled.Tune)
    data object Plugins : Screen("plugins", "插件管理", Icons.Filled.Extension)
    data object Players : Screen("players", "玩家管理", Icons.Filled.People)
    data object Files : Screen("files", "文件管理", Icons.Filled.Folder)
    data object Backups : Screen("backups", "备份恢复", Icons.Filled.Backup)
    data object CoreDownload : Screen("core_download", "下载核心", Icons.Filled.CloudDownload)
    data object Modrinth : Screen("modrinth", "Modrinth", Icons.Filled.TravelExplore)
    data object ResourcePacks : Screen("resource_packs", "资源包", Icons.Filled.Inventory)
    data object Schedules : Screen("schedules", "定时任务", Icons.Filled.Schedule)
    data object Worlds : Screen("worlds", "世界管理", Icons.Filled.Public)
    data object Diagnostics : Screen("diagnostics", "诊断报告", Icons.Filled.MonitorHeart)
    data object CrashReports : Screen("crash_reports", "崩溃报告", Icons.Filled.BugReport)
    data object Appearance : Screen("appearance", "外观", Icons.Filled.Palette)
    data object Terminal : Screen("terminal", "Linux 终端", Icons.Filled.Computer)
    data object ServerList : Screen("server_list", "服务器列表", Icons.Filled.Dns)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Console,
    Screen.Management,
    Screen.Settings
)

/** 管理子页列表，用于 ManagementScreen 内导航 */
val managementSubScreens = listOf(
    Screen.ServerList,
    Screen.ServerConfig,
    Screen.CoreDownload,
    Screen.Modrinth,
    Screen.Plugins,
    Screen.Players,
    Screen.Files,
    Screen.Backups,
    Screen.ResourcePacks,
    Screen.Schedules,
    Screen.Worlds,
    Screen.Diagnostics,
    Screen.Terminal
)
