package com.guanglu.caliper

import com.guanglu.caliper.core.bluetooth.BleConnectionCallback
import com.guanglu.caliper.core.bluetooth.BleTransport
import com.guanglu.caliper.core.bluetooth.CommandQueue
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.channel.ChannelEvent
import com.guanglu.caliper.core.bluetooth.channel.ChannelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 连接无回调时不会永久占用串行连接队列。
 *
 * @author AI-Generated
 */
class ConnectTimeoutTest {

    private class FakeTransport : BleTransport {
        val connects = CopyOnWriteArrayList<String>()
        override fun startScan(onDevice: (ScannedDevice) -> Unit) = Unit
        override fun stopScan() = Unit
        override fun connect(mac: String, callback: BleConnectionCallback) {
            connects.add(mac)
        }
        override fun disconnect(mac: String) = Unit
        override fun write(mac: String, bytes: ByteArray): Boolean = true
        override fun isConnected(mac: String): Boolean = false
    }

    @Test fun stale_connection_does_not_block_the_next_device() {
        val transport = FakeTransport()
        val events = CopyOnWriteArrayList<ChannelEvent>()
        val manager = ChannelManager(
            transport = transport,
            commandQueue = CommandQueue { _, _ -> true },
            onMeasurement = { _, _, _ -> },
            onEvent = { events.add(it) },
            connectTimeoutMs = 80L
        )
        try {
            assertTrue(manager.requestConnect("AA:BB"))
            assertEquals(listOf("AA:BB"), transport.connects.toList())
            val deadline = System.currentTimeMillis() + 2000L
            while (events.none { it is ChannelEvent.Disconnected } &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(10L)
            }
            assertTrue(events.any { it is ChannelEvent.Disconnected })
            assertTrue(manager.requestConnect("CC:DD"))
            assertEquals(listOf("AA:BB", "CC:DD"), transport.connects.toList())
        } finally {
            manager.close()
        }
    }
}
