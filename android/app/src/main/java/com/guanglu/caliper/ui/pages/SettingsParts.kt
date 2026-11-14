package com.guanglu.caliper.ui.pages

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.ui.WebTone
import com.guanglu.caliper.ui.webBanner
import com.guanglu.caliper.ui.webBtnRow
import com.guanglu.caliper.ui.webButton
import com.guanglu.caliper.ui.webCard
import com.guanglu.caliper.ui.webCardTitle
import com.guanglu.caliper.ui.webEdit
import com.guanglu.caliper.ui.webField
import com.guanglu.caliper.ui.webSpinner
import com.guanglu.caliper.ui.webSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 设置页蓝牙配置卡：NAM 改名、按 MAC 登记设备端参数与 HID 录入。
 *
 * @author AI-Generated
 */
internal fun settingsBluetoothCard(
    context: Context,
    c: AppContainer,
    actions: UiActions
): LinearLayout = context.run {
    val card = webCard()
    card.addView(webCardTitle("蓝牙配置（新增 · 设备端参数）"))
    card.addView(webBanner(
        "设备端参数，App 不下发。自动发射时长、蓝牙自动休眠、HID 结束符由卡尺固件持有；本页仅按设备登记。\n" +
            "接口文档只定义 NAM 改广播名；Android 第三方 App 无法配置卡尺 HID 键盘模式参数。",
        WebTone.WARN
    ))
    val labels = mutableListOf<String>()
    val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    val target = webSpinner(listOf<String>())
    target.adapter = adapter
    val channelNumbers = mutableListOf<Int>()
    val nameInput = webEdit("GL-BLE#0187")

    fun refreshChannels(): Int {
        channelNumbers.clear()
        labels.clear()
        val slots = c.channelSlots()
        slots.keys.sorted().forEach { no ->
            channelNumbers.add(no)
            labels.add("CH$no · ${slots[no]?.takeLast(4) ?: "—"}")
        }
        if (labels.isEmpty()) labels.add("无在线通道")
        adapter.notifyDataSetChanged()
        return channelNumbers.size
    }
    refreshChannels()

    card.addView(webField("目标卡尺", "只列在线通道 · CH / MAC 后四位", target))
    card.addView(webBtnRow(webButton("刷新在线通道", small = true) {
        val count = refreshChannels()
        actions.toast(
            if (count == 0) "当前无在线通道，请先在「连接」页连接卡尺" else "已刷新，在线 $count 个通道"
        )
    }))
    card.addView(webField("修改蓝牙名称", "NAM:<string> · ≤16 字符", nameInput))
    card.addView(namActionRow(context, c, actions, target, nameInput, channelNumbers))
    card.addView(registrationFields(context, c, actions, target, nameInput, channelNumbers))
    card
}

/** 下拉位置对应的真实通道号；无在线通道时返回 null。 */
internal fun channelAtPosition(numbers: List<Int>, position: Int): Int? = numbers.getOrNull(position)

private fun selectedChannel(target: Spinner, numbers: List<Int>): Int? =
    channelAtPosition(numbers, target.selectedItemPosition)

private fun namActionRow(
    context: Context,
    c: AppContainer,
    actions: UiActions,
    target: Spinner,
    nameInput: EditText,
    numbers: List<Int>
): LinearLayout = context.run {
    webBtnRow(
        webButton("下发改名（NAM）", small = true) {
            val channelNo = selectedChannel(target, numbers)
            if (channelNo == null) {
                actions.toast("当前无在线通道，请先在「连接」页连接卡尺")
                return@webButton
            }
            val name = nameInput.text.toString()
            if (!c.applyNam(channelNo, name)) {
                actions.toast("名称非法（须保留 GL-BLE# 前缀、总长 8–16）或 CH$channelNo 已断开")
                return@webButton
            }
            actions.toast("改名指令已写入 CH$channelNo，等待设备回包；3 秒无回包会提示")
        },
        webButton("读取当前名称", small = true) {
            val channelNo = selectedChannel(target, numbers)
            val mac = channelNo?.let { c.channelSlots()[it] } ?: c.settings.lastMac
            if (mac == null) {
                actions.toast("该通道未连接且无历史设备，无法读取登记名")
                return@webButton
            }
            CoroutineScope(Dispatchers.Main).launch {
                runCatching { c.deviceConfig.load(mac) }
                    .onSuccess { device ->
                        val current = device?.customName ?: device?.name
                        if (current == null) {
                            actions.toast("本地未登记 ${mac.takeLast(4)} 的名称")
                        } else {
                            nameInput.setText(current)
                            actions.toast("已填入本地登记名：$current")
                        }
                    }
                    .onFailure { error ->
                        actions.toast("读取登记名失败：${error.javaClass.simpleName}，请重试")
                    }
            }
        }
    )
}

private fun registrationFields(
    context: Context,
    c: AppContainer,
    actions: UiActions,
    target: Spinner,
    nameInput: EditText,
    numbers: List<Int>
): LinearLayout = context.run {
    val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val autoEmit = webEdit("30")
    val sleep = webSwitch(false)
    val sleepMinutes = webEdit("5")
    val terminator = webSpinner(listOf("回车 CR / LF", "Tab"))
    val hidInput = webEdit("")
    hidInput.hint = "接收或粘贴数值…"
    box.addView(webField("自动发射时长（秒）", "设备端参数 · 仅登记", autoEmit))
    box.addView(webField("蓝牙自动休眠", "设备端参数 · 仅登记", sleep))
    box.addView(webField("休眠时间（分钟）", "设备端参数 · 仅登记", sleepMinutes))
    box.addView(webField("结束符（HID 键盘模式）", "卡尺侧设置", terminator))
    box.addView(webField("HID 键盘录入", "数值注入这里并落库", hidInput))
    box.addView(webBtnRow(
        webButton("保存登记（不下发）", small = true) {
            val channelNo = selectedChannel(target, numbers) ?: 1
            val mac = c.channelSlots()[channelNo] ?: c.settings.lastMac
            if (mac == null) {
                actions.toast("该通道未连接且无历史设备，未登记")
                return@webButton
            }
            c.settings.terminator = if (terminator.selectedItemPosition == 1) "TAB" else "CR"
            c.deviceConfig.saveRegistration(
                mac = mac,
                name = nameInput.text.toString(),
                channelNo = channelNo,
                customName = nameInput.text.toString(),
                autoEmitSeconds = autoEmit.text.toString().toIntOrNull(),
                sleepEnabled = sleep.isChecked,
                sleepMinutes = sleepMinutes.text.toString().toIntOrNull(),
                terminator = c.settings.terminator
            )
            actions.toast("已按 MAC ${mac.takeLast(4)} 登记设备端参数，未下发到卡尺")
        },
        webButton("注入示例 HID 输入", small = true) {
            hidInput.setText("155.15\t155.08\t154.96\t")
        }
    ))
    box.addView(webButton("HID 录入落库", small = true) {
        val channelNo = selectedChannel(target, numbers) ?: 1
        val mac = c.channelSlots()[channelNo] ?: (c.settings.lastMac ?: "HID")
        val accepted = c.hid.submit(channelNo, mac, hidInput.text.toString())
        actions.toast("HID 录入 $accepted 条，走同一条解析 → 去抖 → 落库链路")
    })
    box
}

/**
 * 超差蜂鸣开关：新采集数据判定超差时响一声，可关闭。
 *
 * @author AI-Generated
 */
internal fun overLimitBeepField(
    context: Context,
    c: AppContainer,
    actions: UiActions
): LinearLayout = context.run {
    val toggle = webSwitch(c.settings.overLimitBeep)
    toggle.setOnCheckedChangeListener { _, checked ->
        c.settings.overLimitBeep = checked
        actions.toast(if (checked) "已开启超差蜂鸣：超差时响一声" else "已关闭超差蜂鸣")
    }
    webField("超差蜂鸣", "新数据判定超差时响一声；连续超差 1.5 秒内不重复", toggle)
}
