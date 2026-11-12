package com.guanglu.caliper.core.bluetooth

/**
 * 7 路共用的串行 GATT 命令队列：blessed-android 的写操作虽自带排队，
 * 但跨 7 个 peripheral 仍需全局串行，且在写完成回调前不发下一条（基线 10.3）。
 * 纯 Kotlin，可 JVM 测试。
 */
class CommandQueue(private val write: (String, ByteArray) -> Boolean) {

    private class Entry(
        val mac: String,
        val bytes: ByteArray,
        val onResult: (Boolean) -> Unit
    )

    private val queue = ArrayDeque<Entry>()
    private var inFlight: Entry? = null

    @Synchronized
    fun submit(mac: String, bytes: ByteArray, onResult: (Boolean) -> Unit = {}) {
        queue.addLast(Entry(mac, bytes, onResult))
        drain()
    }

    /** 由 BlessedTransport.onCharacteristicWrite 回调驱动。 */
    @Synchronized
    fun onWriteCompleted(success: Boolean) {
        val entry = inFlight ?: return
        inFlight = null
        entry.onResult(success)
        drain()
    }

    /**
     * 单通道断连时清掉该通道的待发命令，其余通道不受影响。
     * 不清 inFlight：那一条写已经发给蓝牙栈，让它的 onCharacteristicWrite 正常回调并
     * 驱动 drain()，否则队列会永久卡住等待一个被丢弃的回调。
     */
    @Synchronized
    fun cancelFor(mac: String) {
        val it = queue.iterator()
        while (it.hasNext()) {
            if (it.next().mac == mac) it.remove()
        }
    }

    @Synchronized
    fun pendingCount(): Int = queue.size + if (inFlight == null) 0 else 1

    private fun drain() {
        if (inFlight != null) return
        val entry = queue.removeFirstOrNull() ?: return
        inFlight = entry
        if (!write(entry.mac, entry.bytes)) {
            inFlight = null
            entry.onResult(false)
            drain()
        }
    }
}
