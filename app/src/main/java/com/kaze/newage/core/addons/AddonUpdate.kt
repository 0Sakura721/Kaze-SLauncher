package com.kaze.newage.core.addons

/**
 * 附加组件（插件 / 模组）的更新判定。
 *
 * ## 和核心更新的区别
 * 核心只有一个 jar，直接比构建号。插件是**一批文件**，而且：
 *  - 有的装了好几个版本（用户手动丢了两份）；
 *  - 有的来源认不出来（不是从 Modrinth 下的）；
 *  - 有的上游已经归档、没有新版本（这时**不该**报"有更新"）；
 *  - 升级可能**降级**（当前装的是 beta，稳定版号更小）—— 这种情况要当成"有变化"而不是"更新"，
 *    让用户自己决定，绝不能自动当成新版本覆盖。
 *
 * 所以这里只做**纯判定**：给定"已装版本"与"上游可用版本列表"，算出每个文件的结论。
 * 网络与文件操作在 ViewModel 里。
 */
object AddonUpdate {

    /** 单个文件的判定结果 */
    sealed interface Verdict {
        /** 上游有更新的版本 */
        data class Update(
            val fileName: String,
            val projectId: String,
            val currentVersionId: String,
            val currentVersion: String,
            val next: ModrinthVersion,
            val nextFile: ModrinthFile,
        ) : Verdict

        /** 已是最新 */
        data class Latest(val fileName: String, val version: String) : Verdict

        /** 认不出来源（不是从 Modrinth 装的 / 被改过） */
        data class Unknown(val fileName: String, val reason: String) : Verdict

        /** 上游没有匹配当前 loader/MC 版本的包（插件已停更 / 不支持这个版本） */
        data class NoCompatible(val fileName: String, val version: String) : Verdict
    }

    /**
     * 从版本列表里挑"该升级到哪个"。
     *
     * 规则（顺序很重要）：
     * 1. 排除当前已装的版本（同 id）；
     * 2. 优先取**比当前新**的：Modrinth 的列表按发布时间**新→旧**，所以取第一个
     *    与当前不同的即可 —— 但为了不把"降级"当成更新，这里要求它确实出现在
     *    当前版本**之前**（index 更小）；
     * 3. 当前版本不在列表里（被删了 / 换过 jar）：取第一个（最新），并交由调用方
     *    在文案里说明"当前版本已不在上游列表"。
     *
     * @return 该升级到的版本；没有则 null（= 已是最新）
     */
    fun pickNext(versions: List<ModrinthVersion>, currentVersionId: String): ModrinthVersion? {
        if (versions.isEmpty()) return null
        if (currentVersionId.isBlank()) return versions.first()
        val cur = versions.indexOfFirst { it.id == currentVersionId }
        return when {
            cur == 0 -> null            // 当前就在最新
            cur > 0 -> versions.first() // 有比它更新的
            else -> versions.first()    // 当前版本已不在列表（被上游删了）→ 取最新
        }
    }

    /** 取版本里的主文件（没有 primary 就取第一个） */
    fun primaryFile(version: ModrinthVersion): ModrinthFile? =
        version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()

    /**
     * 判定一个已装文件的结论。
     *
     * @param entry 台账里的来源记录；null = 认不出来源
     * @param versions 该项目在当前 loader/MC 版本下的可用版本（已过滤）；空 = 上游无兼容版本
     */
    fun judge(
        fileName: String,
        entry: AddonRegistry.Entry?,
        versions: List<ModrinthVersion>,
    ): Verdict {
        if (entry == null) {
            return Verdict.Unknown(fileName, "认不出来源（不是从 Modrinth 装的，或被改过）")
        }
        if (versions.isEmpty()) {
            return Verdict.NoCompatible(fileName, entry.versionNumber)
        }
        val next = pickNext(versions, entry.versionId) ?: return Verdict.Latest(fileName, entry.versionNumber)
        val file = primaryFile(next)
            ?: return Verdict.NoCompatible(fileName, entry.versionNumber)
        return Verdict.Update(
            fileName = fileName,
            projectId = entry.projectId,
            currentVersionId = entry.versionId,
            currentVersion = entry.versionNumber,
            next = next,
            nextFile = file,
        )
    }

    /**
     * 把一批结论压成给界面用的摘要。
     *
     * 分开计数是必要的：用户看到"3 个可更新"和"还有 2 个认不出来源"是两件不同的事，
     * 后者需要他去重装一次才能纳入管理，混在一起会让人以为全都检查过了。
     */
    data class Summary(
        val updates: List<Verdict.Update>,
        val latest: List<Verdict.Latest>,
        val unknown: List<Verdict.Unknown>,
        val noCompatible: List<Verdict.NoCompatible>,
    ) {
        val hasAny: Boolean get() = updates.isNotEmpty()
        val total: Int get() = updates.size + latest.size + unknown.size + noCompatible.size
    }

    fun summarize(verdicts: List<Verdict>): Summary = Summary(
        updates = verdicts.filterIsInstance<Verdict.Update>(),
        latest = verdicts.filterIsInstance<Verdict.Latest>(),
        unknown = verdicts.filterIsInstance<Verdict.Unknown>(),
        noCompatible = verdicts.filterIsInstance<Verdict.NoCompatible>(),
    )
}