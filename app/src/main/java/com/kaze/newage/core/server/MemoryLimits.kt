package com.kaze.newage.core.server

/**
 * 内存分配的合法范围与对齐。
 *
 * **创建向导与「实例详情 → 改内存」共用这一套规则**：以前范围（512~8192、256MB 一档、
 * 自动分配下限 1024）是散在向导 UI 里的字面量，实例建好之后就没法再改内存了 ——
 * 真机需求："创建完实例，还是可以像创建时那样编辑内存分配"。规则收到这里，
 * 两处 UI 不会再各写一套、各错一套。
 */
object MemoryLimits {
    /** 滑块范围下限 */
    const val MIN_MB = 512

    /** 滑块范围上限 */
    const val MAX_MB = 8192

    /**
     * 自动分配的下限。512MB 连原版服务端都起不来（用户反馈过"有的太小开都开不了"），
     * 所以自动建议不会低于它。
     */
    const val AUTO_MIN_MB = 1024

    /** 滑块步进：每 256MB 一档（与 `steps = 29` 对应） */
    const val STEP_MB = 256

    /** 设备可用内存 → 建议分配（一半，256MB 对齐，夹在 [AUTO_MIN_MB]..[MAX_MB]） */
    fun suggestFrom(availMb: Float): Float {
        val half = ((availMb * 0.5f) / STEP_MB).toInt() * STEP_MB.toFloat()
        return half.coerceIn(AUTO_MIN_MB.toFloat(), MAX_MB.toFloat())
    }

    /** 夹到合法范围并对齐到 256MB —— 任何来源（滑块/精确输入/旧数据）都要过这一道 */
    fun clamp(mb: Int): Int = ((mb / STEP_MB) * STEP_MB).coerceIn(MIN_MB, MAX_MB)
}
