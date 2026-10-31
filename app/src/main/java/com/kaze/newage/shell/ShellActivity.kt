package com.kaze.newage.shell

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.AppViewModel

/**
 * 壳的根 Activity。
 *
 * 一个 Activity + 三个页签的自绘界面，不用 Fragment、不用 Navigation：
 *  - 页签切换只是把三个 View 的 visibility 换一下，没有事务、没有返回栈歧义；
 *  - Android 的返回键在这里有确定的语义：弹层 → 关弹层；二级页 → 回首页；
 *    首页 → 交给系统（退出）。
 *
 * 主题切换走"整体重建"而不是热更新：自绘 View 持有色值副本，热更新就得给
 * 每个 View 写一遍 refresh()，漏一个就会出现"半深半浅"的界面。重建一次
 * 只要几十毫秒，代价远小于维护成本。
 */
class ShellActivity : ComponentActivity() {

    private val vm: AppViewModel by lazy {
        ViewModelProvider(this)[AppViewModel::class.java]
    }

    private lateinit var prefs: ShellPrefs
    private lateinit var host: ShellHost

    private lateinit var root: FrameLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var navBar: ShellNavBar
    private lateinit var toastLayer: FrameLayout

    private var home: HomeScreen? = null
    private var serverList: ServerListScreen? = null
    private var console: ConsoleScreen? = null
    private var settings: SettingsScreen? = null

    private var tab = 0
    private var toastView: TextView? = null

    /**
     * 导入 jar 的文件选择器。
     *
     * 必须注册在 Activity 上（registerForActivityResult 要求生命周期所有者），
     * 而"导入"按钮在 ServerListScreen 里 —— 所以走 ShellHost 的回调注入，
     * 屏不直接持有 Activity。
     */
    private val pickJarLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // 读权限只在本次会话内有效：实例 jar 是复制进来的（importJar 会拷贝到
        // 实例目录），所以不需要 takePersistableUriPermission。但复制是在
        // ViewModel 的协程里做的，那时本 Activity 可能已销毁 —— 这也正是
        // importJar 自己负责拷贝、而不是让实例直接引用 uri 的原因。
        vm.importJar(
            uri = uri,
            name = "导入-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date()),
            memoryMb = 1024,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 入口一致性的另一半（见 MainActivity.onCreate）：主题样式在 Compose 侧
        // （M3）时，桌面图标 / 通知仍可能直接拉起本 Activity，那时应当把界面
        // 交给 Compose 版本，而不是"点 A 看到 B"。判断依据与设置页同一个：
        // uiPrefs.themeMode（AppThemeMode），不是另设的开关。
        val mode = com.kaze.newage.ui.theme.AppThemeMode.fromId(
            (applicationContext as com.kaze.newage.NewAgeApp).container.uiPrefs.themeMode.value,
        )
        if (!mode.isViewShell) {
            ShellSwitch.restart(this, ShellSwitch.M3)
            return
        }

        prefs = ShellPrefs(this)
        ShellTheme.current = ShellTheme.resolve(prefs.mode, isSystemDark())
        host = ShellHost(this, vm, prefs)

        // 先铺界面再改系统栏：`window.insetsController` 内部直接读 mDecor，
        // 在 setContentView 之前调用会拿到 null 并抛 NPE（真机同样会崩，
        // 不是测试环境的问题 —— 本仓的冒烟测试正是这么抓到的）。
        buildLayout()
        applyEdgeToEdge()
        host.onInstances = { list ->
            home?.renderInstances(list)
            serverList?.renderInstances(list)
        }
        host.onStates = { map ->
            home?.renderStates(map)
            serverList?.renderStates(map)
            console?.renderStates(map)
        }
        host.onToast = { msg, error -> showToast(msg, error) }
        host.onThemeChanged = { recreate() }
        host.openConsoleHook = { instance -> openConsole(instance) }
        host.onPickJar = { pickJarLauncher.launch(arrayOf("application/java-archive", "application/zip", "*/*")) }
        host.onCreateRequested = { showCreateSheet() }
        host.start()

        selectTab(if (savedInstanceState != null) savedInstanceState.getInt(KEY_TAB, 0) else 0)
    }

    /**
     * 新建实例向导。
     *
     * 首页与服务端页都要能打开它，但向导是 private 的弹层实现 —— 所以由
     * Activity 提供一个统一入口，两边都走这里，避免两处各建一份导致行为分叉。
     */
    private fun showCreateSheet() {
        if (!vm.env.isReady) {
            val sheet = Sheet(this, "先部署环境", "服务端跑在 proot 提供的 Linux 里", scrollable = false)
            sheet.add(
                txt(
                    "第一次使用需要下载 Linux 运行时（约 60–200 MB，视架构与网络）。" +
                        "中途退出会保留断点，下次接着下。",
                    Type.body, ShellTheme.current.ink2,
                ).apply { setLineSpacing(dp(3f).toFloat(), 1f) },
            )
            sheet.buttons("开始部署", {
                sheet.dismiss()
                host.toast("开始部署环境…")
                host.setupEnvironment { host.toast("环境就绪，可以新建实例了") }
            }, secondaryLabel = "稍后", onSecondary = { sheet.dismiss() })
            sheet.show()
            return
        }
        CreateWizard(this, host).show()
    }

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * 边到边 + 系统栏图标明暗跟随**应用内**主题。
     *
     * 系统栏图标的默认行为是跟随 `isSystemInDarkTheme`：应用内手动切浅色而系统是深色时，
     * 状态栏图标会画成白色、压在浅色背景上，整条状态栏看起来"消失"了。
     */
    private fun applyEdgeToEdge() {
        // WindowCompat 会把 API 30+ 映射到 setDecorFitsSystemWindows，29 及以下
        // 改写成 SYSTEM_UI_FLAG_LAYOUT_* —— 直接调 window.setDecorFitsSystemWindows
        // 会在 Android 10 及更早的机器上抛 NoSuchMethodError。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 系统栏透明 + 取消强制对比度。API 35 起这些 setter 被标记 deprecated
        //（改由 enableEdgeToEdge 统一处理），但本壳的 minSdk 27 还得靠它们，
        // 因此显式压制警告而不是删掉。
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }
        val light = !ShellTheme.current.dark
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                setSystemBarsAppearance(
                    if (light) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                )
                setSystemBarsAppearance(
                    if (light) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                )
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) or
                (if (light) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0) or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun buildLayout() {
        val t = ShellTheme.current
        root = FrameLayout(this)
        root.setBackgroundColor(t.bg)

        // 背板：铺满整屏（含状态栏后面），保证系统栏透明时露出的也是纸面
        root.addView(PaperBackdrop(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        contentHost = FrameLayout(this)
        root.addView(contentHost, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        navBar = ShellNavBar(this).apply {
            tabs = listOf(
                ShellNavBar.Tab("home", "实例"),
                ShellNavBar.Tab("server", "服务端"),
                ShellNavBar.Tab("console", "控制台"),
                ShellNavBar.Tab("settings", "设置"),
            )
            onSelect = { index -> selectTab(index) }
            background = rounded(t.surface, 0f, t.stroke, Dim.line)
        }
        root.addView(navBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(58f),
            Gravity.BOTTOM,
        ))

        toastLayer = FrameLayout(this).apply { isClickable = false }
        root.addView(toastLayer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        setContentView(root)
        applyInsets()
    }

    /** 状态栏 / 导航栏 / 键盘三段避让：内容区让状态栏，底部条让导航栏，键盘让正文 */
    private fun applyInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, navBottom, ime) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                Triple(bars.top, bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
            } else {
                @Suppress("DEPRECATION")
                Triple(insets.systemWindowInsetTop, insets.systemWindowInsetBottom, 0)
            }

            // 底部条自己让开导航栏
            val navBarHeight = dp(58f) + navBottom
            navBar.setPadding(dp(8f), dp(5f), dp(8f), dp(5f) + navBottom)
            navBar.layoutParams = (navBar.layoutParams as FrameLayout.LayoutParams).apply {
                height = navBarHeight
            }

            // 内容区的**整个矩形**都要停在底部条之上，而不是只让内容底部留白：
            // 留白只能救可滚内容，救不了贴着屏底的固定控件（例如控制台的命令输入行），
            // 那类控件会被底部条直接盖住。
            contentHost.layoutParams = (contentHost.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = navBarHeight
            }

            // 屏内只需让状态栏；键盘弹出时再让出键盘高度，让输入行浮在键盘上方
            (contentHost.getChildAt(0) as? ScreenRoot)?.setSystemInsets(top, ime, navBarHeight)
            insets
        }
        root.requestApplyInsets()
    }

    private fun selectTab(index: Int) {
        tab = index
        navBar.selected = index
        contentHost.removeAllViews()
        val screen: ScreenRoot = when (index) {
            TAB_SERVER -> (serverList ?: ServerListScreen(host).also { serverList = it }).also { it.attach() }
            TAB_CONSOLE -> (console ?: ConsoleScreen(host).also { console = it }).also { it.attach() }
            TAB_SETTINGS -> (settings ?: SettingsScreen(host).also { settings = it }).also { it.attach() }
            else -> (home ?: HomeScreen(host).also { home = it }).also { it.attach() }
        }
        contentHost.addView(screen, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        // 切页时把已缓存的状态立即回填：新构造的屏没有历史回调可等
        home?.renderInstances(vm.instances.value)
        home?.renderStates(vm.serverStates.value)
        serverList?.renderInstances(vm.instances.value)
        serverList?.renderStates(vm.serverStates.value)
        console?.renderStates(vm.serverStates.value)
        root.requestApplyInsets()
    }

    fun openConsole(instance: ServerInstance) {
        host.select(instance)
        console?.let { it.detach() }
        console = null
        // 控制台现在是第 3 个页签（实例 / 服务端 / 控制台 / 设置）。
        // 用常量而不是字面量：加页签时这类下标最容易漏改，漏了的表现是
        // "点开控制台却跳到服务端页"，而且不会报错。
        selectTab(TAB_CONSOLE)
    }

    /**
     * 切页的测试入口。
     *
     * 不把它做成 public API 的"正式功能"，但也不藏起来：自绘界面没有 Compose
     * 的测试规则可用，能直接驱动 Activity 走一遍三屏装配，是最省事也最真实的冒烟。
     */
    fun openTabForTest(index: Int) = selectTab(index)

    @Deprecated("Override of a deprecated platform API; behaviour is intentional here")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 转交分支下界面树根本没建（见 onCreate），此时任何切页都会碰未初始化的
        // navBar/contentHost。直接交给系统，让这次返回正常结束 Activity。
        if (!::navBar.isInitialized) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
        // 二级页（弹层由 Dialog 自己消费返回键）→ 回首页；已在首页 → 交给系统
        if (tab != 0) {
            selectTab(0)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onDestroy() {
        // host 是 lateinit：onCreate 里"转交 Compose"的那条分支会提前 return，
        // 什么都没初始化就结束了。此时系统仍会走 onDestroy，无条件访问就是崩溃
        //（真机实测：切到 Material 3 后按返回，进程直接 FATAL）。
        // 用 ::host.isInitialized 判断，而不是加一个布尔标志 —— 标志会和
        // 真实的初始化状态脱节，lateinit 自己就知道。
        if (::host.isInitialized) host.stop()
        super.onDestroy()
    }

    private fun showToast(message: String, error: Boolean) {
        val c = this
        toastView?.let { toastLayer.removeView(it) }
        val tone = if (error) ShellTheme.current.danger else ShellTheme.current.ink
        val view = c.txt(message, Type.micro, tone, medium = true).apply {
            setLineSpacing(c.dp(2f).toFloat(), 1f)
            setPadding(c.dp(14f), c.dp(10f), c.dp(14f), c.dp(10f))
            background = c.rounded(
                ShellTheme.current.surface,
                10f,
                if (error) ShellTheme.current.danger.alpha(0.45f) else ShellTheme.current.strokeStrong,
                Dim.line,
            )
            maxLines = 3
        }
        toastLayer.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply {
            bottomMargin = dp(78f)
            marginStart = dp(20f)
            marginEnd = dp(20f)
        })
        toastView = view
        view.alpha = 0f
        view.translationY = dp(10f).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(150L).withEndAction {
            view.postDelayed({
                view.animate().alpha(0f).setDuration(180L).withEndAction {
                    toastLayer.removeView(view)
                    if (toastView === view) toastView = null
                }.start()
            }, 2200L)
        }.start()
    }

    private fun dp(v: Float): Int = (resources.displayMetrics.density * v + 0.5f).toInt()

    companion object {
        private const val KEY_TAB = "shell_tab"

        /** 页签下标。与 navBar.tabs 的顺序必须一致，改动时两处一起改。 */
        private const val TAB_HOME = 0
        private const val TAB_SERVER = 1
        private const val TAB_CONSOLE = 2
        private const val TAB_SETTINGS = 3
    }
}

/**
 * 屏的公共骨架。
 *
 * 每个屏都是「固定头 + 可滚正文」，因此把这段结构收进基类：
 *  - [header] 不吃滚动，滚动时标题一直在（长列表里最需要它）；
 *  - [body] 是 LinearLayout，屏自己往里塞内容；
 *  - 系统栏内边距由 Activity 统一下发，屏不关心自己是不是被键盘顶起来了。
 */
abstract class ScreenRoot(
    context: android.content.Context,
    /** 是否把内容放进滚动区。控制台必须为 false：它的日志区要按剩余高度撑满，不能靠滚动。 */
    scrollable: Boolean = true,
) : LinearLayout(context) {

    protected val head: LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(Dim.gutter), dp(10f), dp(Dim.gutter), dp(8f))
    }

    /** 不滚动时的内容容器（与 [body] 二选一由 [scrollable] 决定） */
    protected val frameBody: LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, 0, 0, dp(6f))
    }

    protected val scroller = android.widget.ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    protected val body: LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, 0, 0, dp(20f))
    }

    /** 贴在屏幕底部的固定区（控制台的命令输入）。为 null 时本屏没有底栏。 */
    private var dock: LinearLayout? = null

    init {
        orientation = VERTICAL
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (scrollable) {
            scroller.addView(body, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            // 占满剩余高度：内部靠 weight 分配，任何一块超出都有明确的责任方，
            // 不会像滚动区那样"多出来的部分被静默裁掉"。
            addView(frameBody, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /**
     * 安装底栏。
     *
     * 底栏必须活在滚动区之外 —— 键盘弹出、日志滚到底时它都得留在原位。
     * 导航栏留白只加在这里：加到正文尾部会让所有列表凭空多出一段空白。
     */
    protected fun installDock(view: LinearLayout) {
        dock = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** 由 Activity 在 insets 回调里调：top = 状态栏；keyboard = 键盘；navBar = 已被内容宿主让开的底部条高度 */
    fun setSystemInsets(top: Int, keyboard: Int, navBar: Int) {
        head.setPadding(dp(Dim.gutter), dp(10f) + top, dp(Dim.gutter), dp(8f))

        // 底栏：静止时不再额外加导航栏（宿主矩形已经让开了），键盘弹出时让出键盘
        dock?.setPadding(
            dp(Dim.gutter), dp(8f), dp(Dim.gutter), dp(9f) + keyboard.coerceAtLeast(0),
        )
        // 键盘遮住的是整个窗口底部，底部条也在后面 —— 此时内容区还得自己再让一次，
        // 否则输入行会浮到键盘下面看不见。
        if (keyboard > navBar) {
            dock?.setPadding(
                dp(Dim.gutter), dp(8f), dp(Dim.gutter), dp(9f) + (keyboard - navBar),
            )
        }
        scroller.setPadding(0, 0, 0, 0)
    }

    /** 每次切入本页时调用（用于刷新依赖"当前实例"的内容） */
    open fun attach() {}
    open fun detach() {}

    protected fun dp(v: Float): Int = (resources.displayMetrics.density * v + 0.5f).toInt()
}

/** 标题区：大标题 + 副标题 + 可选右侧动作 */
fun android.content.Context.screenTitle(
    title: String,
    subtitle: String? = null,
    action: View? = null,
): LinearLayout {
    val c = this
    return c.row().apply {
        val texts = c.col().apply {
            addView(c.txt(title, Type.display, ShellTheme.current.ink, medium = true))
            if (subtitle != null) {
                addView(
                    c.txt(subtitle, Type.micro, ShellTheme.current.ink3).apply {
                        setPadding(0, c.dp(4f), 0, 0)
                    },
                )
            }
        }
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) addView(action)
    }
}
