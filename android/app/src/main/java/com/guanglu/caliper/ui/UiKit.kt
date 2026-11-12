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

/** 纯代码构建 View：不引入 Compose / AppCompat / XML 布局，降低 M0 前的编译面。 */
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
