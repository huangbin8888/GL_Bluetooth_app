package com.guanglu.caliper.ui.pages

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.device.CaliperCommand
import com.guanglu.caliper.core.database.entity.Measurement
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.Units
import com.guanglu.caliper.core.model.valueToneOf
import com.guanglu.caliper.ui.*
import com.guanglu.caliper.ui.chart.ChartCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

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
    private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

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

    private fun screen(context: Context): ScrollView {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        return ScrollView(context).apply { addView(column) }
    }

    private fun ScrollView.col(): LinearLayout = getChildAt(0) as LinearLayout

    private fun even(context: Context, weight: Float = 1f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)

    private fun liveTable(context: Context): LiveTable = context.run {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = webDrawable(Web.CARD, dp(Web.RADIUS).toFloat(), Web.LINE, 1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(Web.CARD_GAP) }
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Web.CARD)
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        val caption = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        caption.addView(webBold("实时检测 / 检测记录", 12.5f))
        caption.addView(webTiny("同一界面 · 表格框内上下拖动查看"))
        head.addView(caption)
        val scope = webChip("全部通道", WebTone.BLUE)
        head.addView(scope)
        val clearButton = webButton("清除数据", danger = true, small = true) {}
        clearButton.setPadding(dp(8), dp(4), dp(8), dp(4))
        head.addView(clearButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) })
        card.addView(head)
        card.addView(View(context).apply {
            setBackgroundColor(Web.LINE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
        card.addView(webTableHeader())
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val frame = TableFrame(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(tableFrameHeightDp(0))
            )
            background = webDrawable(Web.CARD, dp(8).toFloat(), Web.LINE, 1)
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            isFillViewport = true
        }
        frame.addView(body, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(frame)
        val footer = webTiny("只保留 时间 + 数值 两列 · 通道用行内色标、判定用左侧色条、备注点行查看")
        footer.setBackgroundColor(Web.TABLE_HEAD)
        footer.setPadding(dp(10), dp(8), dp(10), dp(8))
        card.addView(footer)
        LiveTable(card, body, scope, frame, clearButton)
    }

    private fun bindClearButton(c: AppContainer, actions: UiActions, table: LiveTable) {
        val confirm = ClearConfirm()
        val resetLabel = Runnable {
            confirm.reset()
            table.clearButton.text = "清除数据"
        }
        table.clearButton.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.removeCallbacks(resetLabel)
            }
        })
        table.clearButton.setOnClickListener {
            if (confirm.tap(System.currentTimeMillis())) {
                table.clearButton.removeCallbacks(resetLabel)
                table.clearButton.text = "清除数据"
                c.clearMeasurements { count -> actions.toast("已清除 $count 条检测记录") }
                table.frame.scrollTo(0, 0)
            } else {
                table.clearButton.text = "再点一次确认"
                actions.toast("3 秒内再点一次「清除数据」将删除全部检测记录（不可恢复）")
                table.clearButton.removeCallbacks(resetLabel)
                table.clearButton.postDelayed(resetLabel, CLEAR_CONFIRM_WINDOW_MS)
            }
        }
    }

    private fun channelChips(context: Context, focus: Int, onPick: (Int) -> Unit): List<View> = context.run {
        val chips = mutableListOf<View>()
        chips.add(webChChip("全部 7 路", 0, focus == 0) { onPick(0) })
        for (i in 1..ChannelPalette.MAX_CHANNELS) {
            chips.add(webChChip("CH$i", ChannelPalette.colorOf(i), focus == i) { onPick(i) })
        }
        chips
    }

    private fun connect(context: Context, c: AppContainer, actions: UiActions): View = context.run {
        val page = screen(context)
        val col = page.col()
        val state = webBold("已连接 0 / 7 通道", 13f)
        val note = webTiny(
            if (c.settings.autoConnect) "启动时自动重连上次连接过的卡尺" else "自动重连已关闭（设置 · 采集行为可开启）"
        )
        col.addView(connectHeader(context, state, note))
        CoroutineScope(Dispatchers.Main).launch {
            val registered = c.deviceConfig.recentDevices(ChannelPalette.MAX_CHANNELS)
            if (registered.isNotEmpty()) {
                note.text = "已登记 ${registered.size} 台卡尺" +
                    if (c.settings.autoConnect) " · 启动时自动重连" else " · 自动重连已关闭"
            }
        }
        col.addView(webBanner("长按卡尺红色 data 键 3 秒，液晶闪 OK 后开始扫描。\n最多同时连接 7 台，第 8 台扫描到也不连接并提示通道已满。", WebTone.BLUE))
        col.addView(webBanner("蓝牙 / 定位权限未确认，或系统定位总开关关闭时扫描不到设备。", WebTone.WARN))
        col.addView(webBtnRow(webButton("去系统设置", small = true) { actions.openSystemSettings() }))
        col.addView(webSection("附近设备 · 通道槽位 1–7"))

        val listCard = webCard()
        listCard.addView(webCardTitle("附近设备"))
        val hint = webTiny("尚未扫描")
        listCard.addView(hint)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(list)
        col.addView(listCard)
        val slotLabels = mutableListOf<TextView>()
        col.addView(channelSlotsCard(context, slotLabels))

        val session = ScanSession(c, actions, list, hint) { refreshConnect(c, state, slotLabels) }
        stopScanOnDetach(page) { session.stop() }
        col.addView(webBtnRow(
            webButton("扫描设备", primary = true) { session.start() },
            webButton("全部断开", danger = true) {
                val count = c.disconnectAll()
                session.reset()
                val message = if (count == 0) "当前没有已连接或在连接的通道" else "已断开 $count 个通道"
                hint.text = message
                actions.toast(message)
                refreshConnect(c, state, slotLabels)
            }
        ))
        col.addView(webButton("连接全部 7 台") {
            val targets = session.devices().take(ChannelPalette.MAX_CHANNELS)
            if (targets.isEmpty()) {
                actions.toast("请先点『扫描设备』并等待列表出现卡尺")
                return@webButton
            }
            targets.forEach { c.connect(it.mac) }
            actions.toast("正在连接 ${targets.size} 台卡尺")
            refreshConnect(c, state, slotLabels)
        })
        col.addView(connectFooter(context))
        refreshConnect(c, state, slotLabels)
        page
    }

    private fun refreshConnect(c: AppContainer, state: TextView, labels: List<TextView>) {
        val slots = c.channelSlots()
        state.text = "已连接 ${c.onlineCount()} / ${ChannelPalette.MAX_CHANNELS} 通道"
        labels.forEachIndexed { index, label ->
            val mac = slots[index + 1]
            label.text = if (mac.isNullOrEmpty()) "CH${index + 1} · 空闲" else "CH${index + 1} · $mac"
        }
    }

    private fun capture(context: Context, c: AppContainer, actions: UiActions): View = context.run {
        val page = screen(context)
        val col = page.col()
        val readingCard = webCard()
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(webBold("当前读数 · CH1", 12f, Web.SUB), even(context))
        head.addView(webChip("手动 · DAT0", WebTone.BLUE))
        readingCard.addView(head)
        val reading = webReading("—")
        readingCard.addView(reading)
        readingCard.addView(webFrameLine("原始帧 DAT0:m+155.15\n回包 DAT0:OK · 完整帧解析成功后才回"))
        col.addView(readingCard)

        col.addView(webSection("通道筛选（表格 / 图表同步）"))
        val chipHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(chipHost)
        val table = liveTable(context)
        col.addView(table.card)
        bindClearButton(c, actions, table)

        val total = webStat("0", "总记录")
        val ok = webStat("0", "合格")
        val bad = webStat("0", "超差")
        col.addView(webStatStrip(total, ok, bad))

        val count = webBold("0 条", 18f)
        col.addView(captureBatchCard(context, c, count))
        captureControls(context, c, actions).forEach { col.addView(it) }

        var focus = 0
        var latest: List<Measurement> = emptyList()
        fun renderTable() {
            val shown = if (focus == 0) latest else latest.filter { it.channelNo == focus }
            table.scope.text = if (focus == 0) "全部通道" else "CH$focus"
            renderTableRows(context, table, shown, c.settings.decimals, actions)
        }
        fun renderChips() {
            chipHost.removeAllViews()
            chipHost.addView(webChipFlow(channelChips(context, focus) { picked ->
                focus = picked
                renderChips()
                renderTable()
            }))
        }
        renderChips()
        renderTable()
        table.frame.onInteractionEnd = {
            if (table.pendingRender) {
                table.pendingRender = false
                renderTable()
            }
        }
        val job: Job = CoroutineScope(Dispatchers.Main).launch {
            c.measurements().collect { items ->
                latest = items
                renderTable()
                count.text = "${items.size} 条"
                (total.getChildAt(0) as TextView).text = "${items.size}"
                (ok.getChildAt(0) as TextView).text = "${items.count { it.verdict == "PASS" }}"
                (bad.getChildAt(0) as TextView).text = "${items.count { it.verdict == "FAIL" }}"
                val latestItem = items.firstOrNull()
                val latestValue = latestItem?.let {
                    Units.formatMm(it.valueUm10, c.settings.decimals)
                } ?: "—"
                setReading(reading, latestValue)
                reading.setTextColor(valueTextColor(valueToneOf(latestItem?.verdict.orEmpty())))
            }
        }
        page.tag = job
        page
    }

    private fun renderTableRows(
        context: Context,
        table: LiveTable,
        shown: List<Measurement>,
        decimals: Int,
        actions: UiActions
    ) {
        if (!shouldRenderRows(table.frame.interacting)) {
            table.pendingRender = true
            return
        }
        table.pendingRender = false
        val anchor = if (shown.isEmpty()) 0 else table.frame.scrollY
        table.body.removeAllViews()
        if (shown.isEmpty()) {
            table.body.addView(context.webTableEmpty("该通道暂无数据"))
        } else {
            shown.take(visibleRows(shown.size)).forEach { item ->
                table.body.addView(context.webTableRow(item, CLOCK, decimals) {
                    val detail = "CH${item.channelNo} · ${Units.formatMm(item.valueUm10, decimals)}mm · " +
                        "${item.source} · ${item.note}"
                    actions.toast(detail)
                })
                table.body.addView(context.webDivider())
            }
        }
        table.restoreScroll?.let { table.frame.removeCallbacks(it) }
        val restore = Runnable { table.frame.scrollTo(0, anchor) }
        table.restoreScroll = restore
        table.frame.post(restore)
    }

    private fun captureBatchCard(
        context: Context,
        c: AppContainer,
        count: TextView
    ): LinearLayout = context.run {
        val card = webCard(true)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(webTiny("当前批次"))
        left.addView(webBold("${c.settings.activeBatchId}".take(8), 13f))
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        right.addView(webTiny("本次已记"))
        right.addView(count)
        row.addView(left)
        row.addView(right)
        card.addView(row)
        card.addView(webChipFlow(listOf(
            webChip("7 通道在线", WebTone.OK),
            webChip("采集时屏幕常亮"),
            webChip("按通道独立去抖 ≤0.01mm / 300ms")
        )))
        card
    }

    private fun captureControls(context: Context, c: AppContainer, actions: UiActions): List<View> = context.run {
        val seg = webSeg(listOf("mm", "inch"), 0) { index ->
            actions.toast(
                if (index == 0) "内部统一存 0.1 微米整数，mm 显示 4 位小数无损"
                else "内部统一存 0.1 微米整数，inch 显示放 V2"
            )
        }
        val targetHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val targetHint = webTiny("命令目标：无在线通道")
        val modeButton = webButton("切换为连续") {}
        var selected = 0

        fun onlineChannels(): List<Int> = c.channelSlots().keys.sorted()

        fun renderTarget() {
            val online = onlineChannels()
            if (selected !in online) selected = online.firstOrNull() ?: 0
            targetHost.removeAllViews()
            val chips: List<View> = if (online.isEmpty()) {
                listOf(webChip("无在线通道", WebTone.WARN))
            } else {
                online.map { no ->
                    webChChip("CH$no", ChannelPalette.colorOf(no), no == selected) {
                        selected = no
                        renderTarget()
                    }
                }
            }
            targetHost.addView(webChipFlow(chips))
            targetHint.text = if (selected == 0) {
                "命令目标：无在线通道，MEAS / CLR / 模式均不下发"
            } else {
                "命令目标：CH$selected（${c.channelSlots()[selected]?.takeLast(4) ?: "—"}）· " +
                    if (c.isContinuous(selected)) "连续" else "手动"
            }
            modeButton.text = if (c.isContinuous(selected)) "切换为手动" else "切换为连续"
        }

        fun send(command: CaliperCommand, label: String): Boolean {
            val online = onlineChannels()
            if (selected !in online) {
                selected = online.firstOrNull() ?: 0
                renderTarget()
            }
            if (selected == 0) {
                actions.toast("当前无在线通道，请先到「连接」页连接卡尺")
                return false
            }
            val ok = c.sendCommand(selected, command)
            if (!ok) actions.toast("CH$selected 未连接卡尺，$label 未下发（只影响该通道）")
            return ok
        }

        renderTarget()
        modeButton.setOnClickListener {
            val channelNo = selected
            val wantContinuous = !c.isContinuous(channelNo)
            val target = if (wantContinuous) CaliperCommand.MOD_CONTINUOUS else CaliperCommand.MOD_MANUAL
            if (send(target, "模式切换")) {
                c.setContinuous(channelNo, wantContinuous)
                renderTarget()
                actions.toast("CH$channelNo 已切为${if (wantContinuous) "连续" else "手动"}，其他通道不受影响")
            }
        }
        listOf(
            seg,
            targetHint,
            targetHost,
            webButton("记录一条（当前通道）", primary = true) {
                send(CaliperCommand.MEAS, "记录一条")
            },
            webBtnRow(
                webButton("发一次采集 MEAS") { send(CaliperCommand.MEAS, "采集 MEAS") },
                webButton("清零 CLR") { send(CaliperCommand.CLR, "清零 CLR") }
            ),
            webBtnRow(modeButton, webButton("设置") { actions.setPage("P7") }),
            webBtnRow(
                webButton("多通道 7 屏") { actions.setPage("P3") },
                webButton("查看图表") { actions.setPage("P5") }
            ),
            webBtnRow(
                webButton("模板") { actions.setPage("P4") },
                webButton("导出") { actions.setPage("P6") }
            )
        )
    }

    private fun channels(context: Context, c: AppContainer, actions: UiActions): View = context.run {
        val page = screen(context)
        val col = page.col()
        col.addView(webBanner(
            "最多 7 通道并发：一台手机同时连接 7 台 GL-BLE# 卡尺。\n每路独立 notify 订阅、帧缓冲、去重与落库；跨通道不去重。",
            WebTone.BLUE
        ))
        val occupy = webCard(true)
        val occupyRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val countBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        countBox.addView(webTiny("通道占用"))
        val count = webBold("${c.onlineCount()} / 7 在线", 18f)
        countBox.addView(count)
        occupyRow.addView(countBox)
        occupyRow.addView(webChip("上限 7 台", WebTone.BLUE))
        occupy.addView(occupyRow)
        val legend = mutableListOf<View>()
        for (i in 1..ChannelPalette.MAX_CHANNELS) {
            legend.add(webChMark(i))
        }
        occupy.addView(webChipFlow(legend))
        col.addView(occupy)

        col.addView(webSection("实时读数 · 7 通道卡片墙"))
        val slots = c.channelSlots()
        val cards = mutableListOf<View>()
        val values = mutableMapOf<Int, TextView>()
        for (i in 1..ChannelPalette.MAX_CHANNELS) {
            val mac = slots[i]
            val card = webChCard(i, "— mm", mac ?: "未连接", mac != null)
            val box = card.getChildAt(1) as LinearLayout
            values[i] = box.getChildAt(1) as TextView
            cards.add(card)
        }
        col.addView(webChGrid(*cards.toTypedArray()))

        val plan = webCard(true)
        plan.addView(webCardTitle("多通道显示方案（避免 7 路数据糊在一起）"))
        plan.addView(webTiny(
            "① 固定 7 色编码：连接列表 / 实时表格 / 通道卡 / 图表点 / 导出文件全局一致；\n" +
                "② 实时检测与记录合并一屏，表格只留「时间 + 数值」两列；\n" +
                "③ 顶部通道 chips 一键筛选单通道；④ 7 路读数用卡片墙平铺；\n" +
                "⑤ 图表可叠加或单通道查看；导出可合并或按通道分文件；\n" +
                "⑥ 单通道断连只灰掉该卡，其余 6 路继续采集。"
        ))
        col.addView(plan)
        col.addView(webBtnRow(
            webButton("刷新状态") { actions.setPage("P3") },
            webButton("全部断开") {
                val count = c.disconnectAll()
                actions.toast(if (count == 0) "当前没有已连接或在连接的通道" else "已断开 $count 个通道")
                actions.setPage("P3")
            }
        ))
        col.addView(webBtnRow(
            webButton("回实时检测") { actions.setPage("P2") },
            webButton("看叠加图表") { actions.setPage("P5") }
        ))
        col.addView(webTiny("第 8 台设备扫描到也不连接并提示上限；7 路 GATT 命令串行队列，重连后各自重新订阅 notify。"))

        val job: Job = CoroutineScope(Dispatchers.Main).launch {
            c.measurements().collect { items ->
                count.text = "${c.onlineCount()} / 7 在线"
                for (i in 1..ChannelPalette.MAX_CHANNELS) {
                    val latest = items.firstOrNull { it.channelNo == i }
                    values[i]?.text = latest?.let { Units.formatMm(it.valueUm10) } ?: "— mm"
                }
            }
        }
        page.tag = job
        page
    }

    private fun devRow(context: Context, vararg cells: String): LinearLayout =
        LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }.also { row ->
            cells.forEach { cell ->
                row.addView(
                    context.webTiny(cell, 11f),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
        }

    private fun templates(context: Context, c: AppContainer, actions: UiActions): View = context.run {
        val page = screen(context)
        val col = page.col()
        col.addView(webBanner("V1 支持 CSV 表头模板；自定义 xlsx 模板（合并单元格、公式、样式）延到 V2。", WebTone.BLUE))
        col.addView(webSection("模板列表"))
        val builtIn = webCard(true)
        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val box1 = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box1.addView(webBold("内置默认模板", 13f))
        box1.addView(webTiny("15 列 · 稳定大写 key + 中文别名"))
        row1.addView(box1)
        row1.addView(webChip("使用中", WebTone.OK))
        builtIn.addView(row1)
        col.addView(builtIn)

        val imported = webCard(true)
        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val box2 = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box2.addView(webBold("gl_caliper_default.csv", 13f))
        box2.addView(webTiny("已导入 · UTF-8 BOM"))
        row2.addView(box2)
        row2.addView(webChip("已导入"))
        imported.addView(row2)
        col.addView(imported)

        col.addView(webSection("字段预览（前 8 列 / 共 15 列）"))
        val tableCard = webCard()
        tableCard.addView(devRow(context, "列名 key", "中文别名", "类型", "必填"))
        listOf(
            arrayOf("DATE", "日期", "yyyy-MM-dd", "是"),
            arrayOf("TIME", "时间", "HH:mm:ss", "否"),
            arrayOf("SEQ", "序号", "Int", "否"),
            arrayOf("BATCH", "批次", "Text", "否"),
            arrayOf("ITEM", "检验项目", "Text", "否"),
            arrayOf("NOMINAL", "公称值", "mm", "否"),
            arrayOf("LOWER_TOL", "下偏差", "mm", "否"),
            arrayOf("UPPER_TOL", "上偏差", "mm", "否")
        ).forEach { cells -> tableCard.addView(devRow(context, *cells)) }
        col.addView(tableCard)
        col.addView(webBanner(
            "识别 15 列 · 中文别名命中 6 列 · 未识别列跳过并记入导入报告 · 首列剥离 BOM。\n导出的 CSV 增加 CHANNEL 列。",
            WebTone.WARN
        ))
        col.addView(webBtnRow(
            webButton("导入 CSV", primary = true) { actions.importCsv() },
            webButton("导出诊断日志") { actions.exportDiagnostics() }
        ))
        col.addView(webTiny(TEMPLATE_COLUMNS))
        page
    }

    private fun analysis(context: Context, c: AppContainer, actions: UiActions): View = context.run {
        val page = screen(context)
        val col = page.col()
        val chart = ChartCanvasView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(280)
            )
        }
        col.addView(webSeg(listOf("折线图", "柱状图"), 0) { index ->
            chart.mode = if (index == 0) ChartCanvasView.Mode.LINE else ChartCanvasView.Mode.BAR
            chart.invalidate()
        })
        col.addView(webSection("通道筛选 / 叠加"))
        var focus = 0
        val chipHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(chipHost)
        col.addView(webChipFlow(listOf(
            webChip("批次：本地", WebTone.BLUE),
            webChip("点按通道着色 · 超差描红"),
            webChip("折线 / 柱状可切换")
        )))
        val chartCard = webCard()
        chartCard.addView(chart)
        col.addView(chartCard)

        val total = webStat("0", "样本数")
        val average = webStat("—", "平均 mm")
        val minimum = webStat("—", "最小 mm")
        val maximum = webStat("—", "最大 mm")
        col.addView(webChGrid(total, average, minimum, maximum))
        col.addView(webBtnRow(
            webButton("导出这张图", primary = true) { actions.exportPng(chart.exportBitmap()) },
            webButton("返回实时检测") { actions.setPage("P2") }
        ))

        var latest: List<Measurement> = emptyList()
        fun renderChart() {
            val shown = if (focus == 0) latest else latest.filter { it.channelNo == focus }
            chart.points = shown.reversed().map { it.channelNo to it.valueUm10 }
            chart.lowerUm10 = c.settings.lowerLimitUm10
            chart.upperUm10 = c.settings.upperLimitUm10
            chart.invalidate()
            (total.getChildAt(0) as TextView).text = "${shown.size}"
            if (shown.isNotEmpty()) {
                val values = shown.map { it.valueUm10 }
                (average.getChildAt(0) as TextView).text = Units.formatMm(values.average().toLong())
                (minimum.getChildAt(0) as TextView).text = Units.formatMm(values.minOrNull() ?: 0L)
                (maximum.getChildAt(0) as TextView).text = Units.formatMm(values.maxOrNull() ?: 0L)
            }
        }
        fun renderChips() {
            chipHost.removeAllViews()
            chipHost.addView(webChipFlow(channelChips(context, focus) { picked ->
                focus = picked
                renderChips()
                renderChart()
            }))
        }
        renderChips()
        renderChart()
        val job: Job = CoroutineScope(Dispatchers.Main).launch {
            c.measurements().collect { items ->
                latest = items
                renderChart()
            }
        }
        page.tag = job
        page
    }

    private fun export(context: Context, c: AppContainer, actions: UiActions): View = context.run {
        val page = screen(context)
        val col = page.col()
        val contentCard = webCard()
        contentCard.addView(webCardTitle("导出内容"))
        contentCard.addView(webCheck("图表 PNG", "在数据分析页导出当前图表", false) { })
        contentCard.addView(webCheck("数据 CSV", "默认导出 · 只导图片会把数据锁在 App 里", true) { })
        col.addView(contentCard)

        val rangeCard = webCard()
        rangeCard.addView(webCardTitle("多通道导出范围（新增）"))
        var split = false
        rangeCard.addView(webCheck("合并为一个 CSV", "含 CHANNEL 通道列，7 路按时间混排", true) { })
        rangeCard.addView(webCheck("按通道分文件", "每个在线通道一个 CSV，文件名带 _CHn", false) { on ->
            split = on
        })
        col.addView(rangeCard)

        col.addView(webSection("文件名预览"))
        col.addView(webFileLine("B20261112-01_ALL.csv"))
        col.addView(webFileLine("B20261112-01_ALL.png"))
        col.addView(webBanner(
            "保存到手机「下载 / Documents」，走系统文件选择器 SAF；不申请宽泛存储权限。\nCSV 为 UTF-8 BOM，可重新导入；同名文件不覆盖。",
            WebTone.BLUE
        ))
        col.addView(webButton("保存", primary = true) {
            if (split) actions.toast("请在下方按通道分别导出 CSV") else actions.exportCsv(null)
        })
        for (i in 1..ChannelPalette.MAX_CHANNELS step 2) {
            val first = webButton("只导 CH$i CSV", small = true) { actions.exportCsv(i) }
            if (i + 1 <= ChannelPalette.MAX_CHANNELS) {
                col.addView(webBtnRow(
                    first,
                    webButton("只导 CH${i + 1} CSV", small = true) { actions.exportCsv(i + 1) }
                ))
            } else {
                col.addView(webBtnRow(first))
            }
        }
        col.addView(webBtnRow(
            webButton("导入 CSV") { actions.importCsv() },
            webButton("完成") { actions.setPage("P2") }
        ))
        page
    }

    private fun settingsMeasureCard(context: Context, c: AppContainer, actions: UiActions): LinearLayout = context.run {
        val card = webCard()
        card.addView(webCardTitle("测量与判定"))
        val decimals = context.webEdit(c.settings.decimals.toString())
        val lower = context.webEdit(c.settings.lowerLimitUm10?.let { Units.formatMm(it) } ?: "")
        val upper = context.webEdit(c.settings.upperLimitUm10?.let { Units.formatMm(it) } ?: "")
        card.addView(webField("小数位数（0–4）", "显示与导出统一，默认 4 位不四舍五入", decimals))
        card.addView(webField("公差下限 (mm)", "设置上下公差后才会判定超差；超差数值变红并蜂鸣，未设为「未判定」", lower))
        card.addView(webField("公差上限 (mm)", "", upper))
        card.addView(webButton("保存测量设置", small = true) {
            decimals.text.toString().toIntOrNull()?.let { c.settings.decimals = it }
            c.settings.lowerLimitUm10 = Units.toUm10(lower.text.toString(), 'm')
            c.settings.upperLimitUm10 = Units.toUm10(upper.text.toString(), 'm')
            actions.toast("测量设置已保存")
        })
        card
    }

    private fun settings(context: Context, c: AppContainer, actions: UiActions): View = context.run {
        val page = screen(context)
        val col = page.col()
        col.addView(settingsMeasureCard(context, c, actions))

        val multi = webCard()
        multi.addView(webCardTitle("多通道（新增）"))
        multi.addView(webField("最大并发通道数", "上限 7 台，第 8 台拒绝连接", webBold("7（只读）", 12.5f)))
        multi.addView(webField("通道色标", "CH1–CH7 固定配色，全 App 一致", webChip("固定 7 色", WebTone.BLUE)))
        multi.addView(webField("通道命名 / 改色", "V2", webChip("V2")))
        col.addView(multi)

        col.addView(settingsBluetoothCard(context, c, actions))

        val behavior = webCard()
        behavior.addView(webCardTitle("采集行为"))
        behavior.addView(webField("采集页屏幕常亮", "息屏断连会永久丢数", webSwitch(true)))
        val autoSwitch = webSwitch(c.settings.autoConnect)
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            c.settings.autoConnect = checked
            actions.toast(if (checked) "已开启自动重连：下次启动自动连接" else "已关闭自动重连")
        }
        behavior.addView(webField("自动重连上次设备", "启动时自动连接上次连接过的卡尺", autoSwitch))
        behavior.addView(overLimitBeepField(context, c, actions))
        val idleMinutes = context.webEdit(c.settings.idleDisconnectMinutes.toString())
        behavior.addView(webField("空闲自动断连（分钟）", "0 = 不断开；超时无数据由 App 断开通道，不下发设备参数", idleMinutes))
        behavior.addView(webButton("保存断连设置", small = true) {
            c.settings.idleDisconnectMinutes = idleMinutes.text.toString().toIntOrNull() ?: 0
            actions.toast(
                if (c.settings.idleDisconnectMinutes == 0) "空闲自动断连已关闭"
                else "无数据 ${c.settings.idleDisconnectMinutes} 分钟后自动断开通道"
            )
        })
        col.addView(behavior)

        val maintenance = webCard(true)
        maintenance.addView(webCardTitle("维护入口"))
        maintenance.addView(webBtnRow(
            webButton("导出诊断日志", small = true) { actions.exportDiagnostics() },
            webButton("权限引导页", small = true) { actions.openSystemSettings() }
        ))
        col.addView(maintenance)

        col.addView(webBtnRow(
            webButton("保存", primary = true) { actions.toast("设置已保存") },
            webButton("恢复默认") {
                c.settings.decimals = 4
                c.settings.lowerLimitUm10 = null
                c.settings.upperLimitUm10 = null
                c.settings.terminator = "CR"
                c.settings.autoConnect = true
                c.settings.idleDisconnectMinutes = 0
                c.settings.overLimitBeep = true
                actions.toast("已恢复默认设置")
            }
        ))
        col.addView(webTiny("v0.3.0 · 原生 App · 数值以 0.1 微米存储 · 升级前请先导出 CSV 备份").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        page
    }
}
