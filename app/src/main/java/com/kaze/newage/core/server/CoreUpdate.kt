package com.kaze.newage.core.server

import com.kaze.newage.core.download.CoreBuild
import com.kaze.newage.data.model.CoreType

/**
 * 已建实例的**核心更新**判定。
 *
 * ## 背景
 * 实例建好之后，核心 jar 就固化了 —— 之后 Paper 出了新构建、Fabric 换了 loader，
 * 用户只能重新建一个实例再手动搬 world/config。这里补上「检查 + 升级」。
 *
 * ## 为什么需要记录构建号
 * 光比"有没有新版本"是不够的：要知道**本实例装的是哪个构建**，才能判断是不是落后了。
 * 所以 [com.kaze.newage.data.model.ServerInstance] 加了 `coreBuild` 字段
 * （旧存档没有该字段 → 视为空串 → 显示"未知"，仍可升级）。
 *
 * ## 为什么有些核心判不了
 * 各核心的元数据能力差很多：
 *  - **Paper**：有完整构建列表（`fetchPaperBuilds`），能精确比构建号；
 *  - **Fabric**：有 loader 版本列表（`fetchFabricLoaders`），能比 loader 版本；
 *  - **Purpur / Spigot**：只有"最新"一个地址（无构建列表），只能比对**下载地址**；
 *  - **Vanilla / Forge / NeoForge**：只有"最新"，且地址里带版本号，可比对地址；
 *  - **CUSTOM**（导入的 jar）：**没有上游**，不提供检查。
 *
 * 这个类只做**纯判定**（不联网），网络部分在 ViewModel 里 —— 这样判定逻辑可单测。
 */
object CoreUpdate {

    /** 检查结果 */
    sealed interface Result {
        /** 已是最新（或无法判断落后，但地址一致） */
        data class UpToDate(val current: String) : Result

        /** 有新版本可用 */
        data class Available(
            /** 用于下载的构建 id（Paper 构建号 / Fabric loader 版本）；空 = 交给 resolveDownload 取最新 */
            val buildId: String,
            /** 给用户看的版本名，如 "build 196" / "loader 0.15.11" */
            val label: String,
            /** 当前装的是什么（给用户对照） */
            val current: String,
            /** 新包的下载地址（预解析，便于直接展示大小/来源） */
            val downloadUrl: String,
            val fileName: String,
            /** 官方给的 SHA-1（有则下载后校验） */
            val sha1: String?,
        ) : Result

        /** 这个核心不支持检查（CUSTOM / 无上游） */
        data class Unsupported(val reason: String) : Result
    }

    /** 该核心是否支持"检查更新" */
    fun supports(coreType: CoreType): Boolean = coreType != CoreType.CUSTOM

    /**
     * 从构建列表里挑出"比当前新"的那一个。
     *
     * 列表顺序由各 API 决定（Paper 是新→旧，Fabric 也是），这里**不假设顺序**：
     * 只在列表里找与 [currentBuild] 不同的**第一个**，并且要求它确实在列表更前面
     * （即 index 小于当前所在位置）。当前构建不在列表里（太久远被清理 / 手动换过 jar）
     * 时，视为"有更新"，取列表第一个。
     */
    fun pickNewer(builds: List<CoreBuild>, currentBuild: String): CoreBuild? {
        if (builds.isEmpty()) return null
        if (currentBuild.isBlank()) return builds.first()
        val cur = builds.indexOfFirst { it.id.equals(currentBuild, ignoreCase = true) }
        return when {
            cur == 0 -> null          // 已经在最前面 = 最新
            cur > 0 -> builds[0]      // 列表里有它但不在首位 → 首位更新
            else -> builds.first()    // 列表里没有当前构建 → 取最新（保守：可能被清理了）
        }
    }

    /**
     * 比对"下载地址"来判断是否有更新（用于没有构建列表的核心）。
     *
     * 为什么能这么比：Purpur / Spigot / Vanilla / Forge / NeoForge 的下载地址里**带版本号**
     * （如 `paperclip/1.20.1/latest` 之外，Purpur 是 `.../1.20.1/<build>.jar`、
     * Vanilla 是 `.../1.20.1/server.jar` 且 jar 内容随版本变）。
     * 地址变化 = 上游发了新包。代价是**无法给出"当前是什么版本"**（只有地址），
     * 所以对这类核心，[Result.Available.current] 只能是"未知"。
     *
     * @param currentUrl 建实例时记下的下载地址（旧实例没有 → 传空）
     */
    fun compareByUrl(
        currentUrl: String,
        latestUrl: String,
        label: String,
        fileName: String,
        sha1: String?,
        buildId: String = "",
    ): Result {
        if (latestUrl.isBlank()) {
            return Result.Unsupported("上游没有返回下载地址")
        }
        if (currentUrl.isBlank()) {
            // 旧实例没记过地址：无法判断，但可以提示"可重新下载最新包"
            return Result.Available(
                buildId = buildId,
                label = label,
                current = "未知（旧版本未记录）",
                downloadUrl = latestUrl,
                fileName = fileName,
                sha1 = sha1,
            )
        }
        return if (currentUrl == latestUrl) {
            Result.UpToDate(current = label)
        } else {
            Result.Available(
                buildId = buildId,
                label = label,
                current = "旧构建",
                downloadUrl = latestUrl,
                fileName = fileName,
                sha1 = sha1,
            )
        }
    }
}