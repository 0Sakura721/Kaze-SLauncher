package com.mcserver.launcher.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * UI 反馈中枢:统一封装 Toast 调用,消除散落各处的 Toast.makeText。
 *
 * 使用方式:
 *   val messenger = LocalUiMessenger.current
 *   messenger.toast("已保存")
 *   messenger.toastError("导入失败")
 *
 * 优势:可测试、可替换(未来接入 Snackbar 只需改一处),且减少样板代码。
 */
interface UiMessenger {
    /** 普通提示 */
    fun toast(message: String, long: Boolean = false)

    /** 错误提示(语义化,未来可用不同样式) */
    fun toastError(message: String)

    /** 成功提示 */
    fun toastSuccess(message: String)
}

val LocalUiMessenger = staticCompositionLocalOf<UiMessenger> {
    error("LocalUiMessenger not provided")
}

/** 默认基于 Android Toast 的实现 */
class ToastUiMessenger(private val context: android.content.Context) : UiMessenger {
    override fun toast(message: String, long: Boolean) {
        if (message.isBlank()) return
        Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun toastError(message: String) = toast(message, long = true)

    override fun toastSuccess(message: String) = toast(message, long = false)
}

/** 提供默认 Toast 实现的便捷组合函数 */
@Composable
fun rememberUiMessenger(): UiMessenger {
    val context = LocalContext.current
    return remember(context) { ToastUiMessenger(context) }
}
