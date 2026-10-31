package com.kaze.newage.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 壳的装配冒烟测试。
 *
 * 真的把 ShellActivity 拉起来，走完 onCreate → 建三屏 → 切页，
 * 目的只有一个：**确认这套手搭的界面不会在启动时炸**。
 * 纯渲染测试覆盖不到 Activity 与 insets、ViewModel、Dialog 主题这些接缝，
 * 而接缝恰恰是自绘 UI 最容易出错的地方。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ShellActivitySmokeTest {

    private val outDir = File("build/shell-shots").apply { mkdirs() }

    /**
     * 把主题样式档位钉在「岚」。
     *
     * ShellActivity 现在会读 AppThemeMode：选的是 Compose 侧样式（M3）时，
     * onCreate 会整壳转交后提前 return —— 那是给真机用户的正确行为，
     * 但对"要测岚本身"的测试来说，必须先把档位拨回来，否则 lateinit 全没初始化。
     *
     * **必须走 setThemeMode() 而不是直接写 SharedPreferences**：
     * SettingsPrefs 在 NewAgeApp.onCreate() 时就把 theme_mode 读进了内存里的
     * mutableStateOf，之后只写文件不会更新那个内存值，Activity 读到的还是旧档。
     * 这条踩过一次 —— 直接写 prefs 的版本看起来"设置了"，测试却仍然走转交分支。
     */
    private fun pinShellTheme() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as com.kaze.newage.NewAgeApp
        app.container.uiPrefs.setThemeMode("shell")
    }

    @Test
    fun launches_and_switches_tabs() {
        pinShellTheme()
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)

        val decor = activity.window.decorView
        assertTrue("根视图没有内容", decor.width >= 0)

        // 切到三个页签各一次：任何一屏在构造期抛异常都会在这里暴露
        listOf(1, 2, 0).forEach { tab ->
            activity.openTabForTest(tab)
            controller.pause().resume()
        }

        // 弹层主题（R.style.Shell_Sheet）能解析，说明 styles.xml 已正确编译
        val sheet = Sheet(activity, "冒烟", "弹层主题解析")
        sheet.add(activity.txt("内容", Type.body, ShellTheme.current.ink))
        sheet.buttons("确定", { sheet.dismiss() }, secondaryLabel = "取消", onSecondary = { sheet.dismiss() })
        sheet.show()
        sheet.dismiss()

        controller.pause().stop().destroy()
    }

    /** 深色主题下重建一次，验证主题解析与系统栏图标分支不会崩 */
    @Test
    fun launches_in_ink() {
        pinShellTheme()
        ShellPrefs(org.robolectric.RuntimeEnvironment.getApplication()).mode = ThemeMode.INK
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(ShellTheme.current.dark)
        activity.openTabForTest(2)
        controller.pause().stop().destroy()
        ShellPrefs(org.robolectric.RuntimeEnvironment.getApplication()).mode = ThemeMode.SYSTEM
    }

    /** 把真实的首页画出来：不是拼装的假数据，而是 Activity 自己的视图树 */
    @Test
    fun capture_home() = captureTab("activity-home", 0)

    @Test
    fun capture_console() = captureTab("activity-console", 1)

    @Test
    fun capture_settings() = captureTab("activity-settings", 2)

    private fun captureTab(name: String, tab: Int) {
        pinShellTheme()
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val activity = controller.get()
        activity.openTabForTest(tab)
        val decor: View = activity.window.decorView
        val w = decor.width.takeIf { it > 0 } ?: 822
        val h = decor.height.takeIf { it > 0 } ?: 1782
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        FileOutputStream(File(outDir, "$name.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        controller.pause().stop().destroy()
    }
}
