package com.guanglu.caliper

import com.guanglu.caliper.core.model.containsFullAddress
import com.guanglu.caliper.core.model.deviceAddressHint
import com.guanglu.caliper.core.model.fullBluetoothAddress
import com.guanglu.caliper.core.model.knownDeviceLabel
import com.guanglu.caliper.core.model.scannedDeviceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备列表蓝牙地址完整显示的回归测试。
 *
 * @author AI-Generated
 */
class DeviceAddressPolicyTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test fun full_address_is_never_truncated() {
        assertEquals("AA:BB:CC:DD:EE:FF", fullBluetoothAddress(mac))
        assertEquals(17, fullBluetoothAddress(mac).length)
        assertEquals("AA:BB:CC:DD:EE:FF", fullBluetoothAddress("  aa:bb:cc:dd:ee:ff  "))
    }

    @Test fun empty_address_has_readable_placeholder() {
        assertEquals("未知地址", fullBluetoothAddress(""))
        assertEquals("未知地址", fullBluetoothAddress("   "))
        assertEquals("AA:BB:CC:DD:EE:FF", deviceAddressHint(mac))
    }

    @Test fun scanned_label_puts_full_mac_on_second_line() {
        val label = scannedDeviceLabel("GL-BLE#0187", mac, -55)
        assertTrue(label.startsWith("GL-BLE#0187\n"))
        assertTrue(label.contains(mac))
        assertTrue(label.endsWith("-55dBm"))
        assertFalse(label.contains("EE:FF · -55dBm") && !label.contains(mac))
    }

    @Test fun known_device_label_puts_full_mac_on_second_line() {
        val label = knownDeviceLabel("GL-BLE#0187", mac)
        assertEquals("GL-BLE#0187\nAA:BB:CC:DD:EE:FF", label)
    }

    @Test fun contains_full_address_rejects_tail_only_text() {
        assertTrue(containsFullAddress(scannedDeviceLabel("GL-BLE#0187", mac, -55), mac))
        assertTrue(containsFullAddress(knownDeviceLabel("GL-BLE#0187", mac), mac))
        assertFalse(containsFullAddress("GL-BLE#0187 · EE:FF", mac))
    }
}
