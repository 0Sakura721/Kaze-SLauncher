package com.kaze.newage.shell

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

// ══════════════════════════════════════════════════════════════════════════
// 岚 · 设计令牌
//
// 两套配色（素纸 / 墨夜）共用同一组语义槽位，界面代码只认槽位、不认色值，
// 因此换肤不需要动任何一屏的布局代码。
//
// 视觉基调：纸感中性底 + 陶土红强调色 + 1px 硬描边 + 极轻圆角。
// 与「玻璃拟态」相反——这里不追求半透明叠加，追求的是**边界清楚**：
// 每块内容都有可见的轮廓线，层级靠色阶而非阴影，因此在低端机与深色下都稳。
// ══════════════════════════════════════════════════════════════════════════

/** 主题模式：素纸 / 墨夜 / 跟随系统 */
enum class ThemeMode(val key: String, val label: String) {
    PAPER("paper", "素纸"),
    INK("ink", "墨夜"),
    SYSTEM("system", "跟随系统"),
    ;

    companion object {
        fun of(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * 一整套语义色。
 *
 * 命名不用 red/blue 这类色相词，而用**用途**：danger、good、warn。
 * 深色下同一个槽位往往要换色相（陶土红在纸底上够重，在墨底上就得提亮），
 * 这样界面代码写 `T.danger` 就永远是对的。
 */
data class Tokens(
    val dark: Boolean,
    /** 页面最底层 */
    val bg: Int,
    /** 页面底层的暗部（自绘渐变用） */
    val bgDeep: Int,
    /** 卡片面 */
    val surface: Int,
    /** 卡片内的次级面（行内小块、输入框底） */
    val surfaceAlt: Int,
    /** 凹陷面（分段控件槽、进度槽） */
    val surfaceSunken: Int,
    /** 常规描边 */
    val stroke: Int,
    /** 供强调元素用的重描边 */
    val strokeStrong: Int,
    /** 行分隔线，比 stroke 更淡 */
    val hairline: Int,
    val ink: Int,
    val ink2: Int,
    val ink3: Int,
    val accent: Int,
    val accentDeep: Int,
    /** 强调色的极淡底（选中态、标签底） */
    val accentSoft: Int,
    /** 画在 accent 上的字色 */
    val onAccent: Int,
    val good: Int,
    val warn: Int,
    val danger: Int,
    val info: Int,
    /** 弹层遮罩 */
    val scrim: Int,
)

val PaperTokens = Tokens(
    dark = false,
    bg = 0xFFF4F1EC.toInt(),
    bgDeep = 0xFFE7E1D7.toInt(),
    surface = 0xFFFFFFFF.toInt(),
    surfaceAlt = 0xFFF8F5F0.toInt(),
    surfaceSunken = 0xFFEDE8DF.toInt(),
    stroke = 0xFFE1DACF.toInt(),
    strokeStrong = 0xFFCBC1B2.toInt(),
    hairline = 0xFFECE6DC.toInt(),
    ink = 0xFF191714.toInt(),
    ink2 = 0xFF54504A.toInt(),
    ink3 = 0xFF8A8277.toInt(),
    // 黛蓝：纸色底上最稳的强调色，且与朱红（危险）在色相上彻底分开。
    // 之前用陶土红时，强调色与错误色几乎同色，"删除"和"主按钮"看起来一样重。
    accent = 0xFF2C4A63.toInt(),
    accentDeep = 0xFF1D3547.toInt(),
    accentSoft = 0xFFE6EDF3.toInt(),
    onAccent = 0xFFFDFEFF.toInt(),
    good = 0xFF2F6B48.toInt(),
    warn = 0xFF9A6A14.toInt(),
    danger = 0xFFA83524.toInt(),
    info = 0xFF3B6E8F.toInt(),
    scrim = 0x33000000,
)

val InkTokens = Tokens(
    dark = true,
    bg = 0xFF0E1114.toInt(),
    bgDeep = 0xFF06080A.toInt(),
    surface = 0xFF171B1F.toInt(),
    surfaceAlt = 0xFF1E2327.toInt(),
    surfaceSunken = 0xFF111518.toInt(),
    stroke = 0xFF2A3136.toInt(),
    strokeStrong = 0xFF3E464C.toInt(),
    hairline = 0xFF232A2F.toInt(),
    ink = 0xFFF2EEE7.toInt(),
    ink2 = 0xFFB2ACA2.toInt(),
    ink3 = 0xFF79838B.toInt(),
    // 琥珀金：深底上最醒目的暖色，也和 danger 的红分得开
    accent = 0xFFE7AE4C.toInt(),
    accentDeep = 0xFFC7912F.toInt(),
    accentSoft = 0xFF2B2317.toInt(),
    onAccent = 0xFF16181A.toInt(),
    good = 0xFF62C08D.toInt(),
    warn = 0xFFD9A54B.toInt(),
    danger = 0xFFE46A4F.toInt(),
    info = 0xFF6FA8D6.toInt(),
    scrim = 0x99000000.toInt(),
)

/** 当前生效的令牌。单 Activity 单窗口，切主题时整体重建内容，因此允许全局可变。 */
object ShellTheme {
    @Volatile
    var current: Tokens = PaperTokens

    fun resolve(mode: ThemeMode, systemDark: Boolean): Tokens = when (mode) {
        ThemeMode.PAPER -> PaperTokens
        ThemeMode.INK -> InkTokens
        ThemeMode.SYSTEM -> if (systemDark) InkTokens else PaperTokens
    }
}

/** 度量：一处改，全壳跟着变 */
object Dim {
    /** 屏幕左右留白 */
    const val gutter = 18f

    /** 卡片圆角 */
    const val card = 16f

    /** 弹层顶部圆角 */
    const val sheet = 22f

    /** 输入框 / 小按钮圆角 */
    const val field = 10f

    /** 标签胶囊圆角 */
    const val pill = 40f

    /** 卡片内边距 */
    const val cardPad = 16f

    /** 元素间距 */
    const val gap = 12f

    /** 行高 */
    const val row = 52f

    /** 主按钮高度 */
    const val button = 46f

    /** 常规描边宽度（px 级细线，靠 dp 换算成 1px 观感） */
    const val line = 0.8f
}

/** 字号阶梯（sp） */
object Type {
    const val display = 26f
    const val title = 18f
    const val subtitle = 14f
    const val body = 13.5f
    const val label = 12f
    const val micro = 10.5f
    const val mono = 12f
}

// ── Context 扩展：所有自绘控件都从这里取尺寸与样式 ─────────────────────

fun Context.dp(value: Float): Int = (resources.displayMetrics.density * value + 0.5f).toInt()

fun Context.dp(value: Int): Int = dp(value.toFloat())

/**
 * 造字。**永远关掉 includeFontPadding**：Android 默认给每行加 2dp 上、2dp 下的
 * 字体留白，一排 12sp 的标签看起来会比设计稿高出一圈，纵向节奏全乱。
 */
fun Context.txt(
    value: CharSequence,
    size: Float = Type.body,
    color: Int = ShellTheme.current.ink,
    medium: Boolean = false,
    letterSpacing: Float = 0f,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    includeFontPadding = false
    if (letterSpacing != 0f) this.letterSpacing = letterSpacing
    typeface = Typeface.create(
        if (medium) "sans-serif-medium" else "sans-serif",
        Typeface.NORMAL,
    )
}

/** 等宽：端口、内存、时长这类数字必须等宽，否则每帧都在左右抖 */
fun Context.mono(
    value: CharSequence,
    size: Float = Type.mono,
    color: Int = ShellTheme.current.ink2,
    medium: Boolean = false,
): TextView =
    txt(value, size, color, medium = medium).apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        letterSpacing = 0f
    }

/** 全大写小标签：分区标题用，带字距才有"标牌"感 */
fun Context.micro(value: CharSequence, color: Int = ShellTheme.current.ink3): TextView =
    txt(value, Type.micro, color, medium = true, letterSpacing = 0.12f)

fun Context.rounded(
    fill: Int,
    radius: Float,
    stroke: Int = Color.TRANSPARENT,
    strokeWidth: Float = 0f,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(fill)
    cornerRadius = dp(radius).toFloat()
    if (strokeWidth > 0f) setStroke(dp(strokeWidth), stroke)
}

/** 只有上面两个角是圆的（底部弹层用） */
fun Context.roundedTop(
    fill: Int,
    radius: Float,
    stroke: Int = Color.TRANSPARENT,
    strokeWidth: Float = 0f,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(fill)
    val r = dp(radius).toFloat()
    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    if (strokeWidth > 0f) setStroke(dp(strokeWidth), stroke)
}

fun Context.gradient(
    colors: IntArray,
    radius: Float,
    orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TOP_BOTTOM,
): GradientDrawable = GradientDrawable(orientation, colors).apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(radius).toFloat()
}

/**
 * 圆角 + 涟漪。
 *
 * 第三个参数是 **mask**，传 `null` 表示不裁剪 —— 涟漪会按 View 的矩形 bounds
 * 绘制，于是胶囊按钮（radius=40）按下时，圆角之外的四角会冒出一层方角色块，
 * 压在父容器底色上，半径越大越明显。传一个同形状的纯色 drawable 当 mask
 * （只取它的 alpha 通道，颜色无所谓）就能让涟漪跟着圆角走。
 */
fun Context.rip(
    fill: Int,
    radius: Float,
    rippleColor: Int = if (ShellTheme.current.dark) 0x22FFFFFF else 0x14000000,
    stroke: Int = Color.TRANSPARENT,
    strokeWidth: Float = 0f,
): RippleDrawable = RippleDrawable(
    ColorStateList.valueOf(rippleColor),
    rounded(fill, radius, stroke, strokeWidth),
    rounded(Color.WHITE, radius),
)

fun Context.col(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.spacer(height: Float): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(1, dp(height))
}

fun Context.gap(width: Float): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(width), 1)
}

/** 行内分隔线，左缩进用于卡片内的"inset divider" */
fun Context.hairline(insetStart: Float = 0f, insetEnd: Float = 0f): View = View(this).apply {
    setBackgroundColor(ShellTheme.current.hairline)
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
        marginStart = dp(insetStart)
        marginEnd = dp(insetEnd)
    }
}

/**
 * 一次点击闸门。
 *
 * 自绘控件全都用 OnClickListener，而列表行 + 底部弹层叠在一起时，
 * 一次 tap 很容易既命中行又命中刚弹出来的按钮（动画期间两层都在窗口里），
 * 于是"点一下删掉两个实例"这类事故就会发生。600ms 内同 View 只认一次。
 *
 * `last` 的初值必须是**负值**，不能是 0：`elapsedRealtime()` 是"开机以来的
 * 毫秒数"，设备刚启动（或 Robolectric 沙箱里时钟从 0 起算）时它可能小于
 * interval，于是首次点击会被 `now - last < interval` 判成连击而丢弃 ——
 * 表现是"这个按钮第一次点没反应"。取 `-interval` 之后首击必过。
 */
fun View.clickOnce(intervalMs: Long = 600L, action: () -> Unit) {
    setOnClickListener(object : View.OnClickListener {
        private var last = -intervalMs
        override fun onClick(v: View) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - last < intervalMs) return
            last = now
            action()
        }
    })
}

/** 让 View 可点且有无障碍语义（自绘控件必须给 contentDescription，否则读屏是哑的） */
fun View.tappable(desc: CharSequence? = null, intervalMs: Long = 600L, action: () -> Unit) {
    isClickable = true
    isFocusable = true
    if (desc != null) contentDescription = desc
    clickOnce(intervalMs, action)
}

/** 微调透明度，做状态色时不用另配一套令牌 */
fun Int.alpha(value: Float): Int {
    val a = (Color.alpha(this) * value.coerceIn(0f, 1f)).toInt()
    return Color.argb(a, Color.red(this), Color.green(this), Color.blue(this))
}

/** 两色线性混合，用于"按下/悬停"这类中间态 */
fun Int.mix(other: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    fun ch(a: Int, b: Int) = (a * (1 - f) + b * f).toInt()
    return Color.argb(
        ch(Color.alpha(this), Color.alpha(other)),
        ch(Color.red(this), Color.red(other)),
        ch(Color.green(this), Color.green(other)),
        ch(Color.blue(this), Color.blue(other)),
    )
}

// ── 壳自己的偏好（与既有设置页的偏好隔离，互不覆盖）──────────────────

class ShellPrefs(context: Context) {
    private val p = context.applicationContext
        .getSharedPreferences("kaze_shell", Context.MODE_PRIVATE)

    var mode: ThemeMode
        get() = ThemeMode.of(p.getString("mode", ThemeMode.SYSTEM.key))
        set(v) = p.edit().putString("mode", v.key).apply()

    /** 上次看的实例：再次进入控制台直接回到它 */
    var lastInstanceId: String?
        get() = p.getString("last_instance", null)
        set(v) = p.edit().putString("last_instance", v).apply()

    /** 控制台字号（sp），与主设置页的 consoleFontSp 保持同一区间 */
    var consoleFont: Float
        get() = p.getFloat("console_font", 12f)
        set(v) = p.edit().putFloat("console_font", v.coerceIn(10f, 18f)).apply()

    /** 控制台是否显示时间戳 */
    var consoleStamps: Boolean
        get() = p.getBoolean("console_stamps", false)
        set(v) = p.edit().putBoolean("console_stamps", v).apply()

    /** 首页是否显示底部"新建实例"快捷入口 */
    var heroCompact: Boolean
        get() = p.getBoolean("hero_compact", false)
        set(v) = p.edit().putBoolean("hero_compact", v).apply()
}
