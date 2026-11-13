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
 * 全部断开会清空在线、排队与进行中的连接并立即放行后续连接。
 *
 * @author AI-Generated
 */
class DisconnectAllTest {

    private class FakeTransport : BleTransport {
        val connects = CopyOnWriteArrayList<String>()
        val disconnects = CopyOnWriteArrayList<String>()
        override fun startScan(onDevice: (ScannedDevice) -> Unit) = Unit
        override fun stopScan() = Unit
        override fun connect(mac: String, callback: BleConnectionCallback) {
            connects.add(mac)
        }
        override fun disconnect(mac: String) {
            disconnects.add(mac)
        }
        override fun write(mac: String, bytes: ByteArray): Boolean = true
        override fun isConnected(mac: String): Boolean = false
    }

    @Test fun disconnect_all_cancels_connected_pending_and_connecting() {
        val transport = FakeTransport()
        val events = CopyOnWriteArrayList<ChannelEvent>()
        val manager = ChannelManager(
            transport = transport,
            commandQueue = CommandQueue { _, _ -> true },
            onMeasurement = { _, _, _ -> },
            onEvent = { events.add(it) }
        )
        try {
            assertTrue(manager.requestConnect("AA:01"))
            assertTrue(manager.requestConnect("BB:02"))
            manager.onReady("AA:01")
            assertEquals(2, manager.disconnectAll())
            assertTrue(transport.disconnects.contains("AA:01"))
            assertTrue(transport.disconnects.contains("BB:02"))
            assertEquals(0, manager.onlineCount())
            assertEquals(
                2,
                events.count { it is ChannelEvent.Disconnected && it.reason == "用户断开" }
            )
            assertTrue(manager.requestConnect("CC:03"))
            assertEquals("CC:03", transport.connects.last())
        } finally {
            manager.close()
        }
    }
}
