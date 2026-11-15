package com.guanglu.caliper

import com.guanglu.caliper.core.model.CHANNEL_FILTER_COLUMNS
import com.guanglu.caliper.core.model.CHANNEL_FILTER_MAX_ROWS
import com.guanglu.caliper.core.model.CHIP_GAP_DP
import com.guanglu.caliper.core.model.CHIP_MIN_HEIGHT_DP
import com.guanglu.caliper.core.model.CHIP_PADDING_H_DP
import com.guanglu.caliper.core.model.CHIP_PADDING_V_DP
import com.guanglu.caliper.core.model.CHIP_TEXT_SIZE_SP
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.channelFilterFits
import com.guanglu.caliper.core.model.channelFilterPlaceholders
import com.guanglu.caliper.core.model.channelFilterRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道筛选按钮等宽网格的布局参数回归测试。
 *
 * @author AI-Generated
 */
class ChannelFilterLayoutTest {

    @Test
    fun zeroItemsTakeNoRow() {
        assertEquals(0, channelFilterRows(0))
    }

    @Test
    fun upToFourItemsTakeOneRow() {
        (1..CHANNEL_FILTER_COLUMNS).forEach { count ->
            assertEquals("count=$count", 1, channelFilterRows(count))
        }
    }

    @Test
    fun eightItemsTakeTwoRows() {
        assertEquals(2, channelFilterRows(8))
    }

    @Test
    fun nineItemsTakeThreeRows() {
        assertEquals(3, channelFilterRows(9))
    }

    @Test
    fun partialRowIsPaddedToFullWidth() {
        assertEquals(3, channelFilterPlaceholders(1))
        assertEquals(2, channelFilterPlaceholders(2))
        assertEquals(1, channelFilterPlaceholders(3))
        assertEquals(0, channelFilterPlaceholders(4))
    }

    @Test
    fun fullRowNeedsNoPlaceholder() {
        assertEquals(0, channelFilterPlaceholders(8))
        assertEquals(0, channelFilterPlaceholders(0))
    }

    @Test
    fun currentChannelSelectionFitsTwoRows() {
        val chipCount = 1 + ChannelPalette.MAX_CHANNELS
        assertEquals(8, chipCount)
        assertTrue(channelFilterFits(chipCount))
        assertEquals(CHANNEL_FILTER_MAX_ROWS, channelFilterRows(chipCount))
    }

    @Test
    fun beyondTwoRowsDoesNotFit() {
        assertFalse(channelFilterFits(9))
        assertFalse(channelFilterFits(12))
    }

    @Test
    fun columnsAndRowsStayBounded() {
        assertEquals(4, CHANNEL_FILTER_COLUMNS)
        assertEquals(2, CHANNEL_FILTER_MAX_ROWS)
    }

    @Test
    fun chipTextIsLargerThanBefore() {
        assertTrue(CHIP_TEXT_SIZE_SP > 11f)
    }

    @Test
    fun chipPaddingIsLargerThanBefore() {
        assertTrue(CHIP_PADDING_V_DP > 5)
        assertTrue(CHIP_PADDING_H_DP >= 9)
    }

    @Test
    fun chipHasEnlargedTouchHeight() {
        assertTrue(CHIP_MIN_HEIGHT_DP >= 32)
    }

    @Test
    fun gapsRemainCompact() {
        assertTrue(CHIP_GAP_DP in 1..6)
    }
}
