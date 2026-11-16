package com.guanglu.caliper

import com.guanglu.caliper.core.model.INTERACTIVE_CHIP_CORNER_RADIUS_DP
import com.guanglu.caliper.core.model.READONLY_TAG_CLICKABLE
import com.guanglu.caliper.core.model.READONLY_TAG_CORNER_RADIUS_DP
import com.guanglu.caliper.core.model.readonlyTagIsDistinct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只读状态标签与可点击通道 chip 的视觉、交互区分护栏。
 *
 * @author AI-Generated
 */
class ReadonlyTagStyleTest {

    @Test
    fun readonlyTagUsesSmallCorner() {
        assertEquals(4, READONLY_TAG_CORNER_RADIUS_DP)
    }

    @Test
    fun interactiveChipKeepsCapsuleCorner() {
        assertEquals(18, INTERACTIVE_CHIP_CORNER_RADIUS_DP)
    }

    @Test
    fun readonlyTagIsNotClickable() {
        assertFalse(READONLY_TAG_CLICKABLE)
    }

    @Test
    fun cornersAreVisuallyDifferent() {
        assertTrue(READONLY_TAG_CORNER_RADIUS_DP < INTERACTIVE_CHIP_CORNER_RADIUS_DP)
    }

    @Test
    fun readonlyTagIsDistinctInBothWays() {
        assertTrue(readonlyTagIsDistinct())
    }
}
