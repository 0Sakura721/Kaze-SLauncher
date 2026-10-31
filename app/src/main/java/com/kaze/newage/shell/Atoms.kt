package com.kaze.newage.shell

import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// ══════════════════════════════════════════════════════════════════════════
// 原子件
//
// 这一层只管"长什么样"，不知道任何业务概念。屏里只组合它们，
// 于是改一次圆角/描边/字重，全应用同时改。
//
// 代码风格约定：每个构造函数先把 `this` 收进 `c`，之后一律 `c.xxx`。
// 原因很实际——Kotlin 的 lambda 带接收者（apply/forEach），嵌套两层之后
// 隐式接收者是 LinearLayout 而不是 Context，`dp()`、`row()` 这类 Context
// 扩展就全部失配了。显式捕获一个局部变量，比到处写 `this@某个函数名` 稳。
// ══════════════════════════════════════════════════════════════════════════

/** 卡片：面 + 1px 描边，无阴影。阴影在深色底上是一团脏，这里统一不用。 */
fun Context.card(
    radius: Float = Dim.card,
    fill: Int = ShellTheme.current.surface,
    stroke: Int = ShellTheme.current.stroke,
    pad: Float = Dim.cardPad,
): LinearLayout {
    val c = this
    return c.col().apply {
        background = c.rounded(fill, radius, stroke, Dim.line)
        setPadding(c.dp(pad), c.dp(pad), c.dp(pad), c.dp(pad))
    }
}

/** 无内边距卡片：边距由内容自己管（列表、可点击行用） */
fun Context.bareCard(radius: Float = Dim.card): LinearLayout {
    val c = this
    return c.col().apply {
        background = c.rounded(
            ShellTheme.current.surface, radius, ShellTheme.current.stroke, Dim.line,
        )
    }
}

/** 分区标题：竖线 + 全大写小字 + 右侧可选动作 */
fun Context.sectionHeader(
    label: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
): LinearLayout {
    val c = this
    return c.row().apply {
        addView(View(c).apply {
            setBackgroundColor(ShellTheme.current.accent)
            layoutParams = LinearLayout.LayoutParams(c.dp(2.6f), c.dp(11f)).apply {
                marginEnd = c.dp(7f)
            }
        })
        addView(c.micro(label))
        if (action != null) {
            addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(
                c.txt(action, Type.micro, ShellTheme.current.accent, medium = true).apply {
                    setPadding(c.dp(8f), c.dp(3f), 0, c.dp(3f))
                    tappable(action) { onAction?.invoke() }
                },
            )
        }
    }
}

/** 主按钮：实心强调色 */
fun Context.primaryButton(
    label: String,
    enabled: Boolean = true,
    action: () -> Unit,
): TextView {
    val c = this
    return c.txt(
        label,
        Type.subtitle,
        if (enabled) ShellTheme.current.onAccent else ShellTheme.current.ink3,
        medium = true,
    ).apply {
        gravity = Gravity.CENTER
        setPadding(c.dp(20f), 0, c.dp(20f), 0)
        background = c.rip(
            if (enabled) ShellTheme.current.accent else ShellTheme.current.surfaceSunken,
            Dim.field,
            if (ShellTheme.current.dark) 0x33FFFFFF else 0x30FFFFFF,
        )
        alpha = if (enabled) 1f else 0.85f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, c.dp(Dim.button),
        )
        if (enabled) tappable(label) { action() }
    }
}

/** 次按钮：描边 + 面底，和主按钮形成轻重对比 */
fun Context.outlineButton(label: String, action: () -> Unit): TextView {
    val c = this
    return c.txt(label, Type.subtitle, ShellTheme.current.ink, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(c.dp(20f), 0, c.dp(20f), 0)
        background = c.rip(
            ShellTheme.current.surface,
            Dim.field,
            stroke = ShellTheme.current.strokeStrong,
            strokeWidth = Dim.line,
        )
        layoutParams = LinearLayout.LayoutParams(0, c.dp(Dim.button), 1f)
        tappable(label) { action() }
    }
}

/** 危险按钮：只描边不铺红——铺满红会把整屏注意力都拽过去 */
fun Context.dangerButton(label: String, action: () -> Unit): TextView {
    val c = this
    return c.txt(label, Type.subtitle, ShellTheme.current.danger, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(c.dp(20f), 0, c.dp(20f), 0)
        background = c.rip(
            ShellTheme.current.surface,
            Dim.field,
            stroke = ShellTheme.current.danger.alpha(0.45f),
            strokeWidth = Dim.line,
        )
        layoutParams = LinearLayout.LayoutParams(0, c.dp(Dim.button), 1f)
        tappable(label) { action() }
    }
}

/** 文字按钮 */
fun Context.textButton(
    label: String,
    color: Int = ShellTheme.current.ink2,
    action: () -> Unit,
): TextView {
    val c = this
    return c.txt(label, Type.subtitle, color, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(c.dp(12f), c.dp(10f), c.dp(12f), c.dp(10f))
        tappable(label) { action() }
    }
}

/** 胶囊标签：状态、版本、类型都用它 */
fun Context.chip(
    label: String,
    color: Int = ShellTheme.current.ink2,
    fill: Int = ShellTheme.current.surfaceAlt,
    stroke: Int = ShellTheme.current.stroke,
): TextView {
    val c = this
    return c.txt(label, Type.micro, color, medium = true).apply {
        setPadding(c.dp(8f), c.dp(3.5f), c.dp(8f), c.dp(3.5f))
        background = c.rounded(fill, Dim.pill, stroke, Dim.line)
    }
}

/** 状态标签：圆点 + 文字，比单个色点信息量足 */
/**
 * 状态胶囊：圆点 + 文字。
 *
 * 标签必须有**宽度上界**（maxWidth + 单行 + 省略）。
 * 它常与 `weight=1` 的名称列并排，而 LinearLayout 的第一遍测量会先按自然宽
 * 量所有 WRAP 子项、第二遍才把剩余宽度分给 weight 列 —— 胶囊没有上界时，
 * 长文案（如 "首次启动 · 生成 eula.txt"）会先把宽度吃光，名称列被压到
 * 十几像素：名称折成三行、副标题逐字竖排。320dp 屏实测名称列只剩 10px。
 *
 * 上界取 96dp：它现在与副标题同排（副标题是 weight=1），132dp 的胶囊在 320dp 屏
 * 上仍会把副标题挤成 0 宽 —— 实测副标题文本区为 0。96dp 够放"启动中""运行中"
 * 这类常见状态，更长的由省略号收尾；完整状态在控制台页与实例详情里都能看到。
 */
fun Context.statusTag(label: String, tone: Int): LinearLayout {
    val c = this
    return c.row().apply {
        setPadding(c.dp(8f), c.dp(3.5f), c.dp(9f), c.dp(3.5f))
        background = c.rounded(
            tone.alpha(if (ShellTheme.current.dark) 0.18f else 0.10f),
            Dim.pill,
            tone.alpha(0.32f),
            Dim.line,
        )
        addView(View(c).apply {
            background = c.rounded(tone, 40f)
            layoutParams = LinearLayout.LayoutParams(c.dp(5f), c.dp(5f)).apply {
                marginEnd = c.dp(5f)
            }
        })
        addView(
            c.txt(label, Type.micro, tone, medium = true).apply {
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = c.dp(96f)
            },
        )
    }
}

/** 键值行：值单行、中段省略，避免换行把行高撑乱 */
fun Context.kvRow(
    label: String,
    value: String,
    valueColor: Int = ShellTheme.current.ink,
): LinearLayout {
    val c = this
    return c.row().apply {
        setPadding(0, c.dp(9f), 0, c.dp(9f))
        addView(c.txt(label, Type.body, ShellTheme.current.ink3))
        addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        addView(
            c.txt(value, Type.body, valueColor, medium = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                gravity = Gravity.END
            },
        )
    }
}

/** 设置项行：标题 + 副标题 + 右侧控件 */
fun Context.settingRow(
    title: String,
    subtitle: String? = null,
    trailing: View? = null,
    onClick: (() -> Unit)? = null,
): LinearLayout {
    val c = this
    return c.row().apply {
        setPadding(c.dp(Dim.cardPad), c.dp(12f), c.dp(Dim.cardPad), c.dp(12f))
        minimumHeight = c.dp(Dim.row)
        val texts = c.col().apply {
            // 标题列是 weight=1，宽度取决于同行 trailing 有多宽。
            // 目前 trailing 都很窄（chevron 18dp / 开关 40dp / 短文本），
            // 但一旦换成宽内容（版本串、时间戳），这一列会被压到几十 dp，
            // 中文会逐字竖排。给标题/副标题都设行数上限，让它们自己收尾。
            addView(
                c.txt(title, Type.body, ShellTheme.current.ink, medium = true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            if (subtitle != null) {
                addView(
                    c.txt(subtitle, Type.micro, ShellTheme.current.ink3).apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, c.dp(3f), 0, 0)
                    },
                )
            }
        }
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            addView(
                trailing,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = c.dp(10f) },
            )
        }
        if (onClick != null) tappable(title) { onClick() }
    }
}

/**
 * 右箭头：Path 直画，不依赖图标字体。
 *
 * 必须自己 onMeasure 出固定尺寸，不能靠外层给 LayoutParams：
 * 一旦它作为 trailing 控件被塞进 `WRAP_CONTENT` 的槽位，原来的 18dp 就被覆盖，
 * 内层 MATCH_PARENT 的绘图 View 会把整个宽高撑满 —— 图标会变成占半屏的大箭头。
 */
fun Context.chevron(color: Int = ShellTheme.current.ink3): View {
    val c = this
    val side = c.dp(18f)
    return object : View(c) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = c.dp(1.5f).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            this.color = color
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                resolveSize(side, widthMeasureSpec),
                resolveSize(side, heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val path = android.graphics.Path().apply {
                moveTo(w * 0.36f, h * 0.26f)
                lineTo(w * 0.64f, h * 0.5f)
                lineTo(w * 0.36f, h * 0.74f)
            }
            canvas.drawPath(path, p)
        }
    }
}

/** 输入框：单一实现，避免各屏自己 setBackground 造成边角不一致 */
fun Context.input(hint: String, value: String = "", numeric: Boolean = false): EditText {
    val c = this
    return EditText(c).apply {
        this.hint = hint
        setText(value)
        setSelection(value.length)
        textSize = Type.body
        setTextColor(ShellTheme.current.ink)
        setHintTextColor(ShellTheme.current.ink3)
        includeFontPadding = false
        background = c.rounded(
            ShellTheme.current.surfaceAlt, Dim.field,
            ShellTheme.current.stroke, Dim.line,
        )
        setPadding(c.dp(12f), c.dp(11f), c.dp(12f), c.dp(11f))
        maxLines = 1
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
}

/** 空态：带一个线框图形，避免"什么都没有"被当成加载失败 */
fun Context.emptyState(
    title: String,
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
): LinearLayout {
    val c = this
    return c.col().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(c.dp(24f), c.dp(36f), c.dp(24f), c.dp(36f))

        addView(object : View(c) {
            private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = c.dp(1.2f).toFloat()
                color = ShellTheme.current.strokeStrong
            }
            private val dash = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = c.dp(1f).toFloat()
                color = ShellTheme.current.stroke
                pathEffect = android.graphics.DashPathEffect(
                    floatArrayOf(c.dp(3f).toFloat(), c.dp(3f).toFloat()), 0f,
                )
            }

            // 必须自己量：默认 View.onMeasure 会把 AT_MOST/UNSPECIFIED 当成
            // "取父容器给的全部空间"，于是这个 42dp 的小图形在空态里被拉成一整块。
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(
                    resolveSize(c.dp(46f), widthMeasureSpec),
                    resolveSize(c.dp(42f), heightMeasureSpec),
                )
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                val s = c.dp(30f).toFloat()
                val x = (width - s) / 2f
                val y = (height - s) / 2f
                val r = c.dp(6f).toFloat()
                canvas.drawRoundRect(x, y, x + s, y + s, r, r, p)
                canvas.drawLine(x + s * 0.26f, y + s * 0.5f, x + s * 0.74f, y + s * 0.5f, dash)
                canvas.drawLine(x + s * 0.5f, y + s * 0.26f, x + s * 0.5f, y + s * 0.74f, dash)
            }
        }, LinearLayout.LayoutParams(c.dp(46f), c.dp(42f)))

        addView(c.txt(title, Type.subtitle, ShellTheme.current.ink, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, c.dp(10f), 0, 0)
        })
        addView(c.txt(hint, Type.micro, ShellTheme.current.ink3).apply {
            gravity = Gravity.CENTER
            setPadding(0, c.dp(6f), 0, 0)
            maxLines = 3
        })
        if (actionLabel != null && onAction != null) {
            addView(c.primaryButton(actionLabel, action = onAction).apply {
                layoutParams = LinearLayout.LayoutParams(c.dp(156f), c.dp(Dim.button)).apply {
                    topMargin = c.dp(16f)
                }
            })
        }
    }
}

/** 提示条：贴在内容流里滚动，不会被键盘顶掉，也不像 Snackbar 那样盖住按钮 */
fun Context.banner(
    text: String,
    tone: Int = ShellTheme.current.danger,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
): LinearLayout {
    val c = this
    return c.row().apply {
        setPadding(c.dp(12f), c.dp(10f), c.dp(12f), c.dp(10f))
        background = c.rounded(
            tone.alpha(if (ShellTheme.current.dark) 0.15f else 0.08f),
            Dim.field,
            tone.alpha(0.34f),
            Dim.line,
        )
        addView(View(c).apply {
            setBackgroundColor(tone)
            layoutParams = LinearLayout.LayoutParams(c.dp(2f), c.dp(15f)).apply {
                marginEnd = c.dp(9f)
            }
        })
        addView(
            c.txt(text, Type.micro, tone, medium = true).apply {
                setLineSpacing(c.dp(2.4f).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (actionLabel != null && onAction != null) {
            addView(c.txt(actionLabel, Type.micro, tone, medium = true).apply {
                // 横向 padding 换成 margin：padding 会从**可用宽度**里扣掉，
                // 两字标签（"部署"/"查看"）减去左右各 10dp 后就只剩刚好一个字宽，
                // 于是竖排成两行。margin 不参与内容测量，标签能按真实字宽排版。
                setPadding(0, c.dp(2f), 0, c.dp(2f))
                isSingleLine = true
                tappable(actionLabel) { onAction() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = c.dp(10f) }
            })
        }
    }
}

/** 骨架块：加载占位，比转圈更能提示内容结构 */
fun Context.skeleton(height: Float, widthDp: Float? = null): View {
    val c = this
    return View(c).apply {
        background = c.rounded(ShellTheme.current.surfaceSunken, 6f)
        layoutParams = LinearLayout.LayoutParams(
            if (widthDp == null) LinearLayout.LayoutParams.MATCH_PARENT else c.dp(widthDp),
            c.dp(height),
        )
    }
}

/** 左对齐缩进的分隔线 */
fun Context.splitter(inset: Float = Dim.cardPad): View {
    val c = this
    return c.hairline(inset, inset).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = c.dp(inset)
            marginEnd = c.dp(inset)
        }
    }
}

/** 首字方块：没有图标资源时的一致性兜底 */
fun Context.tileBadge(text: String, tone: Int, size: Float = 34f): FrameLayout {
    val c = this
    return FrameLayout(c).apply {
        background = c.rounded(
            tone.alpha(if (ShellTheme.current.dark) 0.22f else 0.12f),
            9f,
            tone.alpha(0.30f),
            Dim.line,
        )
        layoutParams = LinearLayout.LayoutParams(c.dp(size), c.dp(size))
        addView(
            c.txt(text, 13f, tone, medium = true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER },
        )
    }
}

/** 可滚动正文：返回 (ScrollView, 内容列) */
fun Context.scrollBody(padBottom: Float = 26f): Pair<ScrollView, LinearLayout> {
    val c = this
    val content = c.col().apply { setPadding(0, 0, 0, c.dp(padBottom)) }
    val scroll = ScrollView(c).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
    }
    return scroll to content
}

// 注：曾有一个 gutterMargin(extra) 便捷函数，与 stackMargin(horizontal=) 完全重复
//（都返回 MATCH_PARENT × WRAP_CONTENT + 左右 margin），且全仓零引用，已删除。
// 需要"只有左右留白、上下不限"时直接用 stackMargin(top=0, bottom=0)。

/** 统一左右页边距 */
fun Context.gutterMargin(extra: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        marginStart = dp(Dim.gutter + extra)
        marginEnd = dp(Dim.gutter + extra)
    }

/**
 * 上下间距 + 左右页边距。
 *
 * 注意：它把高度固定成 WRAP_CONTENT。套在**自带固定高度**的 View 上（分隔线、
 * 固定高进度条）会把那个高度覆盖掉 —— 裸 View 在 WRAP_CONTENT 下会吃掉父容器
 * 剩余空间，分隔线会被拉成几百像素的灰板。这类元素请改用
 * [fixedHeightMargin] 或直接构造 LayoutParams。
 */
fun Context.stackMargin(
    top: Float = 0f,
    bottom: Float = 0f,
    horizontal: Float = Dim.gutter,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
).apply {
    marginStart = dp(horizontal)
    marginEnd = dp(horizontal)
    topMargin = dp(top)
    bottomMargin = dp(bottom)
}

/** 固定高度的边距参数：给分隔线、进度条这类不能伸缩的元素用 */
fun Context.fixedHeightMargin(
    heightDp: Float,
    top: Float = 0f,
    bottom: Float = 0f,
    horizontal: Float = Dim.gutter,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    dp(heightDp),
).apply {
    marginStart = dp(horizontal)
    marginEnd = dp(horizontal)
    topMargin = dp(top)
    bottomMargin = dp(bottom)
}
