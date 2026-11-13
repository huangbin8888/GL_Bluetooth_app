package com.guanglu.caliper.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 纯代码构建 View 的扩展函数集合。
 *
 * @author AI-Generated
 */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.label(
    text: String,
    size: Float = 14f,
    bold: Boolean = false,
    color: Int = 0xFF16202F.toInt()
): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(null, Typeface.BOLD)
}

fun Context.actionButton(text: String, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

/** 横向等分栏使用的紧凑按钮。 */
fun Context.compactButton(text: String, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(4), dp(6), dp(4), dp(6))
        setSingleLine(true)
        setOnClickListener { onClick() }
    }

/** 横向等分栏的子 View 布局参数。 */
fun evenParams(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

fun Context.input(hint: String, initial: String = ""): EditText = EditText(this).apply {
    this.hint = hint
    setText(initial)
    setSingleLine(true)
}

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = this@card.dp(12).toFloat()
        setStroke(1, 0xFFE2E7F0.toInt())
    }
    setPadding(dp(14), dp(14), dp(14), dp(14))
}

fun Context.page(): ScrollView = ScrollView(this).apply {
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(28))
    }
    addView(column)
}

fun ScrollView.column(): LinearLayout = getChildAt(0) as LinearLayout

fun Context.horizontal(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
}
