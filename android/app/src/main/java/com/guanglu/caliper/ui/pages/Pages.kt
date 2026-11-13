package com.guanglu.caliper.ui.pages

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.device.CaliperCommand
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.Units
import com.guanglu.caliper.ui.MeasurementAdapter
import com.guanglu.caliper.ui.actionButton
import com.guanglu.caliper.ui.card
import com.guanglu.caliper.ui.chart.ChartCanvasView
import com.guanglu.caliper.ui.column
import com.guanglu.caliper.ui.dp
import com.guanglu.caliper.ui.horizontal
import com.guanglu.caliper.ui.input
import com.guanglu.caliper.ui.label
import com.guanglu.caliper.ui.page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

interface UiActions {
    fun toast(message: String)
    fun setPage(pageId: String)
    fun exportCsv(channelNo: Int? = null)
    fun importCsv()
    fun exportDiagnostics()
    fun exportPng(bitmap: Bitmap)
    fun openSystemSettings()
}

/**
 * 7 个页面的 View 构建入口。
 *
 * @author AI-Generated
 */
object Pages {
    private const val TEMPLATE_COLUMNS =
        "16 列：DATE / TIME / SEQ / BATCH / ITEM / NOMINAL / LOWER_TOL / UPPER_TOL / " +
            "VALUE_MM / RAW_VALUE / RAW_UNIT / VERDICT / SOURCE / DEVICE_MAC / CHANNEL / NOTE"

    fun build(context: Context, pageId: String, c: AppContainer, actions: UiActions): View =
        when (pageId) {
            "P1" -> connect(context, c, actions)
            "P2" -> capture(context, c, actions)
            "P3" -> channels(context, c, actions)
            "P4" -> templates(context, c, actions)
            "P5" -> analysis(context, c, actions)
            "P6" -> export(context, c, actions)
            "P7" -> settings(context, c, actions)
            else -> capture(context, c, actions)
        }

    private fun header(context: Context, title: String, sub: String, col: LinearLayout) {
        col.addView(context.label(title, 20f, true))
        col.addView(context.label(sub, 12f, color = 0xFF6B778C.toInt()))
        col.addView(View(context).apply { minimumHeight = context.dp(10) })
    }

    private fun connect(context: Context, c: AppContainer, actions: UiActions): View {
        val page = context.page()
        val col = page.column()
        header(context, "连接卡尺", "扫描 GL-BLE# 前缀 · 最多 7 台 · 第 8 台拒绝", col)

        val found = mutableListOf<ScannedDevice>()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val handler = Handler(Looper.getMainLooper())
        val slots = context.label("在线 0 / 7")

        col.addView(context.actionButton("扫描设备") {
            found.clear()
            list.removeAllViews()
            c.startScan { device ->
                handler.post {
                    if (found.any { it.mac == device.mac }) return@post
                    found.add(device)
                    val title = "${device.name} · ${device.mac.takeLast(4)} · ${device.rssi}dBm"
                    list.addView(context.actionButton(title) {
                        if (!c.connect(device.mac)) {
                            actions.toast("通道已满，未连接 ${device.mac.takeLast(4)}")
                        }
                    })
                }
            }
        })
        col.addView(context.actionButton("一键连全部（最多 7 台）") {
            found.take(ChannelPalette.MAX_CHANNELS).forEach { c.connect(it.mac) }
            slots.text = "在线 ${c.onlineCount()} / 7"
        })
        col.addView(context.actionButton("停止扫描") { c.stopScan() })
        col.addView(context.label("附近设备", 13f, true))
        col.addView(list)
        col.addView(context.label("通道槽位 CH1–CH7", 13f, true))
        for (i in 1..ChannelPalette.MAX_CHANNELS) {
            col.addView(context.label("CH$i · ${c.channelSlots()[i] ?: "空"}", 12f))
        }
        col.addView(slots)
        col.addView(context.actionButton("权限被拒绝？打开系统设置") { actions.openSystemSettings() })
        return page
    }

    private fun capture(context: Context, c: AppContainer, actions: UiActions): View {
        val page = context.page()
        val col = page.column()
        header(context, "实时检测 · 记录", "两列表格：时间 + 数值；通道用行内色标，判定用左侧色条", col)

        val reading = context.label("— mm", 40f, true, 0xFF1668E3.toInt())
        val count = context.label("已记录 0 条")
        val adapter = MeasurementAdapter { item ->
            actions.toast("CH${item.channelNo} · ${Units.formatMm(item.valueUm, 2)}mm · ${item.source} · ${item.note}")
        }
        val table = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, context.dp(360)
            )
        }
        col.addView(reading)
        col.addView(count)
        col.addView(context.label("采集控制（对 CH1 生效）", 13f, true))
        val buttons = context.horizontal()
        listOf(
            CaliperCommand.MEAS.name to CaliperCommand.MEAS,
            CaliperCommand.CLR.name to CaliperCommand.CLR,
            "手动模式" to CaliperCommand.MOD_MANUAL,
            "连续模式" to CaliperCommand.MOD_CONTINUOUS
        ).forEach { (text, command) ->
            buttons.addView(context.actionButton(text) { c.sendCommand(1, command) })
        }
        col.addView(buttons)
        col.addView(context.label("时间 / 数值 (mm)", 12f, true))
        col.addView(table)
        col.addView(context.actionButton("设置") { actions.setPage("P7") })
        col.addView(context.actionButton("导出") { actions.setPage("P6") })

        val job: Job = CoroutineScope(Dispatchers.Main).launch {
            c.measurements().collect { items ->
                adapter.submit(items)
                count.text = "已记录 ${items.size} 条"
                items.firstOrNull()?.let {
                    reading.text = "${Units.formatMm(it.valueUm, 2)} mm"
                }
            }
        }
        page.tag = job
        return page
    }

    private fun channels(context: Context, c: AppContainer, actions: UiActions): View {
        val page = context.page()
        val col = page.column()
        header(context, "多通道", "固定 7 色编码 · 单路断连不影响其他通道", col)
        val slots = c.channelSlots()
        for (i in 1..ChannelPalette.MAX_CHANNELS) {
            val card = context.card()
            val color = View(context).apply {
                setBackgroundColor(ChannelPalette.colorOf(i))
                minimumHeight = context.dp(8)
            }
            card.addView(color)
            card.addView(context.label("CH$i", 18f, true, ChannelPalette.colorOf(i)))
            card.addView(context.label(slots[i]?.let { "已连接 · $it" } ?: "未连接", 12f))
            col.addView(card)
            col.addView(View(context).apply { minimumHeight = context.dp(10) })
        }
        col.addView(context.actionButton("刷新状态") { actions.setPage("P3") })
        return page
    }

    private fun templates(context: Context, c: AppContainer, actions: UiActions): View {
        val page = context.page()
        val col = page.column()
        header(context, "表格模板", "V1：内置默认模板 + CSV 表头模板；xlsx 完整模板在 V2", col)
        val card = context.card()
        card.addView(context.label("内置默认模板", 15f, true))
        card.addView(context.label(TEMPLATE_COLUMNS, 12f))
        card.addView(context.label("UTF-8 BOM · 支持中文别名 · 未识别列整列跳过 · 历史 CH8–CH10 归入历史通道", 11f))
        col.addView(card)
        col.addView(context.actionButton("导入 CSV（模板 / 数据）") { actions.importCsv() })
        col.addView(context.actionButton("导出诊断日志") { actions.exportDiagnostics() })
        return page
    }

    private fun analysis(context: Context, c: AppContainer, actions: UiActions): View {
        val page = context.page()
        val col = page.column()
        header(context, "数据分析", "折线 / 柱状 · 公差参考线 · 按通道色着色", col)
        val chart = ChartCanvasView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, context.dp(280)
            )
        }
        col.addView(chart)
        val row = context.horizontal()
        row.addView(context.actionButton("折线图") { chart.mode = ChartCanvasView.Mode.LINE; chart.invalidate() })
        row.addView(context.actionButton("柱状图") { chart.mode = ChartCanvasView.Mode.BAR; chart.invalidate() })
        row.addView(context.actionButton("导出 PNG") { actions.exportPng(chart.exportBitmap()) })
        col.addView(row)
        val job: Job = CoroutineScope(Dispatchers.Main).launch {
            c.measurements().collect { items ->
                chart.points = items.reversed().map { it.channelNo to it.valueUm }
                chart.lowerUm = c.settings.lowerLimitUm
                chart.upperUm = c.settings.upperLimitUm
                chart.invalidate()
            }
        }
        page.tag = job
        return page
    }

    private fun export(context: Context, c: AppContainer, actions: UiActions): View {
        val page = context.page()
        val col = page.column()
        header(context, "导出到本地", "CSV 与 PNG 默认双勾 · SAF 保存 · 不覆盖同名文件", col)
        col.addView(context.actionButton("合并 CSV（含 CHANNEL 列）") { actions.exportCsv(null) })
        for (i in 1..ChannelPalette.MAX_CHANNELS) {
            col.addView(context.actionButton("只导 CH$i CSV") { actions.exportCsv(i) })
        }
        col.addView(context.actionButton("导入 CSV") { actions.importCsv() })
        col.addView(context.actionButton("导出诊断日志") { actions.exportDiagnostics() })
        col.addView(context.label("提示：只导 PNG 会把原始数据锁在 App 里；升级前请先导出 CSV 备份。", 12f, color = 0xFFA45F06.toInt()))
        return page
    }

    private fun settings(context: Context, c: AppContainer, actions: UiActions): View {
        val page = context.page()
        val col = page.column()
        header(context, "设置", "测量与判定 · 多通道 · 蓝牙配置", col)

        val measure = context.card()
        measure.addView(context.label("测量与判定", 13f, true))
        val decimals = context.input("小数位数", c.settings.decimals.toString())
        measure.addView(decimals)
        val lower = context.input("公差下限 (mm)", c.settings.lowerLimitUm?.let { Units.formatMm(it, 2) } ?: "")
        val upper = context.input("公差上限 (mm)", c.settings.upperLimitUm?.let { Units.formatMm(it, 2) } ?: "")
        measure.addView(lower)
        measure.addView(upper)
        measure.addView(context.actionButton("保存测量设置") {
            decimals.text.toString().toIntOrNull()?.let { c.settings.decimals = it }
            c.settings.lowerLimitUm = lower.text.toString().toDoubleOrNull()?.let { (it * 1000).toLong() }
            c.settings.upperLimitUm = upper.text.toString().toDoubleOrNull()?.let { (it * 1000).toLong() }
            actions.toast("测量设置已保存")
        })
        col.addView(measure)
        col.addView(context.label("最大并发通道数：7（只读，第 8 台拒绝）", 12f))

        // ---- 蓝牙配置（设备端参数）----
        val card = context.card()
        card.addView(context.label("蓝牙配置（设备端参数）", 13f, true))
        card.addView(
            context.label(
                "设备端参数，App 不下发。需厂商配置指令集或 PC 配置工具设置；本页仅按设备登记。",
                11f,
                color = 0xFFA45F06.toInt()
            )
        )
        val target = Spinner(context).apply {
            adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_item,
                (1..ChannelPalette.MAX_CHANNELS).map { "CH$it" }
            )
        }
        card.addView(target)
        val nameInput = context.input("GL-BLE#xxxx（≤16 字符）", "GL-BLE#0187")
        card.addView(nameInput)
        val namRow = context.horizontal()
        namRow.addView(context.actionButton("下发改名（NAM）") {
            val channelNo = target.selectedItemPosition + 1
            val ok = c.applyNam(channelNo, nameInput.text.toString())
            actions.toast(
                if (ok) "改名指令已写入 CH$channelNo，等待设备回包"
                else "名称非法（须保留 GL-BLE# 前缀、总长 8–16）或该通道未连接"
            )
        })
        namRow.addView(context.actionButton("读取当前名称") {
            actions.toast("NAM 查询指令格式有歧义，M0 抓包确认前 V1 不实现实链路")
        })
        card.addView(namRow)

        val autoEmit = context.input("自动发射时长（秒）· 仅登记", "30")
        val sleepSwitch = Switch(context).apply { text = "蓝牙自动休眠 · 仅登记" }
        val sleepMinutes = context.input("休眠时间（分钟）· 仅登记", "5")
        val terminator = Spinner(context).apply {
            adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_item,
                listOf("回车 CR / LF", "Tab")
            )
        }
        card.addView(autoEmit)
        card.addView(sleepSwitch)
        card.addView(sleepMinutes)
        card.addView(context.label("结束符（卡尺 HID 键盘模式）· 仅登记", 12f))
        card.addView(terminator)

        val hidInput = EditText(context).apply { hint = "HID 键盘录入：接收或粘贴数值" }
        card.addView(hidInput)
        val hidRow = context.horizontal()
        hidRow.addView(context.actionButton("注入示例 HID 输入") {
            hidInput.setText("155.15\t155.08\t154.96\t")
        })
        hidRow.addView(context.actionButton("HID 录入落库") {
            val channelNo = target.selectedItemPosition + 1
            val mac = c.settings.lastMac ?: "HID"
            val accepted = c.hid.submit(channelNo, mac, hidInput.text.toString())
            actions.toast("HID 录入 $accepted 条，走同一条解析 → 去抖 → 落库链路")
        })
        card.addView(hidRow)
        card.addView(context.actionButton("保存登记（不下发到卡尺）") {
            val channelNo = target.selectedItemPosition + 1
            val mac = c.settings.lastMac ?: "UNKNOWN"
            c.settings.terminator = if (terminator.selectedItemPosition == 1) "TAB" else "CR"
            c.deviceConfig.saveRegistration(
                mac = mac,
                name = nameInput.text.toString(),
                channelNo = channelNo,
                customName = nameInput.text.toString(),
                autoEmitSeconds = autoEmit.text.toString().toIntOrNull(),
                sleepEnabled = sleepSwitch.isChecked,
                sleepMinutes = sleepMinutes.text.toString().toIntOrNull(),
                terminator = c.settings.terminator
            )
            actions.toast("仅登记设备端参数，未下发到卡尺")
        })
        col.addView(card)
        col.addView(context.actionButton("恢复默认") {
            c.settings.decimals = 4
            c.settings.lowerLimitUm = null
            c.settings.upperLimitUm = null
            c.settings.terminator = "CR"
            actions.toast("已恢复默认设置")
        })
        return page
    }
}
