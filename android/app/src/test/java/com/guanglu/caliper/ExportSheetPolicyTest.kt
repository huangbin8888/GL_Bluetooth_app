package com.guanglu.caliper

import com.guanglu.caliper.core.model.channelDisplayName
import com.guanglu.caliper.core.model.exportValueHighlighted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出表格标红判定与中文通道名称契约。
 *
 * @author AI-Generated
 */
class ExportSheetPolicyTest {

    @Test fun stored_fail_verdict_is_always_highlighted() {
        assertTrue(exportValueHighlighted("FAIL", 1_550_000L, null, null))
    }

    @Test fun value_outside_current_limits_is_highlighted_even_if_unjudged() {
        assertTrue(exportValueHighlighted("UNJUDGED", 1_570_000L, 1_540_000L, 1_560_000L))
        assertTrue(exportValueHighlighted("PASS", 1_530_000L, 1_540_000L, 1_560_000L))
    }

    @Test fun pass_and_unjudged_without_limits_stay_normal() {
        assertFalse(exportValueHighlighted("PASS", 1_550_000L, 1_540_000L, 1_560_000L))
        assertFalse(exportValueHighlighted("UNJUDGED", 1_550_000L, null, null))
    }

    @Test fun channel_name_is_chinese_not_ch_prefix() {
        assertEquals("通道1", channelDisplayName(1))
        assertEquals("通道7", channelDisplayName(7))
        assertFalse(channelDisplayName(3).startsWith("CH"))
    }
}
