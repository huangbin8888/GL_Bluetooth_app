package com.guanglu.caliper

import com.guanglu.caliper.core.model.CHANNEL_FILTER_COLUMNS
import com.guanglu.caliper.core.model.CHANNEL_FILTER_MAX_ROWS
import com.guanglu.caliper.core.model.ExportAction
import com.guanglu.caliper.core.model.batchConnectMessage
import com.guanglu.caliper.core.model.channelFilterFits
import com.guanglu.caliper.core.model.channelFilterPlaceholders
import com.guanglu.caliper.core.model.channelFilterRows
import com.guanglu.caliper.core.model.exportAction
import com.guanglu.caliper.core.model.readonlyTagIsDistinct
import com.guanglu.caliper.core.model.safResultMessage
import com.guanglu.caliper.core.model.tableColumnsFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 按钮点击契约：静态核对每个点击入口都有非空执行体与可观测反馈。
 *
 * @author AI-Generated
 */
class ButtonContractTest {

    private data class ButtonSite(val file: String, val line: Int, val label: String, val body: String)

    private val sourceRoot: File = locateSourceRoot()

    /** 允许先创建空闭包、随后在同页显式绑定的按钮。 */
    private val lateBound = mapOf(
        "清除数据" to "clearButton.setOnClickListener",
        "切换为连续" to "modeButton.setOnClickListener"
    )

    private val observableActions = listOf(
        "actions.toast", "actions.setPage", "actions.exportCsv", "actions.importCsv",
        "actions.exportDiagnostics", "actions.exportPng", "actions.openSystemSettings",
        "c.", "session.", "send(", "save(", "saveIdle(", "renderTarget()", "renderChips()",
        "renderTable()", "hidInput.setText", "confirm.tap", "chart.", "onConnected()",
        "nameInput.setText", "refreshChannels()"
    )

    private fun locateSourceRoot(): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val current = dir ?: return@repeat
            candidates += File(current, SOURCE_PATH)
            candidates += File(current, "app/$SOURCE_PATH")
            dir = current.parentFile
        }
        (System.getProperty("java.class.path") ?: "").split(File.pathSeparator).forEach { entry ->
            var parent: File? = File(entry)
            repeat(6) {
                parent?.let { candidates += File(it, SOURCE_PATH) }
                parent = parent?.parentFile
            }
        }
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("未找到源码根目录：按钮契约检查不能跳过")
    }

    private fun read(relativePath: String): String = File(sourceRoot, relativePath).readText(Charsets.UTF_8)

    /** 读取 app/src/main 下的文件，用于校验清单权限等非 java 目录的接线。 */
    private fun readMainFile(relativePath: String): String =
        File(sourceRoot, "../../../../$relativePath").readText(Charsets.UTF_8)

    private fun lambdaBody(text: String, from: Int): String {
        val start = text.indexOf('{', from)
        if (start < 0) return ""
        var depth = 0
        var inString = false
        for (index in start until text.length) {
            val ch = text[index]
            if (ch == '"' && (index == 0 || text[index - 1] != '\\')) inString = !inString
            if (inString) continue
            if (ch == '{') {
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0) return text.substring(start + 1, index)
            }
        }
        return text.substring(start + 1)
    }

    private fun buttonSites(relativePath: String): List<ButtonSite> {
        val text = read(relativePath)
        return BUTTON_PATTERN.findAll(text).map { match ->
            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
            ButtonSite(relativePath, line, match.groupValues[1], lambdaBody(text, match.range.last + 1))
        }.toList()
    }

    private fun allButtonSites(): List<ButtonSite> = BUTTON_FILES.flatMap { buttonSites(it) }

    private fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("缺少函数 $signature", start >= 0)
        val next = text.indexOf("\nfun ", start + signature.length)
        return if (next < 0) text.substring(start) else text.substring(start, next)
    }

    @Test
    fun allButtonsHaveNonEmptyHandlerOrExplicitLateBinding() {
        val sites = allButtonSites()
        assertEquals("按钮调用点数量变化时必须重新核对契约", EXPECTED_BUTTON_SITES, sites.size)
        val empty = sites.filter { it.body.isBlank() }
        assertEquals("出现未绑定的空点击闭包", lateBound.keys, empty.map { it.label }.toSet())
        empty.forEach { site ->
            val binding = lateBound.getValue(site.label)
            assertTrue("${site.file}:${site.line} ${site.label} 缺少后绑定 $binding", read(site.file).contains(binding))
        }
    }

    @Test
    fun everyButtonHandlerHasObservableAction() {
        allButtonSites().filter { it.body.isNotBlank() }.forEach { site ->
            assertTrue(
                "${site.file}:${site.line} ${site.label} 点击后无可观测动作",
                observableActions.any { site.body.contains(it) }
            )
        }
    }

    @Test
    fun earlyReturnsAlwaysExplainWhy() {
        allButtonSites().forEach { site ->
            val returnsEarly = site.body.contains("return@webButton") ||
                site.body.contains("return@setOnClickListener")
            if (returnsEarly) {
                assertTrue(
                    "${site.file}:${site.line} ${site.label} 提前返回但没有提示",
                    site.body.contains("toast")
                )
            }
        }
    }

    @Test
    fun clickableFactoriesBindTheirListener() {
        val webUi = read("ui/WebUi.kt")
        listOf(
            "fun Context.webButton(",
            "fun Context.webChChip(",
            "fun Context.webSeg(",
            "fun Context.webTab(",
            "fun webCheckPair(",
            "fun Context.webTableRow("
        ).forEach { signature ->
            assertTrue(
                "$signature 未绑定点击监听",
                functionBody(webUi, signature).contains("setOnClickListener")
            )
        }
        assertTrue(
            "通道 chip 丢失可点击标记",
            functionBody(webUi, "fun Context.webChChip(").contains("isClickable = true")
        )
    }

    @Test
    fun switchesWriteSettingsAndNotify() {
        listOf("ui/pages/ExportSettingsPages.kt", "ui/pages/SettingsParts.kt").forEach { path ->
            val text = read(path)
            val matches = SWITCH_PATTERN.findAll(text).toList()
            assertTrue("$path 没有开关回调", matches.isNotEmpty())
            matches.forEach { match ->
                val body = lambdaBody(text, match.range.last)
                assertTrue("$path 开关回调未写入设置", body.contains("c.settings."))
                assertTrue("$path 开关回调无可见提示", body.contains("actions.toast"))
            }
        }
    }

    @Test
    fun filePickerNeverReturnsSilently() {
        val activity = read("ui/MainActivity.kt")
        assertTrue("文件选择器取消/空 URI 未给出提示", activity.contains("safResultMessage"))
        assertTrue("文件操作结束未清理暂存", activity.contains("clearPending"))
        assertFalse("仍存在静默的 data?.data ?: return", activity.contains("data?.data ?: return"))
        assertFalse("仍存在静默的 resultCode 提前返回", activity.contains("if (resultCode != RESULT_OK) return"))
        assertTrue("底部标签未绑定页面切换", activity.contains("tabBar.addView(webTab("))
    }

    @Test
    fun unimplementedSegmentIsRejectedWithMessage() {
        val pages = read("ui/pages/Pages.kt")
        assertTrue("mm/inch 未实现段未拒绝切换", pages.contains("acceptedSegmentIndex(0, index) == index"))
        assertTrue("拒绝切换没有提示", pages.contains("onRejected = { actions.toast"))
        assertTrue("图表段切换未真正改绘制", pages.contains("chart.mode ="))
    }

    @Test
    fun channelChipsRepaintAndFilter() {
        val pages = read("ui/pages/Pages.kt")
        assertTrue("通道筛选点击未更新焦点", pages.contains("focus = picked"))
        assertTrue("通道筛选点击未重绘表格", pages.contains("renderTable()"))
        assertTrue("通道筛选点击未重绘图表", pages.contains("renderChart()"))
        assertTrue("命令目标点击未重绘", pages.contains("selected = no"))
    }

    @Test
    fun channelFilterGridStaysTwoRowsAndClickable() {
        assertEquals(4, CHANNEL_FILTER_COLUMNS)
        assertEquals(2, CHANNEL_FILTER_MAX_ROWS)
        assertEquals(2, channelFilterRows(8))
        assertTrue(channelFilterFits(8))
        assertEquals(1, channelFilterPlaceholders(3))
        val grid = functionBody(read("ui/WebUi.kt"), "fun Context.webChannelFilterFlow(")
        assertTrue("等宽网格末行未补占位", grid.contains("channelFilterPlaceholders"))
        assertTrue("等宽网格按钮权重丢失", grid.contains("1f"))
    }

    @Test
    fun buttonScenariosKeepPolicyResults() {
        assertEquals("请先点『扫描设备』并等待列表出现卡尺", batchConnectMessage(0, 0))
        assertEquals("已取消CSV 导入", safResultMessage(false, false, "CSV 导入"))
        assertNull(safResultMessage(true, true, "CSV 导出"))
        assertEquals(ExportAction.NO_CONTENT, exportAction(false, false, false))
        assertEquals(ExportAction.CHANNEL_FILES, exportAction(false, false, true))
    }

    @Test
    fun commandSendIsWiredToVisibleFeedback() {
        val pages = read("ui/pages/Pages.kt")
        val start = pages.indexOf("fun send(command: CaliperCommand")
        assertTrue("缺少 send() 函数", start >= 0)
        val sendBody = lambdaBody(pages, start)
        assertTrue("send() 未把下发结果转成提示", sendBody.contains("commandFeedback("))
        assertTrue("send() 未显示提示", sendBody.contains("actions.toast"))
        assertTrue("模式切换未抑制重复提示", pages.contains("notifySent = false"))
        assertTrue("成功下发文案缺失", read("core/model/UiActionPolicy.kt").contains("等待卡尺回数"))
    }

    @Test
    fun activityLaunchHasVisibleFailureFallback() {
        val activity = read("ui/MainActivity.kt")
        assertTrue("未捕获 ActivityNotFoundException", activity.contains("ActivityNotFoundException"))
        assertTrue("启动未收敛到统一入口", activity.contains("launchSafely("))
        assertEquals(
            "存在绕过兜底的 startActivity",
            1,
            Regex("startActivity\\(").findAll(activity).count()
        )
        assertEquals(
            "存在绕过兜底的 startActivityForResult",
            1,
            Regex("startActivityForResult\\(").findAll(activity).count()
        )
        val body = lambdaBody(activity, activity.indexOf("private fun launchSafely("))
        assertTrue("启动失败无可见提示", body.contains("toast("))
        assertTrue("启动失败未清理暂存", body.contains("clearPending"))
        assertTrue("启动失败未写诊断", body.contains("ACTIVITY_NOT_FOUND"))
    }

    @Test
    fun exportButtonLabelTracksSelection() {
        val page = read("ui/pages/ExportSettingsPages.kt")
        val start = page.indexOf("internal fun exportPage(")
        val end = page.indexOf("private fun settingsMeasureCard(")
        assertTrue("缺少导出页函数", start >= 0 && end > start)
        val exportBody = page.substring(start, end)
        assertTrue("导出按钮初始文案与默认动作不符", exportBody.contains("webButton(\"导出 CSV\", primary = true)"))
        assertFalse("导出页仍使用会误导的「保存」按钮", exportBody.contains("webButton(\"保存\""))
        assertTrue("设置页的「保存」仍应真实保存全部设置", page.substring(end).contains("webButton(\"保存\", primary = true)"))
        assertTrue("导出按钮未接入状态策略", exportBody.contains("exportButtonState("))
        assertTrue("按钮文案未实时更新", exportBody.contains("saveButton.text = state.label"))
        assertTrue("状态说明未实时更新", exportBody.contains("hintLine.text = state.hint"))
        assertTrue(
            "勾选变化未刷新按钮",
            Regex("refreshExport\\(\\)").findAll(exportBody).count() >= 5
        )
    }

    @Test
    fun readonlyTagIsNotClickableAndLooksDifferent() {
        val webUi = read("ui/WebUi.kt")
        val tag = functionBody(webUi, "fun Context.webChip(")
        assertFalse("只读标签仍绑定点击监听", tag.contains("setOnClickListener"))
        assertTrue("只读标签未显式声明不可点击", tag.contains("READONLY_TAG_CLICKABLE"))
        assertTrue("只读标签未使用小圆角", tag.contains("READONLY_TAG_CORNER_RADIUS_DP"))
        assertTrue("只读标签未去掉焦点", tag.contains("isFocusable = false"))
        val chip = functionBody(webUi, "fun Context.webChChip(")
        assertTrue("可点 chip 丢失胶囊圆角常量", chip.contains("INTERACTIVE_CHIP_CORNER_RADIUS_DP"))
        assertTrue("可点 chip 丢失可点击标记", chip.contains("isClickable = true"))
        assertTrue(readonlyTagIsDistinct())
    }

    @Test
    fun deviceNameFlowIsWiredIntoCaptureAndAnalysis() {
        val container = read("AppContainer.kt")
        assertTrue("缺少设备名 Flow 门面", container.contains("fun deviceNames(): Flow<Map<String, String>>"))
        assertTrue(
            "设备名未按登记名解析",
            container.contains("deviceDisplayName(device.name, device.customName, device.mac)")
        )
        val pages = read("ui/pages/Pages.kt")
        assertTrue(
            "订阅未同时收集记录与设备名",
            pages.contains("combine(c.measurements(), c.deviceNames())")
        )
        assertEquals(
            "检测页与分析页未同时订阅设备名",
            2,
            Regex("observeMeasurements\\(c\\)").findAll(pages).count()
        )
        assertTrue("表格行未解析设备名称", pages.contains("measurementDeviceName(deviceNames, item.deviceMac)"))
        assertTrue("通道 chip 未接入设备短标签", pages.contains("channelDeviceTags(c.channelSlots(), deviceNames)"))
        assertTrue("设备名变化未重绘通道 chip", pages.contains("!= lastChipTags"))
    }

    @Test
    fun captureTableRendersThreeColumnsAndChipSubtitle() {
        val webUi = read("ui/WebUi.kt")
        val head = functionBody(webUi, "fun Context.webTableHeader(")
        assertTrue("表头缺少设备名称列", head.contains("label(\"设备名称\""))
        assertTrue("表头未使用三列权重", head.contains("TABLE_DEVICE_WEIGHT"))
        val row = functionBody(webUi, "fun Context.webTableRow(")
        assertTrue("表格行缺少设备名称参数", row.contains("deviceName: String"))
        assertTrue("表格行未显示设备名称", row.contains("label(deviceName"))
        assertTrue("表格行未约束名称单行省略", row.contains("TextUtils.TruncateAt.END"))
        val chip = functionBody(webUi, "fun Context.webChChip(")
        assertTrue("通道 chip 缺少副行参数", chip.contains("subtitle: String?"))
        assertTrue("通道 chip 未渲染副行", chip.contains("label(subtitle"))
        assertTrue("通道 chip 副行未保持可点击", chip.contains("isClickable = true"))
        assertTrue(tableColumnsFit())
    }

    @Test
    fun bluetoothRecoveryAndKnownDevicesAreWired() {
        val container = read("AppContainer.kt")
        assertTrue("未监听系统蓝牙开关", container.contains("BluetoothAdapter.ACTION_STATE_CHANGED"))
        assertTrue("未注册蓝牙广播", container.contains("fun registerBluetoothWatcher()"))
        assertTrue("蓝牙恢复未触发补连循环", container.contains("startRecoveryCycle()"))
        assertTrue("补连循环缺少终止策略", container.contains("recoveryDecision(recoveryTick"))
        assertTrue("补连目标未按已登记设备去重", container.contains("recoveryTargets(recent"))
        assertTrue("扫描命中未接自动补连", container.contains("fun autoConnectScanned(mac: String)"))
        val pages = read("ui/pages/Pages.kt")
        assertTrue("连接页未常驻显示已登记设备", pages.contains("renderKnownDevices(context, c, actions"))
        assertTrue("连接页未监听蓝牙恢复", pages.contains("watchBluetoothRecovery(context, c, page)"))
        val recovery = read("ui/pages/ConnectRecoveryParts.kt")
        assertTrue("已登记设备缺少手动重连入口", recovery.contains("webButton(\"重连\", small = true)"))
        assertTrue("已登记设备列表未复用按钮状态说明", recovery.contains("knownDevicesCaption("))
        assertTrue("扫描回调未触发自动补连", read("ui/pages/ConnectParts.kt").contains("c.autoConnectScanned(device.mac)"))
    }

    @Test
    fun toastBubbleReplacesSystemToastWithBoundedSize() {
        val activity = read("ui/MainActivity.kt")
        assertFalse("仍在使用会排队且停留长的系统 Toast", activity.contains("Toast.makeText"))
        assertTrue("气泡未挂载到独立浮层", activity.contains("addContentView("))
        assertTrue("气泡停留时长未接入策略", activity.contains("bubbleDurationMs(message.length)"))
        assertTrue("气泡宽度未封顶", activity.contains("bubbleMaxWidthPx("))
        assertTrue("气泡未区分警示色", activity.contains("bubbleFill(tone)"))
        assertTrue("新消息未替换旧气泡", activity.contains("bubbleHost.removeAllViews()"))
        assertTrue("旧气泡计时未取消", activity.contains("bubbleHandler.removeCallbacks(it)"))
        assertTrue("气泡未限制行数", activity.contains("maxLines = BUBBLE_MAX_LINES"))
    }

    @Test
    fun chartShowsOnlyAxisValuesWithoutFloatingBubbles() {
        val chart = read("ui/chart/ChartCanvasView.kt")
        assertTrue("缺少 Y 轴 mm 数值刻度", chart.contains("chartAxisTicks(low, high"))
        assertTrue("缺少 X 轴时间刻度", chart.contains("chartTimeTicks("))
        assertTrue("Y 轴刻度未格式化为 mm 数值", chart.contains("Units.formatMm(tick, decimals)"))
        assertTrue("X 轴刻度未格式化为时间数值", chart.contains("formatChartTime(time)"))
        assertFalse("仍在绘制圆角气泡背景", chart.contains("drawRoundRect("))
        assertFalse("仍保留最新值行情气泡", chart.contains("drawLatestBadge"))
        assertFalse("仍保留气泡专用画笔", chart.contains("badgePaint"))
        assertFalse("仍叠加数据点数值标注", chart.contains("chartValueLabelIndices("))
        assertFalse("曲线上仍使用涨跌色调", chart.contains("trendToneOf("))
        val policy = read("core/model/ChartPresentation.kt")
        assertFalse("策略层仍保留点标注函数", policy.contains("chartValueLabelIndices"))
        assertFalse("策略层仍保留逐点标注上限", policy.contains("CHART_FULL_LABEL_LIMIT"))
        val pages = read("ui/pages/Pages.kt")
        assertTrue("分析页未传入带时间戳样本", pages.contains("chart.samples = ordered.map"))
        assertTrue("分析页未显示数值概览", pages.contains("chartHeadline(chart.samples"))
        assertTrue("分析页未跟随小数位设置", pages.contains("chart.decimals = c.settings.decimals"))
        assertTrue("分析页说明未同步为仅轴数值", pages.contains("X 时间轴 · Y 数值轴"))
        assertFalse("分析页仍宣传涨跌气泡", pages.contains("涨红跌绿"))
    }

    @Test
    fun overLimitAlertIsAudibleVisibleAndVibrating() {
        val beep = read("core/audio/OverLimitBeep.kt")
        assertTrue("蜂鸣未改为三连脉冲", beep.contains("beepPulseDelays()"))
        assertTrue("未接入高音量参数", beep.contains("BEEP_VOLUME_PERCENT"))
        assertTrue("未接入振动波形", beep.contains("beepVibrationPattern()"))
        assertTrue("新一轮未取消旧脉冲", beep.contains("cancelPulses()"))
        assertTrue("清单未声明振动权限", readMainFile("AndroidManifest.xml").contains("android.permission.VIBRATE"))
        assertTrue("超差未给出红色警示气泡", read("AppContainer.kt").contains("overLimitAlertMessage()"))
        assertTrue("设置页未同步三连提醒文案", read("ui/pages/SettingsParts.kt").contains("三连蜂鸣"))
    }

    private companion object {
        const val SOURCE_PATH = "src/main/java/com/guanglu/caliper"
        const val EXPECTED_BUTTON_SITES = 40
        val BUTTON_PATTERN = Regex("webButton\\(\\s*\"([^\"]+)\"")
        val SWITCH_PATTERN = Regex("setOnCheckedChangeListener")
        val BUTTON_FILES = listOf(
            "ui/pages/Pages.kt",
            "ui/pages/ExportSettingsPages.kt",
            "ui/pages/SettingsParts.kt",
            "ui/pages/ConnectRecoveryParts.kt"
        )
    }
}
