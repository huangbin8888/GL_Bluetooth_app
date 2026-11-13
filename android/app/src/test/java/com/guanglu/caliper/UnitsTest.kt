package com.guanglu.caliper

import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.Units
import com.guanglu.caliper.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 0.1 微米整数换算、4 位小数无损与判定。
 *
 * @author AI-Generated
 */
class UnitsTest {

    @Test fun mm_is_stored_as_tenths_of_micrometres() {
        assertEquals(1551500L, Units.toUm10("155.15", 'm'))
        assertEquals(-317119L, Units.toUm10("-31.7119", 'm'))
    }

    @Test fun four_decimals_are_exact_without_rounding() {
        assertEquals(1551565L, Units.toUm10("155.1565", 'm'))
        assertEquals("155.1565", Units.formatMm(1551565L))
        assertEquals("155.1565", Units.formatMm(1551565L, 4))
        assertEquals("0.0001", Units.formatMm(1L))
    }

    @Test fun inch_uses_25_4() {
        assertEquals(317119L, Units.toUm10("1.2485", 'i'))
        assertEquals(-317119L, Units.toUm10("-1.2485", 'i'))
    }

    @Test fun more_than_four_decimals_is_rejected() {
        assertNull(Units.toUm10("1.24855", 'i'))
    }

    @Test fun garbage_is_rejected() {
        assertNull(Units.toUm10("abc", 'm'))
        assertNull(Units.toUm10("1.0", 'x'))
    }

    @Test fun formatting_is_decimal_not_binary_float() {
        assertEquals("155.1500", Units.formatMm(1551500L))
        assertEquals("155.15", Units.formatMm(1551500L, 2))
        assertEquals("-31.7119", Units.formatMm(-317119L))
    }

    @Test fun verdict_uses_integer_boundaries() {
        assertEquals(Verdict.PASS, Units.judge(1551500L, 1550000L, 1552000L))
        assertEquals(Verdict.FAIL, Units.judge(1549999L, 1550000L, 1552000L))
        assertEquals(Verdict.FAIL, Units.judge(1552001L, 1550000L, 1552000L))
        assertEquals(Verdict.UNJUDGED, Units.judge(1551500L, null, null))
    }

    @Test fun channel_palette_has_exactly_seven_slots() {
        assertEquals(7, ChannelPalette.MAX_CHANNELS)
        assertEquals(7, ChannelPalette.COLORS.size)
        assertEquals("CH7", ChannelPalette.labelOf(7))
        assertEquals("历史通道", ChannelPalette.labelOf(10))
    }
}
