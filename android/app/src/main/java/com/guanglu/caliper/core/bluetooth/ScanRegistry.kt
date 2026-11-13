package com.guanglu.caliper.core.bluetooth

import java.util.LinkedHashMap

/**
 * 扫描结果去重登记：以稳定设备标识与规范化 MAC 双键合并，重复广播原地更新。
 *
 * @author AI-Generated
 */
class ScanRegistry {
    private val map = LinkedHashMap<String, ScannedDevice>()
    private val macKeys = LinkedHashMap<String, String>()

    val size: Int
        get() = map.size

    val devices: List<ScannedDevice>
        get() = map.values.toList()

    /** 被合并掉的重复广播次数。 */
    var mergedCount: Int = 0
        private set

    /** 加入一台设备；同标识或同 MAC 已存在时原地更新并返回 false。 */
    fun add(device: ScannedDevice): Boolean {
        val key = device.identity.ifBlank { device.mac.trim().uppercase() }
        val mac = device.mac.trim().uppercase()
        val existing = when {
            map.containsKey(key) -> key
            macKeys.containsKey(mac) -> macKeys.getValue(mac)
            else -> null
        }
        if (existing != null) {
            map[existing] = device
            macKeys[mac] = existing
            mergedCount++
            return false
        }
        map[key] = device
        macKeys[mac] = key
        return true
    }

    fun clear() {
        map.clear()
        macKeys.clear()
        mergedCount = 0
    }
}
