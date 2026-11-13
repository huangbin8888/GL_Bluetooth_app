package com.guanglu.caliper

import com.guanglu.caliper.core.bluetooth.device.GuangluCaliperHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描名称回退与前缀过滤。
 *
 * @author AI-Generated
 */
class ScanFilterTest {

    @Test fun advertised_name_is_used_when_cached_name_is_missing() {
        val name = GuangluCaliperHandler.pickBroadcastName(null, "GL-BLE#0187")
        assertEquals("GL-BLE#0187", name)
        assertTrue(GuangluCaliperHandler.matchesBroadcastName(name))
    }

    @Test fun blank_cached_name_falls_back_to_advertised_name() {
        assertEquals(
            "GL-BLE#2043",
            GuangluCaliperHandler.pickBroadcastName("", "GL-BLE#2043")
        )
    }

    @Test fun cached_name_wins_when_present() {
        assertEquals(
            "GL-BLE#0187",
            GuangluCaliperHandler.pickBroadcastName("GL-BLE#0187", "GL-BLE#9999")
        )
    }

    @Test fun missing_or_foreign_names_are_rejected() {
        assertNull(GuangluCaliperHandler.pickBroadcastName(null, null))
        assertFalse(GuangluCaliperHandler.matchesBroadcastName(null))
        assertFalse(GuangluCaliperHandler.matchesBroadcastName(""))
        assertFalse(GuangluCaliperHandler.matchesBroadcastName("SomeOtherCaliper"))
    }
}
