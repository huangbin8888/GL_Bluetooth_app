package com.guanglu.caliper.ui.pages

import android.content.Context
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.core.model.ChannelNamePolicy
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.Units
import com.guanglu.caliper.ui.Web
import com.guanglu.caliper.ui.WebTone
import com.guanglu.caliper.ui.dp
import com.guanglu.caliper.ui.webBanner
import com.guanglu.caliper.ui.webBold
import com.guanglu.caliper.ui.webBtnRow
import com.guanglu.caliper.ui.webButton
import com.guanglu.caliper.ui.webCard
import com.guanglu.caliper.ui.webCardTitle
import com.guanglu.caliper.ui.webChipFlow
import com.guanglu.caliper.ui.webDrawable
import com.guanglu.caliper.ui.webEdit
import com.guanglu.caliper.ui.webField
import com.guanglu.caliper.ui.webSection
import com.guanglu.caliper.ui.webChip

/**
 * 通道编辑卡：每通道中文命名 + 全通道统一公差入口。
 *
 * @author AI-Generated
 */
internal fun channelEditCard(
    context: Context,
    c: AppContainer,
    actions: UiActions,
    registerSave: ((Boolean) -> Boolean) -> Unit
): LinearLayout = context.run {
    val card = webCard()
    card.addView(webCardTitle("通道编辑 · 命名与公差"))
    card.addView(webChipFlow(listOf(
        webChip("上限 ${ChannelPalette.MAX_CHANNELS} 路", WebTone.BLUE),
        webChip("固定 7 色", WebTone.NEUTRAL)
    )))
    card.addView(webBanner(
        "通道名支持中文，导出 CSV / 彩色表格按此名写「通道名称」列，单通道导出文件名也用它。\n" +
            "上下公差为全通道统一判定值：设置后超差数值变红并三连蜂鸣 + 振动，留空为「未判定」。",
        WebTone.BLUE
    ))

    val names = c.channelNames()
    val edits = mutableMapOf<Int, EditText>()
    card.addView(webSection("通道名称（1–${ChannelPalette.MAX_CHANNELS}）"))
    for (no in 1..ChannelPalette.MAX_CHANNELS) {
        card.addView(channelNameRow(this, no, names[no].orEmpty(), edits))
    }

    card.addView(webSection("公差判定（全通道统一）"))
    val lower = webEdit(c.settings.lowerLimitUm10?.let { Units.formatMm(it) } ?: "")
    val upper = webEdit(c.settings.upperLimitUm10?.let { Units.formatMm(it) } ?: "")
    card.addView(webField("公差下限 (mm)", "低于此值判超差，留空表示未判定", lower))
    card.addView(webField("公差上限 (mm)", "高于此值判超差，留空表示未判定", upper))

    val save: (Boolean) -> Boolean = { notify ->
        saveChannelEdits(c, actions, edits, lower, upper, notify)
    }
    registerSave(save)
    card.addView(webBtnRow(
        webButton("保存通道设置", small = true) { save(true) },
        webButton("恢复默认命名", small = true) {
            c.resetChannelNames()
            edits.forEach { (no, edit) -> edit.setText(ChannelNamePolicy.defaultName(no)) }
            actions.toast("通道名已恢复默认，点「保存通道设置」生效")
        }
    ))
    card
}

/** 单行通道命名：色标圆点 + 通道标签 + 中文名输入框，输入框占满剩余宽度。 */
private fun channelNameRow(
    context: Context,
    no: Int,
    initial: String,
    edits: MutableMap<Int, EditText>
): LinearLayout = context.run {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
    }
    val dot = View(this).apply {
        background = webDrawable(ChannelPalette.colorOf(no), dp(5).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) }
    }
    val caption = webBold("通道$no", 12.5f).apply {
        layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    val edit = EditText(this).apply {
        setText(initial)
        setSingleLine(true)
        textSize = 12.5f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        filters = arrayOf(InputFilter.LengthFilter(ChannelNamePolicy.MAX_NAME_CHARS))
        background = webDrawable(Web.FILE_BG, dp(8).toFloat(), Web.LINE, 1)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
    }
    edits[no] = edit
    row.addView(dot)
    row.addView(caption)
    row.addView(edit)
    row
}

/** 保存 7 个通道名与公差；非法输入还原为原值并返回 false。 */
private fun saveChannelEdits(
    c: AppContainer,
    actions: UiActions,
    edits: Map<Int, EditText>,
    lower: EditText,
    upper: EditText,
    notify: Boolean
): Boolean {
    val lowerText = lower.text.toString()
    val upperText = upper.text.toString()
    val lowerUm10 = if (lowerText.isBlank()) null else Units.toUm10(lowerText, 'm')
    val upperUm10 = if (upperText.isBlank()) null else Units.toUm10(upperText, 'm')
    val invalid = (lowerText.isNotBlank() && lowerUm10 == null) ||
        (upperText.isNotBlank() && upperUm10 == null)
    if (invalid) {
        lower.setText(c.settings.lowerLimitUm10?.let { Units.formatMm(it) } ?: "")
        upper.setText(c.settings.upperLimitUm10?.let { Units.formatMm(it) } ?: "")
        if (notify) actions.toast("公差须为 ≤4 位小数的 mm 数值，通道设置未保存")
        return false
    }
    val names = c.channelNames().toMutableMap()
    edits.forEach { (no, edit) -> names[no] = ChannelNamePolicy.normalize(edit.text.toString(), no) }
    c.settings.channelNames = ChannelNamePolicy.encode(names)
    c.settings.lowerLimitUm10 = lowerUm10
    c.settings.upperLimitUm10 = upperUm10
    edits.forEach { (no, edit) -> edit.setText(ChannelNamePolicy.nameOf(names, no)) }
    if (notify) actions.toast("已保存 7 个通道名与公差；导出 CSV 将按通道名写出")
    return true
}
