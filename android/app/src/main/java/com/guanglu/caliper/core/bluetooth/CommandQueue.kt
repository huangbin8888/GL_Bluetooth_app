package com.guanglu.caliper.core.bluetooth

/**
 * 7 路共用的串行 GATT 命令队列。
 *
 * @author AI-Generated
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

    /** 清除指定通道的待发命令，保留已发出的 inFlight 命令。 */
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
        while (inFlight == null) {
            val entry = queue.removeFirstOrNull() ?: return
            inFlight = entry
            if (!write(entry.mac, entry.bytes)) {
                inFlight = null
                entry.onResult(false)
            }
        }
    }
}
