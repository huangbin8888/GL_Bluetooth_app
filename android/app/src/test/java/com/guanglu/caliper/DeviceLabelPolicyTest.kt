package com.guanglu.caliper

import com.guanglu.caliper.core.model.DEVICE_NAME_PREFIX
import com.guanglu.caliper.core.model.DEVICE_TAG_MAX_CHARS
import com.guanglu.caliper.core.model.TABLE_DEVICE_WEIGHT
import com.guanglu.caliper.core.model.TABLE_TIME_WEIGHT
import com.guanglu.caliper.core.model.TABLE_VALUE_WEIGHT
import com.guanglu.caliper.core.model.UNKNOWN_DEVICE_NAME
import com.guanglu.caliper.core.model.channelDeviceTag
import com.guanglu.caliper.core.model.channelDeviceTags
import com.guanglu.caliper.core.model.compactMac
import com.guanglu.caliper.core.model.deviceDisplayName
import com.guanglu.caliper.core.model.deviceShortTag
import com.guanglu.caliper.core.model.measurementDeviceName
import com.guanglu.caliper.core.model.tableColumnsFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备名称解析与检测表格三列权重的回归测试。
 *
 * @author AI-Generated
 */
class DeviceLabelPolicyTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun customNameWinsOverRegisteredName() {
        assertEquals("#GL-1234", deviceDisplayName("GL-BLE#E:FF", "#GL-1234", mac))
    }

    @Test
    fun registeredNameIsUsedWhenNoCustomName() {
        assertEquals("卡尺 A", deviceDisplayName("卡尺 A", null, mac))
        assertEquals("卡尺 A", deviceDisplayName("卡尺 A", "  ", mac))
    }

    @Test
    fun blankNamesFallBackToMacTail() {
        assertEquals("GL-BLE#EEFF", deviceDisplayName(null, null, mac))
        assertEquals("GL-BLE#EEFF", deviceDisplayName("", "  ", mac))
    }

    @Test
    fun emptyMacFallsBackToUnknownWithoutCrashing() {
        assertEquals(UNKNOWN_DEVICE_NAME, deviceDisplayName(null, null, ""))
    }

    @Test
    fun autoRegisteredNameWithColonIsNormalized() {
        assertEquals("GL-BLE#EEFF", deviceDisplayName("GL-BLE#E:FF", null, mac))
    }

    @Test
    fun userNamedGlBlePrefixIsPreserved() {
        assertEquals("GL-BLE#1234", deviceDisplayName("GL-BLE#1234", null, mac))
    }

    @Test
    fun compactMacRemovesSeparators() {
        assertEquals("AABBCCDDEEFF", compactMac(mac))
        assertEquals("AABBCCDDEEFF", compactMac("aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun shortTagUsesMacTailForAutoName() {
        assertEquals("#EEFF", deviceShortTag("GL-BLE#EEFF", mac))
    }

    @Test
    fun shortTagKeepsUserHashName() {
        assertEquals("#GL-1234", deviceShortTag("#GL-1234", mac))
        assertEquals("#1234", deviceShortTag("GL-BLE#1234", mac))
    }

    @Test
    fun shortTagKeepsPlainName() {
        assertEquals("卡尺A", deviceShortTag("卡尺A", mac))
    }

    @Test
    fun shortTagNeverReturnsBlank() {
        assertEquals("#EEFF", deviceShortTag("", mac))
        assertEquals("#未知", deviceShortTag("", ""))
    }

    @Test
    fun shortTagIsBoundedForNarrowChips() {
        val tag = deviceShortTag("A".repeat(40), mac)
        assertEquals(DEVICE_TAG_MAX_CHARS, tag.length)
    }

    @Test
    fun tableDeviceNamePrefersRegisteredName() {
        assertEquals("#GL-1234", measurementDeviceName(mapOf(mac to "#GL-1234"), mac))
    }

    @Test
    fun tableDeviceNameNeverBlankForUnregisteredMac() {
        assertEquals("GL-BLE#EEFF", measurementDeviceName(emptyMap(), mac))
        assertEquals(UNKNOWN_DEVICE_NAME, measurementDeviceName(emptyMap(), ""))
        assertEquals("GL-BLE#EEFF", measurementDeviceName(mapOf(mac to "  "), mac))
    }

    @Test
    fun idleChannelHasNoDeviceTag() {
        assertNull(channelDeviceTag(emptyMap(), null))
        assertNull(channelDeviceTag(emptyMap(), ""))
    }

    @Test
    fun connectedChannelHasDeviceTag() {
        assertEquals("#EEFF", channelDeviceTag(emptyMap(), mac))
        assertEquals("#GL-1234", channelDeviceTag(mapOf(mac to "#GL-1234"), mac))
    }

    @Test
    fun channelTagsOnlyCoverOccupiedSlots() {
        val tags = channelDeviceTags(mapOf(1 to mac, 3 to "11:22:33:44:55:66"), emptyMap())
        assertEquals(mapOf(1 to "#EEFF", 3 to "#5566"), tags)
        assertEquals(2, tags.size)
    }

    @Test
    fun tableColumnsFillRowAndKeepValueWidest() {
        assertTrue(tableColumnsFit())
        assertTrue(TABLE_VALUE_WEIGHT > TABLE_DEVICE_WEIGHT)
        assertTrue(TABLE_DEVICE_WEIGHT > TABLE_TIME_WEIGHT)
        assertTrue(DEVICE_NAME_PREFIX.startsWith("GL-BLE#"))
    }
}
