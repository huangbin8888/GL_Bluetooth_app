package com.guanglu.caliper

import com.guanglu.caliper.core.model.BEEP_COOLDOWN_MS
import com.guanglu.caliper.core.model.ValueTone
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
}
