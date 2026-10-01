package com.kaze.newage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kaze.newage.ui.theme.M3Shape

/**
 * 内存分配滑块 —— **创建向导与「实例详情 → 改内存」共用同一个控件**。
 *
 * 真机需求："创建完实例，还是可以像创建时那样编辑内存分配"。
 * 与其在详情页再写一个"差不多"的滑块（迟早会跟向导长得不一样、范围也不一样），
 * 不如就让两边用这一个。
 */
// 带自定义 thumb 的 Slider 在 material3 里仍标着 experimental：不 OptIn 会直接编译失败
// （向导那边原来是在带 OptIn 的外层函数里用的，抽成独立组件后必须自己声明）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySliderRow(
    sliderMb: Float,
    autoMemory: Boolean,
    exceeded: Boolean,
    fmtGb: (Float) -> String,
    onMemoryMb: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (autoMemory) "自动分配（建议 ${fmtGb(sliderMb)}）" else "游戏分配",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Slider(
            value = sliderMb,
            onValueChange = { onMemoryMb(it) },
            enabled = !autoMemory,
            valueRange = 512f..8192f,
            steps = 29, // 每 256MB 一档
            thumb = {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 30.dp)
                        .clip(M3Shape.small)
                        .background(if (exceeded) scheme.error else scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        fmtGb(sliderMb),
                        color = if (exceeded) scheme.onError else scheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
        )
    }
}
