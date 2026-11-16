package com.guanglu.caliper

import com.guanglu.caliper.core.model.BUBBLE_BASE_DURATION_MS
import com.guanglu.caliper.core.model.BUBBLE_MAX_DURATION_MS
import com.guanglu.caliper.core.model.BUBBLE_MAX_LINES
import com.guanglu.caliper.core.model.BUBBLE_MAX_WIDTH_RATIO
import com.guanglu.caliper.core.model.BUBBLE_TEXT_SIZE_SP
import com.guanglu.caliper.core.model.BubbleTone
import com.guanglu.caliper.core.model.OVER_LIMIT_BUBBLE_PREFIX
import com.guanglu.caliper.core.model.bubbleDurationMs
import com.guanglu.caliper.core.model.bubbleFill
import com.guanglu.caliper.core.model.bubbleMaxWidthPx
import com.guanglu.caliper.core.model.bubbleToneOf
import com.guanglu.caliper.core.model.overLimitAlertMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡停留时长、宽度上限与超差警示样式的回归测试。
 *
 * @author AI-Generated
 */
class ToastBubblePolicyTest {

    @Test
    fun short_message_stays_briefly() {
        assertEquals(BUBBLE_BASE_DURATION_MS, bubbleDurationMs(4))
        assertEquals(BUBBLE_BASE_DURATION_MS, bubbleDurationMs(24))
        assertTrue(BUBBLE_BASE_DURATION_MS < 2_000L)
    }

    @Test
    fun long_message_gets_more_time_but_is_capped() {
        assertEquals(BUBBLE_MAX_DURATION_MS, bubbleDurationMs(25))
        assertEquals(BUBBLE_MAX_DURATION_MS, bubbleDurationMs(500))
        assertTrue(BUBBLE_MAX_DURATION_MS < 3_500L)
    }

    @Test
    fun bubble_never_covers_full_screen_width() {
        val max = bubbleMaxWidthPx(1080, 48)
        assertTrue(max < 1080)
        assertTrue(max <= (1080 * BUBBLE_MAX_WIDTH_RATIO).toInt())
        assertTrue(bubbleMaxWidthPx(320, 48) > 0)
        assertTrue(bubbleMaxWidthPx(0, 48) > 0)
    }

    @Test
    fun bubble_text_is_enlarged_but_line_count_is_limited() {
        assertTrue(BUBBLE_TEXT_SIZE_SP >= 14f)
        assertEquals(2, BUBBLE_MAX_LINES)
    }

    @Test
    fun over_limit_message_uses_alert_tone() {
        assertEquals(BubbleTone.ALERT, bubbleToneOf(overLimitAlertMessage()))
        assertEquals(BubbleTone.NORMAL, bubbleToneOf("已清除 3 条检测记录"))
        assertNotEquals(
            bubbleFill(BubbleTone.NORMAL),
            bubbleFill(BubbleTone.ALERT)
        )
    }

    @Test
    fun over_limit_alert_mentions_red_value_and_tolerance() {
        val message = overLimitAlertMessage()
        assertTrue(message.startsWith(OVER_LIMIT_BUBBLE_PREFIX))
        assertTrue(message.contains("超差"))
        assertTrue(message.contains("公差"))
    }
}
