package com.guanglu.caliper

import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.Units
import com.guanglu.caliper.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {

    @Test fun mm_is_stored_as_integer_micrometres() {
        assertEquals(155150L, Units.toMicrometres("155.15", 'm'))
        assertEquals(-31712L, Units.toMicrometres("-31.7119", 'm'))
    }

    @Test fun inch_uses_25_4() {
        assertEquals(31712L, Units.toMicrometres("1.2485", 'i'))
        assertEquals(-31712L, Units.toMicrometres("-1.2485", 'i'))
    }

    @Test fun more_than_four_decimals_is_rejected() {
        assertNull(Units.toMicrometres("1.24855", 'i'))
    }

    @Test fun garbage_is_rejected() {
        assertNull(Units.toMicrometres("abc", 'm'))
        assertNull(Units.toMicrometres("1.0", 'x'))
    }

    @Test fun formatting_is_decimal_not_binary_float() {
        assertEquals("155.15", Units.formatMm(155150L, 2))
        assertEquals("-31.71", Units.formatMm(-31712L, 2))
    }

    @Test fun verdict_uses_integer_boundaries() {
        assertEquals(Verdict.PASS, Units.judge(155150L, 155000L, 155200L))
        assertEquals(Verdict.FAIL, Units.judge(154999L, 155000L, 155200L))
        assertEquals(Verdict.FAIL, Units.judge(155201L, 155000L, 155200L))
        assertEquals(Verdict.UNJUDGED, Units.judge(155150L, null, null))
    }

    @Test fun channel_palette_has_exactly_seven_slots() {
        assertEquals(7, ChannelPalette.MAX_CHANNELS)
        assertEquals(7, ChannelPalette.COLORS.size)
        assertEquals("CH7", ChannelPalette.labelOf(7))
        assertEquals("历史通道", ChannelPalette.labelOf(10))
    }
}
