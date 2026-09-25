package com.kaze.newage.ui.components

/**
 * 服务状态基调（由 ServerState 映射而来）。
 *
 * 原本这里还有一个「状态球」签名元素（StatusOrb：扁平圆环 / 玻璃球体）。
 * 界面重构后，这个位置由 M3 Expressive 的形状变化加载指示器接管
 * （见 [ExpressiveLoadingIndicator] / [ExpressiveLoadingGlyph]）：它同时表达
 * 状态与「活着」的感觉，而且形状来自官方形状资产，不再需要自绘一套。
 *
 * 这个枚举保留下来，因为它是「ServerState → 视觉基调」的单一映射点，
 * 屏幕、指示器与状态色都从这里取。
 */
enum class StatusTone { Running, Busy, Idle, Error }
