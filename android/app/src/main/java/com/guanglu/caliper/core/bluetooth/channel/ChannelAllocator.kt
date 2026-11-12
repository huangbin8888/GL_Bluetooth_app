package com.guanglu.caliper.core.bluetooth.channel

import com.guanglu.caliper.core.model.ChannelPalette

/**
 * 7 个会话槽位的分配器：第 8 台拒绝、单路释放不影响其他路、支持机型上限降级。
 * 与 BLE 传输解耦，纯 Kotlin 可 JVM 测试（基线 10.3 / 11.3）。
 */
class ChannelAllocator(private val maxChannels: Int = ChannelPalette.MAX_CHANNELS) {

    private val byMac = LinkedHashMap<String, Int>()
    private val bySlot = LinkedHashMap<Int, String>()

    /** 机型实测达不到 7 路时下调；上限永远不超过 maxChannels。 */
    @Volatile
    var deviceLimit: Int = maxChannels
        private set

    fun setDeviceLimit(limit: Int) {
        require(limit in 1..maxChannels) { "机型上限必须落在 1..$maxChannels" }
        deviceLimit = limit
    }

    private fun effectiveLimit(): Int = minOf(maxChannels, deviceLimit)

    /** 返回槽位号 1..7；已满或已达机型上限返回 null（调用方必须给出明确提示）。 */
    @Synchronized
    fun acquire(mac: String): Int? {
        byMac[mac]?.let { return it }
        if (bySlot.size >= effectiveLimit()) return null
        val slot = (1..maxChannels).firstOrNull { it !in bySlot } ?: return null
        bySlot[slot] = mac
        byMac[mac] = slot
        return slot
    }

    @Synchronized
    fun release(mac: String): Int? {
        val slot = byMac.remove(mac) ?: return null
        bySlot.remove(slot)
        return slot
    }

    @Synchronized
    fun slotOf(mac: String): Int? = byMac[mac]

    @Synchronized
    fun macOf(slot: Int): String? = bySlot[slot]

    @Synchronized
    fun onlineCount(): Int = bySlot.size

    @Synchronized
    fun slots(): Map<Int, String> = bySlot.toMap()
}
