package com.guanglu.caliper

import com.guanglu.caliper.core.model.BEEP_COOLDOWN_MS
import com.guanglu.caliper.core.model.BEEP_PULSE_COUNT
import com.guanglu.caliper.core.model.BEEP_PULSE_DURATION_MS
import com.guanglu.caliper.core.model.BEEP_PULSE_GAP_MS
import com.guanglu.caliper.core.model.BEEP_VOLUME_PERCENT
import com.guanglu.caliper.core.model.ValueTone
import com.guanglu.caliper.core.model.beepPulseDelays
import com.guanglu.caliper.core.model.beepVibrationPattern
import com.guanglu.caliper.core.model.isOverLimit
import com.guanglu.caliper.core.model.shouldBeep
import com.guanglu.caliper.core.model.valueToneOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超差数值配色与蜂鸣决策的回归测试。
 *
 * @author AI-Generated
 */
class VerdictPolicyTest {

    @Test
    fun failIsOverLimit() {
        assertTrue(isOverLimit("FAIL"))
    }

    @Test
    fun passIsNotOverLimit() {
        assertFalse(isOverLimit("PASS"))
    }

    @Test
    fun unjudgedIsNotOverLimit() {
        assertFalse(isOverLimit("UNJUDGED"))
    }

    @Test
    fun emptyVerdictIsNotOverLimit() {
        assertFalse(isOverLimit(""))
    }

    @Test
    fun verdictMatchIsExact() {
        assertFalse(isOverLimit("fail"))
        assertFalse(isOverLimit(" FAIL "))
        assertFalse(isOverLimit("FAILURE"))
    }

    @Test
    fun toneOfFailIsOverLimit() {
        assertEquals(ValueTone.OVER_LIMIT, valueToneOf("FAIL"))
    }

    @Test
    fun toneOfPassIsNormal() {
        assertEquals(ValueTone.NORMAL, valueToneOf("PASS"))
    }

    @Test
    fun toneOfUnjudgedIsNormal() {
        assertEquals(ValueTone.NORMAL, valueToneOf("UNJUDGED"))
    }

    @Test
    fun beepWhenOverLimitEnabledAndCooldownElapsed() {
        assertTrue(shouldBeep(overLimit = true, enabled = true, elapsedMs = BEEP_COOLDOWN_MS))
        assertTrue(shouldBeep(overLimit = true, enabled = true, elapsedMs = BEEP_COOLDOWN_MS + 1))
    }

    @Test
    fun noBeepWhenNotOverLimit() {
        assertFalse(shouldBeep(overLimit = false, enabled = true, elapsedMs = 10_000L))
    }

    @Test
    fun noBeepWhenDisabled() {
        assertFalse(shouldBeep(overLimit = true, enabled = false, elapsedMs = 10_000L))
    }

    @Test
    fun noBeepWithinCooldown() {
        assertFalse(shouldBeep(overLimit = true, enabled = true, elapsedMs = BEEP_COOLDOWN_MS - 1))
    }

    @Test
    fun noBeepWhenClockMovesBackwards() {
        assertFalse(shouldBeep(overLimit = true, enabled = true, elapsedMs = -1L))
    }

    @Test
    fun customCooldownIsRespected() {
        assertFalse(shouldBeep(true, true, 999L, 1_000L))
        assertTrue(shouldBeep(true, true, 1_000L, 1_000L))
    }

    @Test
    fun over_limit_alert_is_three_loud_pulses() {
        assertEquals(3, BEEP_PULSE_COUNT)
        assertEquals(100, BEEP_VOLUME_PERCENT)
        assertEquals(3, beepPulseDelays().size)
        assertEquals(listOf(0L, 240L, 480L), beepPulseDelays())
        assertEquals(BEEP_PULSE_DURATION_MS + BEEP_PULSE_GAP_MS, 240L)
    }

    @Test
    fun pulse_delays_are_finite_and_non_negative() {
        assertTrue(beepPulseDelays(0).isEmpty())
        assertTrue(beepPulseDelays(-5).isEmpty())
        assertTrue(beepPulseDelays(10).all { it >= 0L })
    }

    @Test
    fun vibration_pattern_matches_three_pulses() {
        val pattern = beepVibrationPattern()
        assertEquals(2 * BEEP_PULSE_COUNT, pattern.size)
        assertEquals(0L, pattern.first())
        assertTrue(pattern.all { it >= 0L })
        beepVibrationPattern()[1] = 1L
        assertEquals(130L, beepVibrationPattern()[1])
    }
}
