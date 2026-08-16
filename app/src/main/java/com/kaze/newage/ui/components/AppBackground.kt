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
 * 应用级状态提供者：提供 Haze 模糊状态与玻璃模糊开关。
 * 背景绘制抽成 BackdropLayer（由 AppRoot 放进单一 hazeSource 内——多个 hazeSource
 * 同 state 会互相覆盖导致底栏模糊采样不到内容，真机实测"没任何效果"）。
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
        content()
    }
}

/** 背景层：背景图（模糊+遮罩）或主题背景渐变 */
@Composable
fun BackdropLayer(prefs: SettingsPrefs, modifier: Modifier = Modifier) {
    Box(modifier) {
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
}

/** dp 扩展（避免导入冲突） */
private fun Float.dp(): androidx.compose.ui.unit.Dp =
    androidx.compose.ui.unit.Dp(this)
