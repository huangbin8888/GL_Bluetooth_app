package com.guanglu.caliper

import com.guanglu.caliper.core.bluetooth.BleConnectionCallback
import com.guanglu.caliper.core.bluetooth.BleTransport
import com.guanglu.caliper.core.bluetooth.CommandQueue
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.channel.ChannelEvent
import com.guanglu.caliper.core.bluetooth.channel.ChannelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * NAM 改名：写失败、回包超时、OK/ERR 回包与断连清理的回归测试。
 *
 * @author AI-Generated
 */
class NamCommandTest {

    private class FakeTransport : BleTransport {
        override fun startScan(onDevice: (ScannedDevice) -> Unit) = Unit
        override fun stopScan() = Unit
        override fun connect(mac: String, callback: BleConnectionCallback) = Unit
        override fun disconnect(mac: String) = Unit
        override fun write(mac: String, bytes: ByteArray): Boolean = true
        override fun isConnected(mac: String): Boolean = false
    }

    private val events = CopyOnWriteArrayList<ChannelEvent>()

    private fun manager(writeOk: Boolean, namTimeoutMs: Long = 3_000L): ChannelManager =
        ChannelManager(
            transport = FakeTransport(),
            commandQueue = CommandQueue { _, _ -> writeOk },
            onMeasurement = { _, _, _ -> },
            onEvent = { events.add(it) },
            namTimeoutMs = namTimeoutMs
        )

    private fun online(m: ChannelManager, mac: String = "AA:01") {
        m.requestConnect(mac)
        m.onReady(mac)
        events.clear()
    }

    private fun namResults(): List<ChannelEvent.NamResult> =
        events.filterIsInstance<ChannelEvent.NamResult>()

    @Test fun write_failure_reports_failed_nam_immediately() {
        val m = manager(writeOk = false)
        try {
            online(m)
            assertTrue(m.applyNam(1, "GL-BLE#0187"))
            val result = namResults().single()
            assertFalse(result.ok)
            assertTrue(result.reason.contains("写入失败"))
            assertEquals("GL-BLE#0187", result.name)
        } finally {
            m.close()
        }
    }

    @Test fun missing_reply_reports_timeout_instead_of_silence() {
        val m = manager(writeOk = true, namTimeoutMs = 40L)
        try {
            online(m)
            assertTrue(m.applyNam(1, "GL-BLE#0187"))
            Thread.sleep(250)
            val result = namResults().single()
            assertFalse(result.ok)
            assertTrue(result.reason.contains("超时"))
        } finally {
            m.close()
        }
    }

    @Test fun nam_ok_reply_succeeds_and_cancels_timeout() {
        val m = manager(writeOk = true, namTimeoutMs = 100L)
        try {
            online(m)
            assertTrue(m.applyNam(1, "GL-BLE#0187"))
            m.onBytes("AA:01", "NAM:OK".toByteArray(Charsets.US_ASCII))
            m.pollIdle(System.currentTimeMillis() + 200)
            val result = namResults().single()
            assertTrue(result.ok)
            assertEquals("GL-BLE#0187", result.name)
            Thread.sleep(250)
            assertEquals(1, namResults().size)
        } finally {
            m.close()
        }
    }

    @Test fun nam_err_reply_reports_device_reason() {
        val m = manager(writeOk = true)
        try {
            online(m)
            assertTrue(m.applyNam(1, "GL-BLE#0187"))
            m.onBytes("AA:01", "NAM:ERR".toByteArray(Charsets.US_ASCII))
            m.pollIdle(System.currentTimeMillis() + 200)
            val result = namResults().single()
            assertFalse(result.ok)
            assertTrue(result.reason.contains("NAM:ERR"))
        } finally {
            m.close()
        }
    }

    @Test fun invalid_name_or_offline_channel_is_rejected_without_event() {
        val m = manager(writeOk = true)
        try {
            online(m)
            assertFalse(m.applyNam(1, "中文名称"))
            assertFalse(m.applyNam(4, "GL-BLE#0187"))
            assertTrue(namResults().isEmpty())
        } finally {
            m.close()
        }
    }

    @Test fun disconnect_cancels_pending_nam_without_late_result() {
        val m = manager(writeOk = true, namTimeoutMs = 40L)
        try {
            online(m)
            assertTrue(m.applyNam(1, "GL-BLE#0187"))
            m.disconnect("AA:01", "用户断开")
            Thread.sleep(250)
            assertTrue(namResults().isEmpty())
        } finally {
            m.close()
        }
    }
}
