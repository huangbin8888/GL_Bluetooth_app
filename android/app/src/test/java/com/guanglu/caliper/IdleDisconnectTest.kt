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
 * 空闲超过设定时长后通道被 App 断开，有数据活动则不断开。
 *
 * @author AI-Generated
 */
class IdleDisconnectTest {

    private class FakeTransport : BleTransport {
        override fun startScan(onDevice: (ScannedDevice) -> Unit) = Unit
        override fun stopScan() = Unit
        override fun connect(mac: String, callback: BleConnectionCallback) = Unit
        override fun disconnect(mac: String) = Unit
        override fun write(mac: String, bytes: ByteArray): Boolean = true
        override fun isConnected(mac: String): Boolean = false
    }

    private val minute = 60_000L

    @Test fun idle_channel_is_disconnected_after_timeout() {
        val events = CopyOnWriteArrayList<ChannelEvent>()
        var now = 1_000L
        val manager = ChannelManager(
            transport = FakeTransport(),
            commandQueue = CommandQueue { _, _ -> true },
            onMeasurement = { _, _, _ -> },
            onEvent = { events.add(it) },
            clock = { now }
        )
        try {
            manager.requestConnect("AA:01")
            manager.onReady("AA:01")
            now += 11 * minute
            manager.pollIdle(idleTimeoutMs = 10 * minute)
            val idle = events.filterIsInstance<ChannelEvent.Disconnected>()
                .firstOrNull { it.reason.contains("空闲自动断连") }
            assertTrue(idle != null)
            assertEquals(0, manager.onlineCount())
        } finally {
            manager.close()
        }
    }

    @Test fun active_channel_is_not_disconnected() {
        val events = CopyOnWriteArrayList<ChannelEvent>()
        var now = 1_000L
        val manager = ChannelManager(
            transport = FakeTransport(),
            commandQueue = CommandQueue { _, _ -> true },
            onMeasurement = { _, _, _ -> },
            onEvent = { events.add(it) },
            clock = { now }
        )
        try {
            manager.requestConnect("AA:01")
            manager.onReady("AA:01")
            now += 10 * minute - 1_000L
            manager.onBytes("AA:01", "DAT0:m+155.15".toByteArray(Charsets.US_ASCII))
            now += 30_000L
            manager.pollIdle(idleTimeoutMs = 10 * minute)
            assertEquals(1, manager.onlineCount())
            assertTrue(events.none { it is ChannelEvent.Disconnected })
        } finally {
            manager.close()
        }
    }
}
