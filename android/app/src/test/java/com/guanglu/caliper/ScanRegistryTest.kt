package com.guanglu.caliper

import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.ScanRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描去重登记：重复 MAC 原地更新，列表绝不出现重复条目。
 *
 * @author AI-Generated
 */
class ScanRegistryTest {

    private fun device(mac: String, rssi: Int, name: String = "GL-BLE#1234") =
        ScannedDevice(mac, name, rssi)

    @Test fun duplicate_mac_is_not_added_twice() {
        val registry = ScanRegistry()
        assertTrue(registry.add(device("AA:01", -50)))
        assertFalse(registry.add(device("AA:01", -60)))
        assertEquals(1, registry.size)
        assertEquals(listOf("AA:01"), registry.devices.map { it.mac })
    }

    @Test fun distinct_macs_are_kept_in_discovery_order() {
        val registry = ScanRegistry()
        registry.add(device("AA:01", -50))
        registry.add(device("BB:02", -55))
        registry.add(device("CC:03", -40))
        assertEquals(3, registry.size)
        assertEquals(listOf("AA:01", "BB:02", "CC:03"), registry.devices.map { it.mac })
    }

    @Test fun repeated_broadcast_updates_rssi_in_place_without_new_entry() {
        val registry = ScanRegistry()
        registry.add(device("AA:01", -50))
        registry.add(device("AA:01", -70))
        registry.add(device("AA:01", -30))
        assertEquals(1, registry.size)
        // 最新一次广播的 RSSI 应当原地更新。
        assertEquals(-30, registry.devices[0].rssi)
    }

    @Test fun clear_empties_registry() {
        val registry = ScanRegistry()
        registry.add(device("AA:01", -50))
        registry.add(device("BB:02", -55))
        registry.clear()
        assertEquals(0, registry.size)
        assertTrue(registry.devices.isEmpty())
    }
}
