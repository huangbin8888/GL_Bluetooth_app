package com.guanglu.caliper.ui.pages

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.ScanRegistry
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.deviceAddressHint
import com.guanglu.caliper.core.model.scannedDeviceLabel
import com.guanglu.caliper.ui.Web
import com.guanglu.caliper.ui.WebTone
import com.guanglu.caliper.ui.dp
import com.guanglu.caliper.ui.evenParams
import com.guanglu.caliper.ui.webButton
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

internal fun connectHeader(context: Context, state: TextView, note: TextView): LinearLayout = context.run {
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
    card.addView(note)
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
    countProvider: () -> Int,
    hint: TextView,
    actions: UiActions,
    dedupProvider: () -> Int = { 0 },
    onDone: () -> Unit
): Runnable = Runnable {
    c.stopScan()
    onDone()
    if (countProvider() == 0) {
        hint.text = "没有搜寻到设备！确认卡尺已开机、长按 data 键 3 秒且液晶显示 OK；可在 P4 导出诊断日志查看 SCAN_SKIP"
        actions.toast("没有搜寻到设备！")
    } else {
        hint.text = "发现 ${countProvider()} 台，点设备名连接" + dedupSuffix(dedupProvider())
    }
}

internal fun dedupSuffix(merged: Int): String =
    if (merged > 0) "（已合并 $merged 次重复广播）" else ""

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

/**
 * 连接页扫描会话：就绪检查、MAC 去重（重复广播原地更新）、可见反馈、10 秒超时与离开页面停扫描。
 *
 * @author AI-Generated
 */
internal class ScanSession(
    private val c: AppContainer,
    private val actions: UiActions,
    private val list: LinearLayout,
    private val hint: TextView,
    private val onConnected: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingTimeout: Runnable? = null
    private val registry = ScanRegistry()
    private val deviceButtons = LinkedHashMap<String, Button>()

    fun stop() {
        pendingTimeout?.let { handler.removeCallbacks(it) }
        pendingTimeout = null
        c.stopScan()
    }

    /** 清空设备列表并重建（全部断开或重新扫描前调用）。 */
    fun reset() {
        registry.clear()
        rebuild()
    }

    /** 当前扫描到的设备（按发现顺序）。 */
    fun devices(): List<ScannedDevice> = registry.devices

    /** 以 MAC 为键复用按钮，只更新文字，避免广播刷新时替换 View 打断点击。 */
    private fun rebuild() {
        val devices = registry.devices
        val live = devices.map { it.mac }.toSet()
        val iterator = deviceButtons.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in live) {
                list.removeView(entry.value)
                iterator.remove()
            }
        }
        devices.forEach { device ->
            val button = deviceButtons[device.mac] ?: createDeviceButton(device.mac).also {
                deviceButtons[device.mac] = it
                list.addView(it)
            }
            button.text = scannedDeviceLabel(device.name, device.mac, device.rssi)
        }
    }

    /** 两行文字承载完整 MAC，不允许单行省略。 */
    private fun createDeviceButton(mac: String): Button =
        list.context.webButton(deviceAddressHint(mac), small = true) {
            actions.toast("正在连接 ${deviceAddressHint(mac)}…")
            if (!c.connect(mac)) {
                actions.toast("通道已满，未连接 ${deviceAddressHint(mac)}")
            }
            onConnected()
        }.apply {
            setSingleLine(false)
            maxLines = 2
            ellipsize = null
        }

    fun start() {
        val reason = c.scanReadiness()
        if (reason != null) {
            actions.toast(reason)
            hint.text = reason
            return
        }
        stop()
        registry.clear()
        rebuild()
        hint.text = "正在扫描… 长按卡尺 data 键 3 秒后等待广播"
        actions.toast("开始扫描 GL-BLE# 卡尺")
        c.startScan { device ->
            handler.post {
                registry.add(device)
                rebuild()
                c.autoConnectScanned(device.mac)
                hint.text = "发现 ${registry.size} 台卡尺，点设备名连接" +
                    dedupSuffix(c.scanDedupCount() + registry.mergedCount)
            }
        }
        val timeout = scanTimeout(
            c,
            { registry.size },
            hint,
            actions,
            { c.scanDedupCount() + registry.mergedCount }
        ) { pendingTimeout = null }
        pendingTimeout = timeout
        handler.postDelayed(timeout, SCAN_TIMEOUT_MS)
    }
}
