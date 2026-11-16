package com.guanglu.caliper.ui.pages

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.core.model.BT_STATE_ON
import com.guanglu.caliper.core.model.deviceAddressHint
import com.guanglu.caliper.core.model.knownDevicesCaption
import com.guanglu.caliper.core.model.knownDeviceLabel
import com.guanglu.caliper.ui.webButton
import com.guanglu.caliper.ui.webTiny
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// 连接页的已登记设备列表与蓝牙恢复重扫，独立于页面构建入口。

/** 已登记卡尺常驻列表：即使本轮未扫描到，也显示名称并提供一键重连。 */
internal fun renderKnownDevices(
    context: Context,
    c: AppContainer,
    actions: UiActions,
    hint: TextView,
    list: LinearLayout
) {
    CoroutineScope(Dispatchers.Main).launch {
        val result = runCatching { c.knownDevices() }
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.javaClass?.simpleName ?: "数据库异常"
            hint.text = "读取已登记卡尺失败：$reason"
            c.diagnostics.log("KNOWN_DEVICE_LOAD_FAIL $reason")
            return@launch
        }
        val devices = result.getOrThrow()
        hint.text = knownDevicesCaption(
            devices.size, c.settings.autoConnect, c.scanReadiness() == null
        )
        list.removeAllViews()
        devices.forEach { device ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                context.webTiny(knownDeviceLabel(device.name, device.mac)).apply { maxLines = 2 },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(context.webButton("重连", small = true) {
                if (c.connect(device.mac)) {
                    actions.toast("正在重连 ${device.name}（${deviceAddressHint(device.mac)}）…")
                } else {
                    actions.toast("通道已满，未连接 ${deviceAddressHint(device.mac)}")
                }
            })
            list.addView(row)
        }
    }
}

/** 连接页监听系统蓝牙开关；关闭再打开时自动重启扫描并刷新已登记设备。 */
internal fun watchBluetoothRecovery(
    context: Context,
    c: AppContainer,
    page: View,
    onRecovered: () -> Unit
) {
    val appContext = context.applicationContext
    val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    var lastState = if (adapter?.isEnabled == true) BT_STATE_ON else -1
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
                c.diagnostics.log("BT_UI_BROADCAST_SKIP ${intent.action.orEmpty()}")
                return
            }
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            val wasOff = lastState != BT_STATE_ON
            lastState = state
            if (state == BT_STATE_ON && wasOff) {
                c.diagnostics.log("BT_UI_RECOVER")
                onRecovered()
            }
        }
    }
    ContextCompat.registerReceiver(
        appContext,
        receiver,
        IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED
    )
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            runCatching { appContext.unregisterReceiver(receiver) }
                .onFailure { error ->
                    c.diagnostics.log("BT_UI_UNREG_FAIL ${error.javaClass.simpleName}")
                }
            page.removeOnAttachStateChangeListener(this)
        }
    })
}
