package com.guanglu.caliper.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.guanglu.caliper.core.database.entity.Measurement
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.Units
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Web 版 UI 原型的颜色与尺寸变量及对应原生组件。
 *
 * @author AI-Generated
 */
object Web {
    val BG = 0xFFF3F5F9.toInt()
    val CARD = 0xFFFFFFFF.toInt()
    val LINE = 0xFFE2E7F0.toInt()
    val TEXT = 0xFF16202F.toInt()
    val SUB = 0xFF6B778C.toInt()
    val PRIMARY = 0xFF1668E3.toInt()
    val PRIMARY_SOFT = 0xFFE8F0FE.toInt()
    val OK = 0xFF0F9D58.toInt()
    val OK_SOFT = 0xFFE7F6EE.toInt()
    val BAD = 0xFFE5384A.toInt()
    val BAD_SOFT = 0xFFFDECEE.toInt()
    val WARN = 0xFFE8912A.toInt()
    val WARN_SOFT = 0xFFFFF3E2.toInt()
    val ROW_LINE = 0xFFF0F3F8.toInt()
    val TABLE_HEAD = 0xFFFAFBFD.toInt()
    val SEG_BG = 0xFFEEF1F6.toInt()
    val FILE_BG = 0xFFF5F7FB.toInt()
    val CHIP_ACTIVE_BORDER = 0xFFB9D2FA.toInt()
    val BAD_BORDER = 0xFFF3C6CC.toInt()
    val WARN_BORDER = 0xFFF6DDB7.toInt()
    val INFO_BORDER = 0xFFCFE0FC.toInt()
    const val RADIUS = 12
    const val CARD_PAD = 12
    const val CARD_GAP = 10
    const val APPBAR_H = 54
    const val TABBAR_H = 56
    const val VIEWPORT_PAD = 12
    const val BTN_H = 44
    const val BTN_PRIMARY_H = 56
    const val BTN_SM_H = 36
    const val BTN_GAP = 8
}

enum class WebTone { NEUTRAL, BLUE, OK, BAD, WARN }

fun webDrawable(
    fill: Int,
    radiusPx: Float,
    strokeColor: Int = Color.TRANSPARENT,
    strokePx: Int = 0
): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radiusPx
    if (strokePx > 0) setStroke(strokePx, strokeColor)
}

private fun chipFill(tone: WebTone): Int = when (tone) {
    WebTone.NEUTRAL -> Web.SEG_BG
    WebTone.BLUE -> Web.PRIMARY_SOFT
    WebTone.OK -> Web.OK_SOFT
    WebTone.BAD -> Web.BAD_SOFT
    WebTone.WARN -> Web.WARN_SOFT
}

private fun chipText(tone: WebTone): Int = when (tone) {
    WebTone.NEUTRAL -> 0xFF46536A.toInt()
    WebTone.BLUE -> Web.PRIMARY
    WebTone.OK -> Web.OK
    WebTone.BAD -> Web.BAD
    WebTone.WARN -> 0xFFA45F06.toInt()
}

private fun bannerFill(tone: WebTone): Int = when (tone) {
    WebTone.NEUTRAL -> Web.SEG_BG
    WebTone.BLUE -> Web.PRIMARY_SOFT
    WebTone.OK -> Web.OK_SOFT
    WebTone.BAD -> Web.BAD_SOFT
    WebTone.WARN -> Web.WARN_SOFT
}

private fun bannerText(tone: WebTone): Int = when (tone) {
    WebTone.NEUTRAL -> Web.TEXT
    WebTone.BLUE -> 0xFF124CA8.toInt()
    WebTone.OK -> Web.OK
    WebTone.BAD -> 0xFFA71F30.toInt()
    WebTone.WARN -> 0xFF8A5208.toInt()
}

private fun bannerBorder(tone: WebTone): Int = when (tone) {
    WebTone.NEUTRAL -> Web.LINE
    WebTone.BLUE -> Web.INFO_BORDER
    WebTone.OK -> Web.OK_SOFT
    WebTone.BAD -> 0xFFF7C9CF.toInt()
    WebTone.WARN -> Web.WARN_BORDER
}

fun Context.webCard(tight: Boolean = false): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val pad = if (tight) 10 else Web.CARD_PAD
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    background = webDrawable(Web.CARD, dp(Web.RADIUS).toFloat(), Web.LINE, 1)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(Web.CARD_GAP) }
}

fun Context.webCardTitle(text: String): TextView = label(text, 12f, true, Web.SUB).apply {
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(9) }
}

fun Context.webSection(text: String): TextView = label(text, 11f, true, Web.SUB).apply {
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(14)
        bottomMargin = dp(7)
        leftMargin = dp(2)
        rightMargin = dp(2)
    }
}

fun Context.webTiny(
    text: String,
    size: Float = 11f,
    color: Int = Web.SUB
): TextView = label(text, size, false, color).apply {
    setLineSpacing(0f, 1.6f)
}

fun Context.webBold(text: String, size: Float = 13f, color: Int = Web.TEXT): TextView =
    label(text, size, true, color)

fun Context.webMono(text: String, size: Float = 11.5f, color: Int = Web.SUB): TextView =
    label(text, size, false, color).apply { typeface = Typeface.MONOSPACE }

fun Context.webButton(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    small: Boolean = false,
    onClick: () -> Unit
): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    textSize = if (primary && !small) 16f else if (small) 12f else 13f
    setTypeface(null, Typeface.BOLD)
    minWidth = 0
    minimumWidth = 0
    minimumHeight = dp(
        when {
            small -> Web.BTN_SM_H
            primary -> Web.BTN_PRIMARY_H
            else -> Web.BTN_H
        }
    )
    stateListAnimator = null
    elevation = 0f
    setPadding(dp(8), dp(4), dp(8), dp(4))
    when {
        primary -> {
            background = webDrawable(Web.PRIMARY, dp(10).toFloat())
            setTextColor(Color.WHITE)
        }
        danger -> {
            background = webDrawable(Web.CARD, dp(10).toFloat(), Web.BAD_BORDER, 1)
            setTextColor(Web.BAD)
        }
        else -> {
            background = webDrawable(Web.CARD, dp(10).toFloat(), Web.LINE, 1)
            setTextColor(Web.TEXT)
        }
    }
    setOnClickListener { onClick() }
}

fun Context.webBtnRow(vararg views: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(Web.BTN_GAP) }
}.also { row ->
    views.forEachIndexed { index, view ->
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index > 0) marginStart = dp(Web.BTN_GAP)
        }
        row.addView(view)
    }
}

fun Context.webBanner(text: String, tone: WebTone): TextView =
    label(text, 12f, false, bannerText(tone)).apply {
        background = webDrawable(bannerFill(tone), dp(10).toFloat(), bannerBorder(tone), 1)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setLineSpacing(0f, 1.25f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(Web.CARD_GAP) }
    }

fun Context.webChip(text: String, tone: WebTone = WebTone.NEUTRAL): TextView = TextView(this).apply {
    this.text = text
    textSize = 11.5f
    setTypeface(null, Typeface.BOLD)
    setTextColor(chipText(tone))
    background = webDrawable(chipFill(tone), dp(18).toFloat())
    setPadding(dp(10), dp(5), dp(10), dp(5))
}

fun Context.webChipFlow(items: List<View>): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(9) }
}.also { flow ->
    val ctx = this
    items.chunked(4).forEach { group ->
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        group.forEach { view ->
            view.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(5)
                bottomMargin = dp(5)
            }
            line.addView(view)
        }
        flow.addView(line)
    }
}

fun Context.webReading(value: String): TextView {
    val span = SpannableString("$value mm")
    val unitStart = value.length + 1
    span.setSpan(AbsoluteSizeSpan(dp(18)), unitStart, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    span.setSpan(ForegroundColorSpan(Web.SUB), unitStart, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return TextView(this).apply {
        text = span
        textSize = 48f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Web.TEXT)
        setPadding(0, dp(4), 0, dp(4))
    }
}

fun Context.webSeg(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = webDrawable(Web.SEG_BG, dp(9).toFloat())
        setPadding(dp(3), dp(3), dp(3), dp(3))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(Web.CARD_GAP) }
    }.also { seg ->
        val ctx = this
        var current = selected
        val buttons = mutableListOf<Button>()
        labels.forEachIndexed { index, name ->
            val button = Button(ctx).apply {
                text = name
                isAllCaps = false
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                minWidth = 0
                minimumWidth = 0
                minimumHeight = dp(32)
                stateListAnimator = null
                elevation = 0f
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    current = index
                    buttons.forEachIndexed { i, item -> paintSegment(item, i == current) }
                    onSelect(index)
                }
            }
            paintSegment(button, index == current)
            buttons.add(button)
            seg.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

private fun Context.paintSegment(button: Button, active: Boolean) {
    button.background = webDrawable(
        if (active) Web.CARD else Color.TRANSPARENT,
        dp(7).toFloat()
    )
    button.setTextColor(if (active) Web.PRIMARY else Web.SUB)
}

fun Context.webField(labelText: String, hint: String, control: View): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }.also { box ->
        val ctx = this
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        val caption = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        caption.addView(label(labelText, 12.5f))
        if (hint.isNotEmpty()) caption.addView(webTiny(hint, 10.5f))
        line.addView(caption)
        line.addView(control)
        box.addView(line)
        box.addView(View(ctx).apply {
            setBackgroundColor(Web.ROW_LINE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
    }

fun Context.webSwitch(initial: Boolean): Switch = Switch(this).apply {
    isChecked = initial
    text = ""
}

/** 设置页单行文本输入。 */
fun Context.webEdit(initial: String): EditText = EditText(this).apply {
    setText(initial)
    setSingleLine(true)
    textSize = 12.5f
    gravity = Gravity.END or Gravity.CENTER_VERTICAL
    background = webDrawable(Web.FILE_BG, dp(8).toFloat(), Web.LINE, 1)
    setPadding(dp(8), 0, dp(8), 0)
    layoutParams = LinearLayout.LayoutParams(dp(112), dp(34))
}

/** 设置页下拉选择。 */
fun Context.webSpinner(items: List<String>): Spinner = Spinner(this).apply {
    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items)
    layoutParams = LinearLayout.LayoutParams(dp(112), dp(34))
}

fun Context.webCheck(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(11), 0, dp(11))
    }.also { row ->
        val ctx = this
        var state = checked
        val box = TextView(ctx).apply {
            text = if (state) "✓" else ""
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        val caption = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }
        caption.addView(webBold(title, 13f))
        caption.addView(webTiny(hint, 10.5f))
        row.addView(box)
        row.addView(caption)
        val paint = {
            box.text = if (state) "✓" else ""
            box.background = webDrawable(
                if (state) Web.PRIMARY else Web.CARD,
                dp(6).toFloat(),
                if (state) Web.PRIMARY else 0xFFC6D0DE.toInt(),
                dp(2)
            )
        }
        paint()
        row.setOnClickListener {
            state = !state
            paint()
            onChange(state)
        }
    }

fun Context.webStat(value: String, caption: String): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    background = webDrawable(Web.CARD, dp(10).toFloat(), Web.LINE, 1)
    setPadding(dp(4), dp(8), dp(4), dp(8))
}.also { box ->
    box.addView(webBold(value, 15f), LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ))
    box.addView(label(caption, 10f, false, Web.SUB), LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ))
}

fun Context.webStatStrip(vararg stats: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(Web.CARD_GAP) }
}.also { strip ->
    stats.forEachIndexed { index, view ->
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index < stats.size - 1) marginEnd = dp(6)
        }
        strip.addView(view)
    }
}

fun Context.webFileLine(text: String): TextView = label(text, 11.5f, false, Web.TEXT).apply {
    typeface = Typeface.MONOSPACE
    background = webDrawable(Web.FILE_BG, dp(8).toFloat(), Web.LINE, 1)
    setPadding(dp(10), dp(8), dp(10), dp(8))
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(6) }
}

fun Context.webChChip(text: String, dotColor: Int, on: Boolean, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = webDrawable(
            if (on) Web.PRIMARY_SOFT else Web.CARD,
            dp(18).toFloat(),
            if (on) Web.CHIP_ACTIVE_BORDER else Web.LINE,
            1
        )
        setPadding(dp(9), dp(5), dp(9), dp(5))
        isClickable = true
        setOnClickListener { onClick() }
    }.also { chip ->
        val ctx = this
        if (dotColor != 0) {
            chip.addView(View(ctx).apply {
                background = webDrawable(dotColor, dp(4).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(5) }
            })
        }
        chip.addView(label(text, 11f, true, if (on) Web.PRIMARY else Web.TEXT))
    }

fun Context.webChMark(channelNo: Int): TextView = TextView(this).apply {
    text = ChannelPalette.labelOf(channelNo)
    textSize = 9.5f
    setTypeface(null, Typeface.BOLD)
    setTextColor(Color.WHITE)
    background = webDrawable(ChannelPalette.colorOf(channelNo), dp(4).toFloat())
    setPadding(dp(5), dp(2), dp(5), dp(2))
}

fun Context.webChCard(channelNo: Int, value: String, meta: String, online: Boolean): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = webDrawable(Web.CARD, dp(10).toFloat(), Web.LINE, 1)
        alpha = if (online) 1f else 0.56f
    }.also { card ->
        val ctx = this
        val stripe = View(ctx).apply {
            setBackgroundColor(ChannelPalette.colorOf(channelNo))
            minimumHeight = dp(64)
        }
        card.addView(stripe, LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT))
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(9), dp(8), dp(9))
        }
        box.addView(label(ChannelPalette.labelOf(channelNo), 12f, true, ChannelPalette.colorOf(channelNo)))
        box.addView(label(value, 20f, true, Web.TEXT).apply { typeface = Typeface.MONOSPACE })
        box.addView(webTiny(meta, 9.5f))
        card.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

fun Context.webChGrid(vararg cards: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}.also { grid ->
    val ctx = this
    cards.toList().chunked(2).forEach { group ->
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        group.forEachIndexed { index, card ->
            card.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index == 0) marginEnd = dp(4) else marginStart = dp(4)
                bottomMargin = dp(8)
            }
            line.addView(card)
        }
        if (group.size == 1) {
            line.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        }
        grid.addView(line)
    }
}

fun Context.webTab(text: String, active: Boolean, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        setOnClickListener { onClick() }
    }.also { tab ->
        val ctx = this
        val dot = View(ctx).apply {
            background = webDrawable(
                if (active) Web.PRIMARY else Color.TRANSPARENT,
                dp(5).toFloat(),
                if (active) Web.PRIMARY else Web.SUB,
                dp(2)
            )
            alpha = if (active) 1f else 0.55f
        }
        tab.addView(dot, LinearLayout.LayoutParams(dp(18), dp(18)))
        tab.addView(
            label(text, 10.5f, active, if (active) Web.PRIMARY else Web.SUB),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        )
    }

fun Context.webTableHeader(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    setBackgroundColor(Web.TABLE_HEAD)
    setPadding(dp(9), dp(7), dp(9), dp(7))
}.also { head ->
    head.addView(
        label("时间", 11f, true, Web.SUB),
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
    )
    head.addView(
        label("数值 (mm)", 11f, true, Web.SUB).apply { gravity = Gravity.END },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
    )
}

fun Context.webTableRow(
    item: Measurement,
    clock: SimpleDateFormat,
    decimals: Int = 4,
    onClick: () -> Unit
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(9), dp(8), dp(9), dp(8))
        setOnClickListener { onClick() }
    }.also { row ->
        val ctx = this
        val bad = item.verdict == "FAIL"
        row.addView(
            View(ctx).apply { setBackgroundColor(if (bad) Web.BAD else Web.OK) },
            LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT)
        )
        row.addView(
            label(clock.format(Date(item.capturedAt)), 11.5f, false, Web.SUB).apply {
                typeface = Typeface.MONOSPACE
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f).apply {
                marginStart = dp(9)
            }
        )
        val valueBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        valueBox.addView(webChMark(item.channelNo))
        valueBox.addView(
            label(Units.formatMm(item.valueUm10, decimals), 15f, true, if (bad) Web.BAD else Web.TEXT).apply {
                typeface = Typeface.MONOSPACE
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(7) }
        )
        row.addView(valueBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f))
    }

fun Context.webTableEmpty(text: String): TextView = label(text, 11f, false, Web.SUB).apply {
    gravity = Gravity.CENTER
    setPadding(0, dp(18), 0, dp(18))
}

fun Context.webDivider(): View = View(this).apply {
    setBackgroundColor(Web.ROW_LINE)
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
}

fun setReading(view: TextView, value: String) {
    val span = SpannableString("$value mm")
    val unitStart = value.length + 1
    span.setSpan(
        AbsoluteSizeSpan(view.context.dp(18)),
        unitStart,
        span.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    span.setSpan(ForegroundColorSpan(Web.SUB), unitStart, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    view.text = span
}

fun Context.webFrameLine(text: String): TextView = label(text, 11.5f, false, Web.SUB).apply {
    setLineSpacing(0f, 1.5f)
    setPadding(0, dp(8), 0, 0)
}

fun Context.webBadge(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 12f
    setTypeface(null, Typeface.BOLD)
    setTextColor(Color.WHITE)
    background = webDrawable(0x30FFFFFF, dp(6).toFloat())
    setPadding(dp(8), dp(5), dp(8), dp(5))
}
