package com.guanglu.caliper.core.bluetooth

import java.util.LinkedHashMap

/**
 * 扫描结果去重登记：以 MAC 为键，重复广播（含 RSSI / 名称变化）原地更新，列表绝不出现重复条目。
 *
 * @author AI-Generated
 */
class ScanRegistry {
    private val map = LinkedHashMap<String, ScannedDevice>()

    val size: Int
        get() = map.size

    val devices: List<ScannedDevice>
        get() = map.values.toList()

    /** 加入一台设备；若 MAC 已存在则原地更新并返回 false，否则新增并返回 true。 */
    fun add(device: ScannedDevice): Boolean {
        val isNew = !map.containsKey(device.mac)
        map[device.mac] = device
        return isNew
    }

    fun clear() = map.clear()
}
