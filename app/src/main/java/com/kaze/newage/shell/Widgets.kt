package com.kaze.newage.shell

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.min

// ══════════════════════════════════════════════════════════════════════════
// 自绘控件
//
// 全部用 Canvas 直接画，不走 Drawable 拼贴。理由：
//  1. 环、刻度、分段条这类图形用 GradientDrawable 做不出来，硬拼就是一层层
//     叠 View，层级一深，点击热区与无障碍焦点全乱；
//  2. 只用 Framework 的 Paint/Canvas，不引 Material、不引 AndroidX，
//     APK 体积与冷启动都不受影响；
//  3. 深色/浅色切换时只需要重读一次令牌并 invalidate()，不必重建视图树。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 状态刻度盘：270° 弧 + 36 根刻度。
 *
 * 中心留给调用方塞文字（它是 FrameLayout）。之所以不把文字画进 onDraw：
 * 文字要跟随字号设置、要能被读屏取到，交给 TextView 比手画 StaticLayout 稳。
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val plateEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcRect = RectF()

    /** 是否处于"工作中"：决定弧的颜色与转速 */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startSpin() else {
                stopSpin()
                progressAnim?.cancel()
                progressAnim = ValueAnimator.ofFloat(shown, 1f).apply {
                    duration = 320L
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        shown = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
            invalidate()
        }

    /** 0f..1f 的弧长比例；空闲时留一小段，避免看起来像"完全没电" */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (!active) shown = field
            invalidate()
        }

    var onToggle: (() -> Unit)? = null

    private var shown = 0f
    private var spinDeg = 0f
    private var spinAnim: ValueAnimator? = null
    private var progressAnim: ValueAnimator? = null
    private var attached = false
    private var lastTap = 0L

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        contentDescription = "启动或停止当前实例"
        minimumWidth = context.dp(104f)
        minimumHeight = context.dp(104f)
    }

    private fun startSpin() {
        if (spinAnim != null) return
        spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 7000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinDeg = it.animatedValue as Float
                if (attached) invalidate()
            }
        }.also { if (attached) it.start() }
    }

    private fun stopSpin() {
        spinAnim?.cancel()
        spinAnim = null
        spinDeg = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (active) {
            startSpin()
            spinAnim?.start()
        }
    }

    override fun onDetachedFromWindow() {
        attached = false
        spinAnim?.cancel()
        spinAnim = null
        progressAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = context.dp(112f)
        setMeasuredDimension(
            resolveSize(want, widthMeasureSpec),
            resolveSize(want, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = ShellTheme.current
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - context.dp(7f)
        val ringR = outer - context.dp(3f)

        // 外圈：刻度所在的一环
        val tickCount = 36
        val startAngle = 135f
        val fullSweep = 270f
        tick.strokeWidth = context.dp(1.6f).toFloat()
        val lit = (shown * tickCount).toInt()
        for (i in 0 until tickCount) {
            val a = Math.toRadians((startAngle + fullSweep * i / (tickCount - 1)).toDouble())
            val isMajor = i % 6 == 0
            val inner = ringR - context.dp(if (isMajor) 5.5f else 3.5f)
            val cosA = Math.cos(a).toFloat()
            val sinA = Math.sin(a).toFloat()
            tick.color = when {
                i < lit && active -> t.accent
                i < lit -> t.accent.alpha(0.45f)
                isMajor -> t.strokeStrong
                else -> t.stroke
            }
            canvas.drawLine(
                cx + cosA * inner, cy + sinA * inner,
                cx + cosA * ringR, cy + sinA * ringR,
                tick,
            )
        }

        // 内圈：进度弧
        val arcR = ringR - context.dp(11f)
        arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        track.strokeWidth = context.dp(2.4f).toFloat()
        track.color = t.surfaceSunken
        canvas.drawArc(arcRect, startAngle, fullSweep, false, track)

        sweep.strokeWidth = context.dp(2.9f).toFloat()
        sweep.color = if (active) t.accent else t.ink3.alpha(0.75f)
        val rotate = if (active) spinDeg else 0f
        val arcLen = if (active) 92f else 24f + 250f * shown
        canvas.drawArc(arcRect, startAngle + rotate, arcLen, false, sweep)
        if (active) {
            // 第二道短弧：与第一道错开 200°，转动时像两端交替，比单弧更有"运行中"的读感
            sweep.color = t.accent.alpha(0.5f)
            canvas.drawArc(arcRect, startAngle + rotate + 200f, 46f, false, sweep)
        }

        // 盘心：用**页面底色**而不是卡片色。卡片本身是 surface，若盘心也用 surface，
        // 圆盘和卡片就糊成一片，「有个可以按的东西」这个暗示就没了。
        val plateR = arcR - context.dp(9f)
        plate.color = t.bg
        canvas.drawCircle(cx, cy, plateR, plate)
        plateEdge.strokeWidth = context.dp(Dim.line).toFloat()
        plateEdge.color = if (active) t.accent.alpha(0.55f) else t.strokeStrong
        canvas.drawCircle(cx, cy, plateR, plateEdge)
    }

    override fun performClick(): Boolean {
        super.performClick()
        val now = SystemClock.elapsedRealtime()
        if (now - lastTap < 600L) return true
        lastTap = now
        animate().scaleX(0.96f).scaleY(0.96f).setDuration(70L).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
        }.start()
        onToggle?.invoke()
        return true
    }
}

/**
 * 开关。自绘的理由不是为了好看——而是 Framework 的 Switch 在
 * `Theme.Material.NoActionBar` 下会被系统主题染色（某些 ROM 上直接染成品红），
 * 与任何自定义配色都不相容。
 */
class TogglePill @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction = 0f
    private var checked = false
    private var anim: ValueAnimator? = null

    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun isChecked(): Boolean = checked

    fun setChecked(value: Boolean, animate: Boolean = true) {
        if (checked == value) {
            if (!animate) {
                fraction = if (value) 1f else 0f
                invalidate()
            }
            return
        }
        checked = value
        anim?.cancel()
        if (animate) {
            anim = ValueAnimator.ofFloat(fraction, if (value) 1f else 0f).apply {
                duration = 170L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            fraction = if (value) 1f else 0f
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dp(40f), widthMeasureSpec),
            resolveSize(context.dp(24f), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = ShellTheme.current
        val r = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = t.surfaceSunken.mix(t.accent, fraction)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(Dim.line).toFloat()
        paint.color = if (fraction > 0.02f) t.accent.alpha(0.55f) else t.strokeStrong
        canvas.drawRoundRect(
            paint.strokeWidth / 2f, paint.strokeWidth / 2f,
            width - paint.strokeWidth / 2f, height - paint.strokeWidth / 2f,
            r, r, paint,
        )

        val knobR = r - context.dp(3.4f)
        val x = knobR + context.dp(3.4f) + fraction * (width - 2f * knobR - context.dp(6.8f))
        paint.style = Paint.Style.FILL
        paint.color = if (checked) t.onAccent else t.ink3
        canvas.drawCircle(x, r, knobR, paint)
    }

    override fun performClick(): Boolean {
        super.performClick()
        setChecked(!checked)
        onCheckedChange?.invoke(checked)
        return true
    }
}

/**
 * 分段计量条：把「已用/总量」画成一段段的格子。
 *
 * 比起连续进度条，分段条在手机上更好读——一眼数得清还剩几格，
 * 而且每一格都对齐到整 MB 区间，不会出现"59% 但看起来快满了"的错觉。
 */
class SegmentMeter @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var segments: Int = 16
        set(value) {
            field = value.coerceIn(2, 64)
            invalidate()
        }

    /** 0f..1f；会被吸附到整格 */
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var tone: Int = 0 // 0=accent 1=good 2=warn 3=danger

    init {
        setWillNotDraw(false)
        minimumHeight = context.dp(10f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dp(120f), widthMeasureSpec),
            resolveSize(context.dp(10f), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = ShellTheme.current
        val gap = context.dp(2.6f).toFloat()
        val segW = (width - gap * (segments - 1)) / segments
        val h = height.toFloat()
        val radius = min(h / 2f, segW / 2f)
        val lit = Math.round(fraction * segments)
        val litColor = when (tone) {
            1 -> t.good
            2 -> t.warn
            3 -> t.danger
            else -> t.accent
        }
        for (i in 0 until segments) {
            val left = i * (segW + gap)
            rect.set(left, 0f, left + segW, h)
            paint.style = Paint.Style.FILL
            paint.color = if (i < lit) litColor else t.surfaceSunken
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (i >= lit) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dp(0.7f).toFloat()
                paint.color = t.stroke
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }
}

/**
 * 细横条进度（下载、部署）。带一层"爬行的亮边"以表明还在动，
 * 静止的进度条最容易被当成卡死。
 */
class RibbonProgress @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var anim: ValueAnimator? = null
    private var attached = false

    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var indeterminate: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) start() else stop()
            invalidate()
        }

    init {
        setWillNotDraw(false)
        minimumHeight = context.dp(6f)
    }

    private fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                if (attached) invalidate()
            }
        }.also { if (attached) it.start() }
    }

    private fun stop() {
        anim?.cancel()
        anim = null
        phase = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (indeterminate) start()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stop()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dp(140f), widthMeasureSpec),
            resolveSize(context.dp(6f), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = ShellTheme.current
        val h = height.toFloat()
        val r = h / 2f
        paint.style = Paint.Style.FILL
        paint.color = t.surfaceSunken
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, paint)

        if (indeterminate) {
            val w = width * 0.34f
            val x = (width + w) * phase - w
            paint.color = t.accent.alpha(0.75f)
            canvas.drawRoundRect(
                x.coerceAtLeast(0f), 0f,
                (x + w).coerceAtMost(width.toFloat()), h, r, r, paint,
            )
            return
        }

        val w = width * fraction
        if (w <= 0f) return
        paint.color = t.accent
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
        // 亮边：只画在头部两像素，给"末端"一个可见的位置感
        paint.color = t.accentDeep.alpha(0.55f)
        canvas.drawRoundRect(
            (w - context.dp(2f)).coerceAtLeast(0f), 0f, w, h, r, r, paint,
        )
    }
}

/** 呼吸点：运行中的状态标识，比整块变色克制 */
class BreathDot @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulse = 0f
    private var anim: ValueAnimator? = null
    private var attached = false

    var color: Int = 0

    var running: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startPulse() else {
                anim?.cancel()
                anim = null
                pulse = 0f
            }
            invalidate()
        }

    init {
        setWillNotDraw(false)
    }

    private fun startPulse() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                if (attached) invalidate()
            }
        }.also { if (attached) it.start() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (running) startPulse()
    }

    override fun onDetachedFromWindow() {
        attached = false
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dp(12f), widthMeasureSpec),
            resolveSize(context.dp(12f), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = ShellTheme.current
        val c = if (color != 0) color else t.accent
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f - context.dp(2.5f)

        if (running) {
            // 扩散环：半径随脉冲放大、透明度反向衰减
            val spread = base + context.dp(2.5f) * pulse
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(1f).toFloat()
            paint.color = c.alpha(0.45f * (1f - pulse))
            canvas.drawCircle(cx, cy, spread, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (running) c else c.alpha(0.5f)
        canvas.drawCircle(cx, cy, base * 0.78f, paint)
    }
}

/**
 * 「正在跑」的实例卡片左缘色条。
 *
 * 单独做成一个 View 而不是给卡片整体染色：卡片底色承载着内容可读性，
 * 跟着状态变色会让长列表整体呼吸、看着累。
 */
class EdgeStripe @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var tone: Int = 0

    init { setWillNotDraw(false) }

    override fun onDraw(canvas: Canvas) {
        val t = ShellTheme.current
        paint.color = when (tone) {
            1 -> t.accent
            2 -> t.good
            3 -> t.warn
            4 -> t.danger
            else -> t.strokeStrong
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
    }
}

/**
 * 滑块。
 *
 * 不用 Framework 的 SeekBar：它把轨道画在 `progressDrawable` 里，而这个
 * drawable 的 tint 会被主题覆盖，各 ROM 上颜色不一；更麻烦的是它的
 * thumb 有最小触摸尺寸，做不出"轨道 4dp、滑块 16dp"这种比例。
 *
 * 交互上刻意只响应 **DOWN 与 MOVE 落在轨道区域内**（含上下各 18dp 缓冲），
 * 而不是整块 View：滑块通常嵌在可滚动页面里，整块可拖会让纵向滑动被抢走。
 */
class SliderBar @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = false

    /** 最小/最大值 */
    var min: Float = 0f
    var max: Float = 100f
    /** 步进；0 表示连续 */
    var step: Float = 0f

    var value: Float = 0f
        set(v) {
            val clamped = v.coerceIn(min, max)
            val snapped = if (step > 0f) (Math.round(clamped / step) * step).coerceIn(min, max) else clamped
            if (snapped == field) return
            field = snapped
            invalidate()
            onValueChange?.invoke(snapped)
        }

    var onValueChange: ((Float) -> Unit)? = null
    /** 松手时回调，用于"拖完再落盘"，避免拖动过程里连续写文件 */
    var onValueSettled: ((Float) -> Unit)? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        minimumHeight = context.dp(34f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dp(160f), widthMeasureSpec),
            resolveSize(context.dp(34f), heightMeasureSpec),
        )
    }

    private val trackLeft: Float get() = paddingLeft + context.dp(9f).toFloat()
    private val trackRight: Float get() = width - paddingRight - context.dp(9f).toFloat()

    private fun fractionOf(v: Float): Float =
        if (max <= min) 0f else ((v - min) / (max - min)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val t = ShellTheme.current
        val cy = height / 2f
        val l = trackLeft
        val r = trackRight
        val h = context.dp(3.6f).toFloat()
        val radius = h / 2f

        paint.style = Paint.Style.FILL
        paint.color = t.surfaceSunken
        canvas.drawRoundRect(l, cy - radius, r, cy + radius, radius, radius, paint)

        val fx = fractionOf(value)
        val head = l + (r - l) * fx
        paint.color = t.accent
        canvas.drawRoundRect(l, cy - radius, head.coerceAtLeast(l), cy + radius, radius, radius, paint)

        // 刻度：每 1/8 一格，给"还能拖多远"一个参照
        paint.color = t.stroke
        for (i in 0..8) {
            val x = l + (r - l) * i / 8f
            canvas.drawRect(x - context.dp(0.4f), cy + context.dp(6f), x + context.dp(0.4f), cy + context.dp(9f), paint)
        }

        val knobR = context.dp(if (dragging) 9.5f else 8f).toFloat()
        paint.color = t.surface
        canvas.drawCircle(head, cy, knobR, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1.6f).toFloat()
        paint.color = t.accent
        canvas.drawCircle(head, cy, knobR, paint)
        paint.style = Paint.Style.FILL
        paint.color = t.accent
        canvas.drawCircle(head, cy, knobR * 0.34f, paint)
    }

    private fun applyTouch(x: Float) {
        val l = trackLeft
        val r = trackRight
        if (r <= l) return
        val f = ((x - l) / (r - l)).coerceIn(0f, 1f)
        value = min + (max - min) * f
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val near = event.y > -context.dp(18f) && event.y < height + context.dp(18f)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (!near) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                applyTouch(event.x)
                invalidate()
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                applyTouch(event.x)
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onValueSettled?.invoke(value)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * 页面背板。
 *
 * 不用纯色而用一道极缓的纵向渐变（顶部略亮、底部略深）：纯色在 OLED 上会让
 * 卡片与背景缺少分离感，而渐变只要 3~5 个色阶差就够看出层次，且不增加任何绘制成本
 * （一次性 Shader，之后每帧只是一次 drawRect）。
 */
class PaperBackdrop @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: android.graphics.Shader? = null
    private var grain: android.graphics.Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val t = ShellTheme.current
        shader = android.graphics.LinearGradient(
            0f, 0f, w * 0.35f, h.toFloat(),
            intArrayOf(t.bg, t.bgDeep),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
        // 右上角一团极淡的暖色：让"纸"看起来有光而不是死平。
        // 半径取屏宽 0.9 倍、透明度 4%，正常看不出是圆，只会觉得那一角不那么冷。
        grain = android.graphics.RadialGradient(
            w * 0.92f, -h * 0.05f, (w * 0.95f).coerceAtLeast(1f),
            intArrayOf(t.accent.alpha(if (t.dark) 0.10f else 0.055f), 0x00000000),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = grain
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}

/**
 * 底部导航。
 *
 * 三个页签，自绘：文字 + 上方一道 2dp 的指示条。
 * 用文字不用图标——本应用的三个入口（实例 / 控制台 / 设置）语义清楚，
 * 硬画三套图标只会让底部更挤；指示条是唯一的"当前位置"信号，
 * 比改变文字颜色更容易在余光里扫到。
 */
class ShellNavBar @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    data class Tab(val key: String, val label: String)

    var tabs: List<Tab> = emptyList()
        set(value) {
            field = value
            rebuild()
        }

    var selected: Int = 0
        set(value) {
            if (field == value) return
            field = value
            refreshTabs()
        }

    var onSelect: ((Int) -> Unit)? = null

    private val items = mutableListOf<LinearLayout>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8f), context.dp(5f), context.dp(8f), context.dp(5f))
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun rebuild() {
        removeAllViews()
        items.clear()
        tabs.forEachIndexed { index, tab ->
            val cell = context.col().apply {
                // 父容器的 gravity 只负责**垂直**居中整组子 View：
                // layoutVertical() 里 child 的 lp.gravity 若是 NO_GRAVITY，会被替换成
                // `mGravity & VERTICAL_GRAVITY_MASK`，横向位在这一步就被抹掉了，
                // 最终走 `default: childLeft = paddingLeft` —— 表现为"每个标签都贴着自己那格左边"。
                // 所以横向居中必须逐个下发给子 View 的 LayoutParams，不能只写在父容器上。
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(6f), 0, context.dp(4f))

                val labelLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                addView(
                    context.txt(tab.label, Type.micro, ShellTheme.current.ink3, medium = true).apply {
                        letterSpacing = 0.06f
                        tag = "label"
                    },
                    labelLp,
                )

                // 指示条放在文字**下面**：放上面会悬在半空，像是没对齐的分隔线；
                // 放下面则贴着文字，读作"当前所在位置"的下划线。
                val barLp = LinearLayout.LayoutParams(context.dp(18f), context.dp(2f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = context.dp(5f)
                }
                addView(
                    View(context).apply {
                        background = context.rounded(Color.TRANSPARENT, 4f)
                        tag = "indicator"
                    },
                    barLp,
                )
                tappable(tab.label) { onSelect?.invoke(index) }
            }
            addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            items.add(cell)
        }
        refreshTabs()
    }

    private fun refreshTabs() {
        val t = ShellTheme.current
        items.forEachIndexed { index, cell ->
            val active = index == selected
            val indicator = cell.findViewWithTag<View>("indicator")
            val label = cell.findViewWithTag<android.widget.TextView>("label")
            indicator?.background = context.rounded(
                if (active) t.accent else Color.TRANSPARENT, 4f,
            )
            label?.setTextColor(if (active) t.ink else t.ink3)
        }
    }
}

// ── Widgets.kt 内部的通用构造器（只在壳内用） ──────────────────────────

internal fun Context.centered(block: LinearLayout.() -> Unit): LinearLayout =
    col().apply {
        gravity = Gravity.CENTER
        block()
    }
