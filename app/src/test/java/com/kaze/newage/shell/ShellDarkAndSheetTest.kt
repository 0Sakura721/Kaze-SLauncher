package com.kaze.newage.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * 深色主题与弹层的快照。
 *
 * 深色单独一测而不是参数化：主题是**读偏好重建**的，路径与浅色完全不同
 *（ShellPrefs.mode → ShellTheme.resolve → recreate），参数化测不到这条链。
 *
 * 弹层单独一测：它跑在独立 Window 里，主题来自 styles.xml 的 Shell_Sheet，
 * 与 Activity 的主题互不影响 —— 这是最容易漏配的地方。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ShellDarkAndSheetTest {

    private val outDir = File("build/shell-shots").apply { mkdirs() }

    /**
     * 把主题样式档位钉在「岚」。
     *
     * ShellActivity 按 AppThemeMode 决定去留：档位在 Compose 侧样式时，
     * onCreate 会整壳转交后提前 return。要测岚本身就得先把档位拨回来。
     *
     * **必须走 setThemeMode()**：SettingsPrefs 在 Application.onCreate 时已把
     * theme_mode 读进内存 mutableStateOf，直接写 SharedPreferences 不会更新它。
     */
    private fun pinShellTheme() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as com.kaze.newage.NewAgeApp
        app.container.uiPrefs.setThemeMode("shell")
    }

    private fun save(name: String, view: View) {
        // show() 会把弹层根视图置为 alpha=0 再淡入。测试环境没有帧回调，动画不会推进，
        // 于是整棵子树都是透明的，截出来只有遮罩底色。这里把透明度全部归位。
        fun reveal(v: View) {
            v.alpha = 1f
            v.translationY = 0f
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) reveal(v.getChildAt(i))
            }
        }
        reveal(view)
        val w = view.width.takeIf { it > 0 } ?: 822
        val h = view.height.takeIf { it > 0 } ?: 1782
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // 弹层是透明底的浮层：先铺一层遮罩，才能看出"压暗下层"的真实观感
        canvas.drawColor(0xFF6E6255.toInt())
        view.draw(canvas)
        FileOutputStream(File(outDir, "$name.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun ink_home() {
        val app = RuntimeEnvironment.getApplication()
        pinShellTheme()
        ShellPrefs(app).mode = ThemeMode.INK
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        controller.get().openTabForTest(0)
        save("ink-home", controller.get().window.decorView)
        controller.pause().stop().destroy()
        ShellPrefs(app).mode = ThemeMode.SYSTEM
    }

    @Test
    fun ink_console() {
        val app = RuntimeEnvironment.getApplication()
        pinShellTheme()
        ShellPrefs(app).mode = ThemeMode.INK
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        controller.get().openTabForTest(1)
        save("ink-console", controller.get().window.decorView)
        controller.pause().stop().destroy()
        ShellPrefs(app).mode = ThemeMode.SYSTEM
    }

    /** 弹层：内容 + 底部按钮，验证 styles.xml 与自绘圆角/遮罩都正确 */
    @Test
    fun sheet_paper() {
        ShellTheme.current = PaperTokens
        pinShellTheme()
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val activity = controller.get()
        val sheet = Sheet(activity, "新建服务端", "选核心与版本，应用会下载 jar 并建目录")
        sheet.label("服务端核心")
        sheet.add(activity.txt("Paper    Purpur    Spigot    Fabric", Type.micro, ShellTheme.current.ink2))
        sheet.label("游戏版本")
        sheet.add(
            activity.row().apply {
                background = activity.rounded(
                    ShellTheme.current.surfaceAlt, Dim.field,
                    ShellTheme.current.stroke, Dim.line,
                )
                setPadding(activity.dp(13f), activity.dp(11f), activity.dp(12f), activity.dp(11f))
                addView(activity.txt("目标版本", Type.body, ShellTheme.current.ink))
                addView(View(activity), android.widget.LinearLayout.LayoutParams(0, 1, 1f))
                addView(activity.mono("1.21.4", Type.body, ShellTheme.current.ink))
            },
        )
        sheet.label("内存上限")
        sheet.add(activity.mono("2048 MB", Type.title, ShellTheme.current.ink))
        sheet.add(SliderBar(activity).apply {
            min = 512f; max = 8192f; step = 256f; value = 2048f
        })
        sheet.note("设备可分配给单个实例的上限约 4096 MB。分配偏小会在生成区块时卡顿。")
        sheet.buttons("下载并创建", { }, secondaryLabel = "取消", onSecondary = { })
        sheet.show()

        val dialogView = sheet.contentRoot
        save("sheet-paper", dialogView)
        sheet.dismiss()
        controller.pause().stop().destroy()
    }

    /** 确认框：验证 content 非滚动时的排布 */
    @Test
    fun confirm_sheet() {
        ShellTheme.current = PaperTokens
        pinShellTheme()
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val activity = controller.get()
        val sheet = Sheet(activity, "删除「生存服」", scrollable = false)
        sheet.add(
            activity.txt(
                "只移除应用里的实例记录；目录 生存服 与世界存档仍在磁盘上，可以重新导入。此动作不可撤销。",
                Type.body, ShellTheme.current.ink2,
            ).apply { setLineSpacing(activity.dp(3f).toFloat(), 1f) },
        )
        sheet.buttons("删除", { }, secondaryLabel = "取消", onSecondary = { }, danger = true)
        sheet.show()
        save("sheet-confirm", sheet.contentRoot)
        sheet.dismiss()
        controller.pause().stop().destroy()
    }
}
