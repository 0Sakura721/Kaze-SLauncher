package com.kaze.newage.shell

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kaze.newage.R

// ══════════════════════════════════════════════════════════════════════════
// 底部弹层
//
// 用 Dialog 而不是 Fragment/PopupWindow：
//  - PopupWindow 的返回键行为在各 ROM 上不一致，且不会压暗下层；
//  - Fragment 要引 androidx.fragment，本壳刻意不引任何 AndroidX，只保留
//    core-ktx（Application 侧已经在用），界面层零依赖。
//
// 三个必须自己处理的坑：
//  1. 遮罩点击必须由**本窗口**吃掉。窗口是全屏的，若遮罩不 clickable，
//     点击会穿透到 Activity，把刚 dismiss 的那个按钮再点一次；
//  2. 底部留白要跟系统导航栏 / 输入法取最大值。写死 20dp 在全面屏手势条上
//     会让最后一个按钮被挡住；
//  3. 内容区要有最大高度。长表单不给上限会把标题推到屏幕外，
//     用户看不到"这是什么弹层"。
// ══════════════════════════════════════════════════════════════════════════

/** 带最大高度的 ScrollView：不设上限时它会按内容想多高就多高 */
class CappedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : ScrollView(context, attrs) {

    var maxHeight: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, spec)
    }
}

open class Sheet(
    context: Context,
    title: String? = null,
    subtitle: String? = null,
    scrollable: Boolean = true,
) : Dialog(context, R.style.Shell_Sheet) {

    /** 弹层正文：所有 add() 都落在这里 */
    val body: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dp(14f), 0, 0)
    }

    private val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(20f), context.dp(9f), context.dp(20f), context.dp(18f))
        background = context.roundedTop(
            ShellTheme.current.surface,
            Dim.sheet,
            ShellTheme.current.strokeStrong,
            Dim.line,
        )
    }

    private val scroll = CappedScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private val overlay = FrameLayout(context).apply {
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { dismiss() }
    }

    /** 常驻底栏：按钮放在滚动区外，键盘弹出或正文很长时也点得到 */
    private val footer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var footerContent: LinearLayout? = null

    /**
     * 弹层自己的视图树根。
     *
     * 暴露成只读属性是为了让测试能直接量它：Dialog 的 decorView 在测试环境里
     * 不会被 layout（窗口没有真实的 WindowManager 会话），量出来是 0×0，
     * 截出来是一张全透明图。拿到这个根，就能按真实尺寸测量、布局、绘制。
     */
    val contentRoot: View get() = overlay

    private val ctx: Context get() = context

    init {
        // 拖拽把手
        val handle = View(context).apply {
            background = context.rounded(ShellTheme.current.strokeStrong, 40f)
        }
        root.addView(handle, LinearLayout.LayoutParams(context.dp(30f), context.dp(3f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = context.dp(13f)
        })

        if (title != null) {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, context.dp(2f), 0, context.dp(2f))
            }
            texts.addView(context.txt(title, Type.title, ShellTheme.current.ink, medium = true))
            if (subtitle != null) {
                texts.addView(
                    context.txt(subtitle, Type.micro, ShellTheme.current.ink3).apply {
                        setPadding(0, context.dp(4f), 0, 0)
                    },
                )
            }
            header.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val close = context.txt("×", 21f, ShellTheme.current.ink3).apply {
                gravity = Gravity.CENTER
                background = context.rip(Color.TRANSPARENT, 18f)
                tappable("关闭") { dismiss() }
            }
            header.addView(close, LinearLayout.LayoutParams(context.dp(36f), context.dp(36f)))
            root.addView(header)
        }

        if (scrollable) {
            scroll.addView(
                body,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            root.addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        } else {
            // 自带列表的弹层不能再套 ScrollView：外层会抢走列表的拖拽
            root.addView(
                body,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        root.isClickable = true
        root.setOnClickListener { }
        overlay.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        setContentView(overlay)
        setCanceledOnTouchOutside(true)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setGravity(Gravity.TOP or Gravity.START)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.34f)
            // 键盘由我们自己按 insets 让位；交给系统 adjustResize 会和全屏窗口打架
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }

    fun add(view: View, params: LinearLayout.LayoutParams? = null): Sheet {
        body.addView(
            view,
            params ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return this
    }

    fun space(height: Float): Sheet {
        body.addView(View(context), LinearLayout.LayoutParams(1, context.dp(height)))
        return this
    }

    fun label(text: String, color: Int = ShellTheme.current.ink3): TextView {
        val v = context.micro(text, color).apply {
            setPadding(0, context.dp(10f), 0, context.dp(5f))
        }
        add(v)
        return v
    }

    fun note(text: String, color: Int = ShellTheme.current.ink3): TextView {
        val v = context.txt(text, Type.micro, color).apply {
            setLineSpacing(context.dp(2.5f).toFloat(), 1f)
            setPadding(0, context.dp(5f), 0, 0)
        }
        add(v)
        return v
    }

    /** 单行主按钮（占满宽） */
    fun primary(label: String, action: () -> Unit): TextView {
        val b = context.primaryButton(label, action = action)
        add(b, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(Dim.button),
        ).apply { topMargin = context.dp(14f) })
        return b
    }

    /** 主次并排：次要动作在左，主要动作在右（右手拇指区）。返回主按钮，便于后续改文案 */
    fun buttons(
        primaryLabel: String,
        onPrimary: () -> Unit,
        secondaryLabel: String? = null,
        onSecondary: () -> Unit = {},
        danger: Boolean = false,
        enabled: Boolean = true,
    ): TextView {
        val content = ensureFooter()
        val row = context.row()
        if (secondaryLabel != null) {
            row.addView(
                context.outlineButton(secondaryLabel, onSecondary).apply {
                    layoutParams = LinearLayout.LayoutParams(0, context.dp(Dim.button), 1f)
                },
            )
            row.addView(View(context), LinearLayout.LayoutParams(context.dp(9f), 1))
        }
        val main = if (danger) {
            context.dangerButton(primaryLabel, onPrimary).apply {
                layoutParams = LinearLayout.LayoutParams(0, context.dp(Dim.button), 1f)
            }
        } else {
            context.primaryButton(primaryLabel, enabled, onPrimary).apply {
                layoutParams = LinearLayout.LayoutParams(0, context.dp(Dim.button), 1f)
            }
        }
        row.addView(main)
        content.addView(row)
        return main
    }

    /** 只在底栏加一个文字按钮（关闭 / 稍后） */
    fun footerText(label: String, color: Int = ShellTheme.current.accent, action: () -> Unit) {
        val content = ensureFooter()
        content.addView(
            context.textButton(label, color, action),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(42f),
            ),
        )
    }    private fun ensureFooter(): LinearLayout {
        footerContent?.let { return it }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(10f), 0, 0)
        }
        // 底栏与正文之间的分隔线：正文滚到底时按钮区不会和内容糊在一起
        footer.addView(
            View(context).apply { setBackgroundColor(ShellTheme.current.hairline) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
        )
        footer.addView(content)
        root.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = context.dp(12f) },
        )
        footerContent = content
        return content
    }

    /** 改写底栏按钮文案（例如"保存"→"保存中…"） */
    fun setPrimaryBusy(busy: Boolean, wording: String? = null) {
        val content = footerContent ?: return
        for (i in 0 until content.childCount) {
            val rowView = content.getChildAt(i)
            if (rowView !is LinearLayout) continue
            for (j in 0 until rowView.childCount) {
                val child = rowView.getChildAt(j)
                if (child is TextView && child.isClickable) {
                    if (wording != null) child.text = wording
                    child.alpha = if (busy) 0.55f else 1f
                }
            }
        }
    }

    override fun show() {
        super.show()
        window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setGravity(Gravity.TOP or Gravity.START)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        val decor = window?.decorView ?: return
        decor.setOnApplyWindowInsetsListener { _, insets ->
            val (nav, ime) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom to
                    insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom to 0
            }
            root.setPadding(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight,
                ctx.dp(18f) + maxOf(nav, ime),
            )
            val metrics = ctx.resources.displayMetrics
            scroll.maxHeight = if (ime > 0) {
                (metrics.heightPixels - ime - ctx.dp(200f)).coerceAtLeast(ctx.dp(200f))
            } else {
                (metrics.heightPixels * if (footerContent != null) 0.60f else 0.72f).toInt()
            }
            scroll.requestLayout()
            insets
        }
        decor.requestApplyInsets()

        root.alpha = 0f
        root.translationY = ctx.dp(16f).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(170L).start()
    }
}

/** 极简确认框：只有标题、一句正文、两个按钮 */
fun Context.confirm(
    title: String,
    message: String,
    confirmLabel: String = "确定",
    cancelLabel: String = "取消",
    danger: Boolean = false,
    onConfirm: () -> Unit,
) {
    val sheet = Sheet(this, title, scrollable = false)
    sheet.add(txt(message, Type.body, ShellTheme.current.ink2).apply {
        setLineSpacing(dp(3f).toFloat(), 1f)
    })
    sheet.buttons(confirmLabel, {
        sheet.dismiss()
        onConfirm()
    }, secondaryLabel = cancelLabel, onSecondary = { sheet.dismiss() }, danger = danger)
    sheet.show()
}

/** 单选列表弹层：返回选中的下标 */
fun Context.choiceSheet(
    title: String,
    subtitle: String? = null,
    options: List<Pair<String, String>>, // 显示名 to 说明
    selected: Int = -1,
    onPick: (Int) -> Unit,
) {
    val sheet = Sheet(this, title, subtitle)
    options.forEachIndexed { index, (label, hint) ->
        val isSelected = index == selected
        val rowView = row().apply {
            setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
            background = if (isSelected) {
                rounded(ShellTheme.current.accentSoft, Dim.field, ShellTheme.current.accent.alpha(0.35f), Dim.line)
            } else {
                rounded(Color.TRANSPARENT, Dim.field)
            }
            val texts = col().apply {
                addView(txt(label, Type.body, ShellTheme.current.ink, medium = isSelected))
                if (hint.isNotEmpty()) {
                    addView(txt(hint, Type.micro, ShellTheme.current.ink3).apply {
                        setPadding(0, dp(3f), 0, 0)
                    })
                }
            }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (isSelected) {
                addView(txt("✓", 15f, ShellTheme.current.accent, medium = true))
            }
            tappable(label) {
                onPick(index)
                sheet.dismiss()
            }
        }
        sheet.add(rowView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(3f) })
    }
    sheet.footerText("取消", ShellTheme.current.ink3) { sheet.dismiss() }
    sheet.show()
}
