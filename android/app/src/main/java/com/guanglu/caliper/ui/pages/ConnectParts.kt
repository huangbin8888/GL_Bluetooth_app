package com.guanglu.caliper.ui.pages

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.ui.Web
import com.guanglu.caliper.ui.WebTone
import com.guanglu.caliper.ui.dp
import com.guanglu.caliper.ui.evenParams
import com.guanglu.caliper.ui.webCard
import com.guanglu.caliper.ui.webCardTitle
import com.guanglu.caliper.ui.webChip
import com.guanglu.caliper.ui.webDrawable
import com.guanglu.caliper.ui.webTiny

/**
 * 连接页的状态卡、槽位卡与扫描反馈组件。
 *
 * @author AI-Generated
 */
internal const val SCAN_TIMEOUT_MS = 10_000L

internal fun connectHeader(context: Context, state: TextView): LinearLayout = context.run {
    val card = webCard(true)
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(
        View(context).apply { background = webDrawable(Web.OK, dp(4).toFloat()) },
        LinearLayout.LayoutParams(dp(8), dp(8))
    )
    row.addView(state, evenParams().apply { marginStart = dp(9) })
    row.addView(webChip("GL-BLE# 前缀 · 上限 7", WebTone.BLUE))
    card.addView(row)
    card.addView(webTiny("上次连接：7 路按上次 MAC 自动重连"))
    card
}

internal fun channelSlotsCard(
    context: Context,
    labels: MutableList<TextView>
): LinearLayout = context.run {
    val card = webCard()
    card.addView(webCardTitle("通道槽位 CH1–CH7"))
    for (i in 1..ChannelPalette.MAX_CHANNELS) {
        val label = webTiny("CH$i · 空闲")
        labels.add(label)
        card.addView(label)
    }
    card
}

internal fun scanTimeout(
    c: AppContainer,
    found: List<ScannedDevice>,
    hint: TextView,
    actions: UiActions,
    onDone: () -> Unit
): Runnable = Runnable {
    c.stopScan()
    onDone()
    if (found.isEmpty()) {
        hint.text = "10 秒未发现卡尺：确认卡尺已开机、长按 data 键 3 秒且液晶显示 OK；可在 P4 导出诊断日志查看 SCAN_SKIP"
        actions.toast("未发现卡尺，请查看连接页提示")
    } else {
        hint.text = "发现 ${found.size} 台，点设备名连接"
    }
}

internal fun stopScanOnDetach(page: View, onDetach: () -> Unit) {
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = onDetach()
    })
}

internal fun connectFooter(context: Context): TextView = context.run {
    webTiny(
        "机型适配：连接前先停止扫描；Android 限制 30 秒内最多 5 次扫描；重连后重新发现服务并订阅 notify。\n" +
            "7 路 GATT 走串行命令队列；单通道断连不影响其他通道继续采集。"
    )
}
