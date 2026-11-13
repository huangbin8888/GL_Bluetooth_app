package com.guanglu.caliper

import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.ScanRegistry
import com.guanglu.caliper.core.bluetooth.device.GuangluCaliperHandler
import com.guanglu.caliper.ui.pages.channelAtPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描稳定设备标识、双键去重与按通道目标选择的回归测试。
 *
 * @author AI-Generated
 */
class ScanIdentityTest {

    private fun scanned(mac: String, name: String, rssi: Int): ScannedDevice =
        ScannedDevice(mac, name, rssi, GuangluCaliperHandler.stableScanKey(name, mac))

    @Test fun stable_key_prefers_named_broadcast_over_mac() {
        val first = GuangluCaliperHandler.stableScanKey("GL-BLE#0187", "AA:BB:CC:DD:EE:01")
        val second = GuangluCaliperHandler.stableScanKey("GL-BLE#0187", "11:22:33:44:55:66")
        assertEquals("GL-BLE#0187", first)
        assertEquals(first, second)
    }

    @Test fun stable_key_falls_back_to_normalized_mac_for_bare_prefix() {
        assertEquals("AA:BB", GuangluCaliperHandler.stableScanKey("GL-BLE#", " aa:bb "))
        assertEquals("AA:BB", GuangluCaliperHandler.stableScanKey(null, " aa:bb "))
    }

    @Test fun stable_key_normalizes_case_and_whitespace() {
        assertEquals("GL-BLE#AB12", GuangluCaliperHandler.stableScanKey(" gl-ble#ab12 ", "AA:01"))
    }

    @Test fun same_name_with_three_different_macs_merges_into_one_entry() {
        val registry = ScanRegistry()
        assertTrue(registry.add(scanned("AA:01", "GL-BLE#0187", -50)))
        assertFalse(registry.add(scanned("BB:02", "GL-BLE#0187", -55)))
        assertFalse(registry.add(scanned("CC:03", "gl-ble#0187", -60)))
        assertEquals(1, registry.size)
        assertEquals(2, registry.mergedCount)
        assertEquals("CC:03", registry.devices.single().mac)
    }

    @Test fun mac_case_and_whitespace_variants_merge_without_new_entry() {
        val registry = ScanRegistry()
        assertTrue(registry.add(scanned("AA:01", "GL-BLE#", -50)))
        assertFalse(registry.add(scanned(" aa:01 ", "GL-BLE#", -60)))
        assertEquals(1, registry.size)
        assertEquals(1, registry.mergedCount)
    }

    @Test fun name_change_on_same_mac_keeps_one_entry() {
        val registry = ScanRegistry()
        assertTrue(registry.add(scanned("AA:01", "GL-BLE#0187", -50)))
        assertFalse(registry.add(scanned("AA:01", "GL-BLE#9999", -55)))
        assertEquals(1, registry.size)
        assertEquals("GL-BLE#9999", registry.devices.single().name)
    }

    @Test fun clear_resets_registry_and_merged_count() {
        val registry = ScanRegistry()
        registry.add(scanned("AA:01", "GL-BLE#0187", -50))
        registry.add(scanned("BB:02", "GL-BLE#0187", -55))
        assertEquals(1, registry.size)
        assertEquals(1, registry.mergedCount)
        registry.clear()
        assertEquals(0, registry.size)
        assertEquals(0, registry.mergedCount)
        assertTrue(registry.add(scanned("AA:01", "GL-BLE#0187", -50)))
    }

    @Test fun channel_at_position_maps_to_real_online_channel() {
        val numbers = listOf(2, 5, 7)
        assertEquals(2, channelAtPosition(numbers, 0))
        assertEquals(5, channelAtPosition(numbers, 1))
        assertEquals(7, channelAtPosition(numbers, 2))
        assertNull(channelAtPosition(numbers, 3))
        assertNull(channelAtPosition(emptyList(), 0))
    }
}
