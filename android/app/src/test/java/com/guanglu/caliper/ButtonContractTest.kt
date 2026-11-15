package com.guanglu.caliper

import com.guanglu.caliper.core.model.CHANNEL_FILTER_COLUMNS
import com.guanglu.caliper.core.model.CHANNEL_FILTER_MAX_ROWS
import com.guanglu.caliper.core.model.ExportAction
import com.guanglu.caliper.core.model.batchConnectMessage
import com.guanglu.caliper.core.model.channelFilterFits
import com.guanglu.caliper.core.model.channelFilterPlaceholders
import com.guanglu.caliper.core.model.channelFilterRows
import com.guanglu.caliper.core.model.exportAction
import com.guanglu.caliper.core.model.safResultMessage
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

    private companion object {
        const val SOURCE_PATH = "src/main/java/com/guanglu/caliper"
        const val EXPECTED_BUTTON_SITES = 39
        val BUTTON_PATTERN = Regex("webButton\\(\\s*\"([^\"]+)\"")
        val SWITCH_PATTERN = Regex("setOnCheckedChangeListener")
        val BUTTON_FILES = listOf(
            "ui/pages/Pages.kt",
            "ui/pages/ExportSettingsPages.kt",
            "ui/pages/SettingsParts.kt"
        )
    }
}
