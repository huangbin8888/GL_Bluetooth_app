package com.guanglu.caliper

import com.guanglu.caliper.core.bluetooth.BleConnectionCallback
import com.guanglu.caliper.core.bluetooth.BleTransport
import com.guanglu.caliper.core.bluetooth.CommandQueue
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.channel.ChannelManager
import com.guanglu.caliper.shouldAutoReconnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 连接幂等（重复占槽/重复连接）与掉线自动重连决策回归测试。
 *
 * @author AI-Generated
 */
class ConnectReconnectTest {

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

    private fun manager(transport: FakeTransport = FakeTransport()): ChannelManager =
        ChannelManager(
            transport = transport,
            commandQueue = CommandQueue { _, _ -> true },
            onMeasurement = { _, _, _ -> },
            onEvent = { }
        )

    @Test fun request_connect_twice_on_same_mac_does_not_double_connect() {
        val transport = FakeTransport()
        val m = manager(transport)
        try {
            assertTrue(m.requestConnect("AA:01"))
            assertTrue(m.requestConnect("AA:01"))
            assertEquals(1, transport.connects.filter { it == "AA:01" }.size)
        } finally {
            m.close()
        }
    }

    @Test fun request_connect_while_pending_returns_true_without_new_connect() {
        val transport = FakeTransport()
        val m = manager(transport)
        try {
            // 第一台进入 connecting；第二台进入 pending。
            assertTrue(m.requestConnect("AA:01"))
            assertTrue(m.requestConnect("BB:02"))
            // 重复请求 pending 中的第二台。
            assertTrue(m.requestConnect("BB:02"))
            assertEquals(1, transport.connects.size)
            assertEquals(setOf("AA:01", "BB:02"), m.pendingMacs())
        } finally {
            m.close()
        }
    }

    @Test fun request_connect_after_online_is_idempotent() {
        val transport = FakeTransport()
        val m = manager(transport)
        try {
            assertTrue(m.requestConnect("AA:01"))
            m.onReady("AA:01")
            assertTrue(m.requestConnect("AA:01"))
            assertEquals(1, transport.connects.filter { it == "AA:01" }.size)
            assertEquals(1, m.onlineCount())
        } finally {
            m.close()
        }
    }

    @Test fun pending_macs_includes_connecting_and_queued() {
        val transport = FakeTransport()
        val m = manager(transport)
        try {
            m.requestConnect("AA:01")
            m.requestConnect("BB:02")
            m.requestConnect("CC:03")
            assertEquals(setOf("AA:01", "BB:02", "CC:03"), m.pendingMacs())
        } finally {
            m.close()
        }
    }

    @Test fun should_auto_reconnect_decisions() {
        val online = setOf("AA:01")
        val pending = setOf("BB:02")

        // 关闭自动重连：不重连。
        assertFalse(shouldAutoReconnect(false, "AA:01", "连接超时", online, pending, true))
        // 用户主动断开：不重连。
        assertFalse(shouldAutoReconnect(true, "AA:01", "用户断开", online, pending, true))
        // 空闲自动断连：不重连。
        assertFalse(shouldAutoReconnect(true, "AA:01", "空闲自动断连", online, pending, true))
        // 已在在线集合：不重连。
        assertFalse(shouldAutoReconnect(true, "AA:01", "连接超时", online, pending, true))
        // 已在排队集合：不重连。
        assertFalse(shouldAutoReconnect(true, "BB:02", "连接超时", online, pending, true))
        // 未知设备：不重连。
        assertFalse(shouldAutoReconnect(true, "ZZ:99", "连接超时", online, pending, false))
        // 已知设备 + 信号丢失：重连。
        assertTrue(shouldAutoReconnect(true, "ZZ:99", "信号丢失", online, pending, true))
        // 已知设备 + 连接超时：重连。
        assertTrue(shouldAutoReconnect(true, "ZZ:99", "连接超时（15s 未收到服务发现回调）", online, pending, true))
    }
}
