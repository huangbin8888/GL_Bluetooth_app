package com.guanglu.caliper

import com.guanglu.caliper.core.model.ChannelNamePolicy
import com.guanglu.caliper.core.model.ChannelPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道中文命名策略：默认值、规范化、持久化往返、导入反查与文件名。
 */
class ChannelNamePolicyTest {

    @Test
    fun defaultNamesAreChineseAndBounded() {
        assertEquals("通道1", ChannelNamePolicy.defaultName(1))
        assertEquals("通道7", ChannelNamePolicy.defaultName(ChannelPalette.MAX_CHANNELS))
        assertEquals("历史通道", ChannelNamePolicy.defaultName(0))
        assertEquals("历史通道", ChannelNamePolicy.defaultName(8))
    }

    @Test
    fun normalizeKeepsChineseAndDropsCsvUnsafeChars() {
        assertEquals("外径测量A", ChannelNamePolicy.normalize("  外径测量A  ", 1))
        assertEquals("长度上线", ChannelNamePolicy.normalize("长度,上\"线\n", 2))
        assertEquals("通道3", ChannelNamePolicy.normalize("   ", 3))
        assertEquals("通道4", ChannelNamePolicy.normalize("", 4))
    }

    @Test
    fun normalizeEnforcesLengthLimit() {
        val long = "一二三四五六七八九十甲乙丙丁"
        assertEquals(ChannelNamePolicy.MAX_NAME_CHARS, ChannelNamePolicy.normalize(long, 1).length)
    }

    @Test
    fun encodeDecodeRoundTripPreservesNames() {
        val names = mapOf(
            1 to "外径", 2 to "内径", 3 to "厚度", 4 to "通道4",
            5 to "宽度", 6 to "深度", 7 to "高度"
        )
        val restored = ChannelNamePolicy.decode(ChannelNamePolicy.encode(names))
        assertEquals(names, restored)
    }

    @Test
    fun decodeDamagedOrMissingPartsFallsBackToDefaults() {
        val restored = ChannelNamePolicy.decode("外径|内径")
        assertEquals("外径", restored[1])
        assertEquals("内径", restored[2])
        assertEquals("通道3", restored[3])
        assertEquals("通道7", restored[7])
        assertEquals(ChannelPalette.MAX_CHANNELS, restored.size)
    }

    @Test
    fun nameOfFallsBackForUnknownChannel() {
        assertEquals("通道5", ChannelNamePolicy.nameOf(emptyMap(), 5))
        assertEquals("外径", ChannelNamePolicy.nameOf(mapOf(1 to "外径"), 1))
    }

    @Test
    fun displayLabelUsesCustomNameAndKeepsChannelNumberByDefault() {
        val names = ChannelNamePolicy.decode("外径|内径")
        assertEquals("外径", ChannelNamePolicy.displayLabel(names, 1))
        assertEquals("内径", ChannelNamePolicy.displayLabel(names, 2))
        assertEquals("CH3", ChannelNamePolicy.displayLabel(names, 3))
        assertEquals("CH7", ChannelNamePolicy.displayLabel(names, 7))
    }

    @Test
    fun displayLabelKeepsDefaultAndHistoricalTokens() {
        assertEquals("CH1", ChannelNamePolicy.displayLabel(emptyMap(), 1))
        assertEquals("历史通道", ChannelNamePolicy.displayLabel(emptyMap(), 0))
        assertEquals("历史通道", ChannelNamePolicy.displayLabel(emptyMap(), 8))
    }

    @Test
    fun displayLabelFallsBackWhenNameEqualsDefault() {
        val names = mapOf(1 to "通道1")
        assertEquals("CH1", ChannelNamePolicy.displayLabel(names, 1))
    }

    @Test
    fun numberFromNameMatchesCustomNameFirst() {
        val names = ChannelNamePolicy.decode("外径|内径")
        assertEquals(1 to false, ChannelNamePolicy.numberFromName("外径", names))
        assertEquals(2 to false, ChannelNamePolicy.numberFromName("内径", names))
        assertEquals(1 to false, ChannelNamePolicy.numberFromName("  外径 ", names))
    }

    @Test
    fun numberFromNameFallsBackToLegacyChannelTokens() {
        val names = emptyMap<Int, String>()
        assertEquals(7 to false, ChannelNamePolicy.numberFromName("CH7", names))
        assertEquals(3 to false, ChannelNamePolicy.numberFromName("通道3", names))
        assertEquals(10 to true, ChannelNamePolicy.numberFromName("CH10", names))
        assertEquals(0 to true, ChannelNamePolicy.numberFromName("未知卡尺", names))
    }

    @Test
    fun fileNameUsesChannelNameForSingleExport() {
        val names = ChannelNamePolicy.decode("外径|内径")
        assertEquals("测量数据.csv", ChannelNamePolicy.csvFileName(null, names))
        assertTrue(ChannelNamePolicy.csvFileName(1, names).startsWith("外径_"))
        assertTrue(ChannelNamePolicy.csvFileName(1, names).endsWith("_测量数据.csv"))
        assertFalse(ChannelNamePolicy.csvFileName(2, names).contains("CH2"))
    }
}
