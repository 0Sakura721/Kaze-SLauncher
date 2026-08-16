package com.kaze.newage.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.kaze.newage.data.prefs.SettingsPrefs
import com.kaze.newage.ui.theme.LocalGlassBlurEnabled
import com.kaze.newage.ui.theme.LocalHazeState
import com.kaze.newage.ui.theme.ThemeBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * 应用级背景：
 *  - 默认使用当前主题的背景层（M3 平面 / GLASS 柔光）
 *  - 用户在设置中启用背景图后，背景图（模糊 + 遮罩）替代主题背景层
 *  - 背景层作为 Haze 的模糊源（安卓原生液态玻璃：卡片经 hazeEffect 采样此处做真实背景模糊）
 * 背景图方案借鉴 ZalithLauncher2（GPL-3.0），Haze 模糊方案照搬 BiliPai（GPL-3.0）。
 */
@Composable
fun AppBackground(
    prefs: SettingsPrefs,
    content: @Composable () -> Unit,
) {
    val hazeState = remember { HazeState() }
    CompositionLocalProvider(
        LocalHazeState provides hazeState,
        LocalGlassBlurEnabled provides prefs.glassBlur.value,
    ) {
        Box(Modifier.fillMaxSize()) {
            // 背景层作为 Haze 模糊源
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
            ) {
                val path = if (prefs.bgEnabled.value) prefs.backgroundImagePath() else null
                val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it) } }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(prefs.bgBlur.floatValue.dp()),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = prefs.bgOpacity.floatValue / 100f))
                    )
                } else {
                    ThemeBackdrop(Modifier.fillMaxSize())
                }
            }
            // 内容层不在此包 hazeSource（AppRoot 里只包 NavHost——绝不能把底栏自身包进模糊源，
            // 否则底栏把自己模糊进缓存形成自反馈，玻璃映射效果全无）
            content()
        }
    }
}

/** dp 扩展（避免导入冲突） */
private fun Float.dp(): androidx.compose.ui.unit.Dp =
    androidx.compose.ui.unit.Dp(this)
