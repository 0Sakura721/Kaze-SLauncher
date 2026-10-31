package com.kaze.newage.shell

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 转交分支的生命周期安全。
 *
 * ## 背景（真机连踩两次）
 * ShellActivity.onCreate 在"主题样式是 Compose 侧"时会整壳转交给 MainActivity
 * 并**提前 return** —— 此时 `prefs/host/root/contentHost/navBar/toastLayer`
 * 六个 lateinit 全都没初始化。系统随后仍会走 onDestroy / onBackPressed：
 *  1. 第一次崩在 `host.stop()`（UninitializedPropertyAccessException）
 *  2. 补了 host 判断后，崩在 `contentHost.getChildAt(0)`
 *
 * 这类 bug 单看代码很难发现（onDestroy 里那句 detach 看起来完全合理），
 * 但在"转交"这条非常规路径上必现。所以用测试把这条路径钉死：
 * 档位设成 m3 → 构造 Activity → 立刻销毁 → 不许抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ShellTransferLifecycleTest {

    private fun setThemeMode(id: String) {
        val app = RuntimeEnvironment.getApplication() as com.kaze.newage.NewAgeApp
        app.container.uiPrefs.setThemeMode(id)
    }

    /**
     * 档位在 Compose 侧时构造再销毁，不得崩溃。
     *
     * 注意：这条测试**必须**接受"界面没建起来"这个前提 —— 它验证的正是
     * 那条提前 return 的路径。任何在 onDestroy/onBackPressed 里无条件访问
     * lateinit 的写法都会在这里炸出来。
     */
    @Test
    fun destroy_after_transfer_does_not_throw() {
        setThemeMode("m3")
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val activity = controller.get()
        // 转交后界面树不该被建起来（说明确实走了提前 return 的分支）
        // 不崩即通过：destory 会走到 onDestroy 里的那些 isInitialized 判断
        controller.pause().stop().destroy()
        assertTrue(true)
    }

    /** 转交路径下按返回键也不许崩（onBackPressed 会碰 navBar/contentHost） */
    @Test
    fun back_after_transfer_does_not_throw() {
        setThemeMode("m3")
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val activity = controller.get()
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        controller.pause().stop().destroy()
        assertTrue(true)
    }

    /** 正常路径（岚）下销毁同样不许崩 —— 这条是回归基线 */
    @Test
    fun destroy_on_shell_path_does_not_throw() {
        setThemeMode("shell")
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val activity = controller.get()
        activity.openTabForTest(2) // 控制台：collect 最多的一屏
        controller.pause().stop().destroy()
        assertTrue(true)
    }
}
