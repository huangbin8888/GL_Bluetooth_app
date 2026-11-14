package com.guanglu.caliper

import com.guanglu.caliper.core.model.ExportAction
import com.guanglu.caliper.core.model.IDLE_POLL_INTERVAL_MS
import com.guanglu.caliper.core.model.STAT_PLACEHOLDER
import com.guanglu.caliper.core.model.acceptedSegmentIndex
import com.guanglu.caliper.core.model.batchConnectMessage
import com.guanglu.caliper.core.model.exportAction
import com.guanglu.caliper.core.model.parseDecimals
import com.guanglu.caliper.core.model.safResultMessage
import com.guanglu.caliper.core.model.statisticsTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按钮反馈链路与统计显示的纯策略回归测试。
 *
 * @author AI-Generated
 */
class UiActionPolicyTest {

    @Test
    fun cancelledFilePickerHasVisibleMessage() {
        assertEquals("已取消CSV 导出", safResultMessage(false, false, "CSV 导出"))
    }

    @Test
    fun cancelWinsEvenWhenUriPresent() {
        assertEquals("已取消图片导出", safResultMessage(false, true, "图片导出"))
    }

    @Test
    fun missingUriHasVisibleMessage() {
        assertEquals("CSV 导入 失败：未取得文件位置，请重试", safResultMessage(true, false, "CSV 导入"))
    }

    @Test
    fun validSafResultHasNoMessage() {
        assertNull(safResultMessage(true, true, "诊断日志导出"))
    }

    @Test
    fun batchConnectWithoutTargetsPromptsScan() {
        assertEquals("请先点『扫描设备』并等待列表出现卡尺", batchConnectMessage(0, 0))
    }

    @Test
    fun batchConnectAllRejectedDoesNotClaimConnecting() {
        val message = batchConnectMessage(3, 0)
        assertTrue(message.startsWith("0/3"))
        assertTrue(message.contains("未新增连接"))
    }

    @Test
    fun batchConnectPartialReportsCount() {
        assertEquals("已受理 2/3 台，其余通道已满或已在连接队列", batchConnectMessage(3, 2))
    }

    @Test
    fun batchConnectAllAcceptedReportsCount() {
        assertEquals("正在连接 3 台卡尺", batchConnectMessage(3, 3))
    }

    @Test
    fun implementedSegmentIsAccepted() {
        assertEquals(0, acceptedSegmentIndex(1, 0))
    }

    @Test
    fun unimplementedInchKeepsCurrentMm() {
        assertEquals(0, acceptedSegmentIndex(0, 1))
    }

    @Test
    fun outOfRangeSegmentKeepsCurrent() {
        assertEquals(2, acceptedSegmentIndex(2, 7, 2))
    }

    @Test
    fun maxImplementedOneAcceptsSecondSegment() {
        assertEquals(1, acceptedSegmentIndex(0, 1, 1))
    }

    @Test
    fun decimalsAcceptsRange() {
        assertEquals(0, parseDecimals("0"))
        assertEquals(2, parseDecimals(" 2 "))
        assertEquals(4, parseDecimals("4"))
    }

    @Test
    fun decimalsRejectsEmptyAndText() {
        assertNull(parseDecimals(""))
        assertNull(parseDecimals("abc"))
    }

    @Test
    fun decimalsRejectsOutOfRange() {
        assertNull(parseDecimals("-1"))
        assertNull(parseDecimals("5"))
        assertNull(parseDecimals("99"))
    }

    @Test
    fun exportDefaultsToAllCsv() {
        assertEquals(ExportAction.EXPORT_ALL_CSV, exportAction(csv = true, png = false, split = false))
    }

    @Test
    fun exportWithoutCsvDoesNotSilentlyExport() {
        assertEquals(ExportAction.NO_CONTENT, exportAction(csv = false, png = false, split = false))
        assertEquals(ExportAction.PNG_GUIDE, exportAction(csv = false, png = true, split = false))
    }

    @Test
    fun exportSplitTakesPriority() {
        assertEquals(ExportAction.CHANNEL_FILES, exportAction(csv = true, png = false, split = true))
        assertEquals(ExportAction.CHANNEL_FILES, exportAction(csv = false, png = false, split = true))
    }

    @Test
    fun exportCsvAndPngGuidesToAnalysis() {
        assertEquals(ExportAction.CSV_AND_PNG_GUIDE, exportAction(csv = true, png = true, split = false))
    }

    @Test
    fun emptyStatisticsResetAllCards() {
        val texts = statisticsTexts(emptyList(), 4)
        assertEquals(STAT_PLACEHOLDER, texts.average)
        assertEquals(STAT_PLACEHOLDER, texts.minimum)
        assertEquals(STAT_PLACEHOLDER, texts.maximum)
    }

    @Test
    fun averageIsRoundedNotTruncated() {
        assertEquals("0.0002", statisticsTexts(listOf(1L, 2L), 4).average)
    }

    @Test
    fun statisticsFollowDecimals() {
        assertEquals("1.00", statisticsTexts(listOf(10_000L), 2).average)
        assertEquals("1.0000", statisticsTexts(listOf(10_000L), 4).average)
    }

    @Test
    fun minimumAndMaximumFollowDecimals() {
        val texts = statisticsTexts(listOf(10_000L, 20_000L), 1)
        assertEquals("1.5", texts.average)
        assertEquals("1.0", texts.minimum)
        assertEquals("2.0", texts.maximum)
    }

    @Test
    fun idlePollIsNotMillisecondLoop() {
        assertTrue(IDLE_POLL_INTERVAL_MS >= 1_000L)
        assertTrue(IDLE_POLL_INTERVAL_MS <= 60_000L)
    }
}
