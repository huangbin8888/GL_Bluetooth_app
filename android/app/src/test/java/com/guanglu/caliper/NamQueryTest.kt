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
 * NAM 查询回读卡尺真实广播名：回包、超时、写失败、离线、无请求回包与断连清理。
 *
 * @author AI-Generated
 */
class NamQueryTest {

    private class FakeTransport : BleTransport {
        override fun startScan(onDevice: (ScannedDevice) -> Unit) = Unit
        override fun stopScan() = Unit
        override fun connect(mac: String, callback: BleConnectionCallback) = Unit
        override fun disconnect(mac: String) = Unit
        override fun write(mac: String, bytes: ByteArray): Boolean = true
        override fun isConnected(mac: String): Boolean = false
    }

    private val events = CopyOnWriteArrayList<ChannelEvent>()
    private val diagnostics = CopyOnWriteArrayList<String>()

    private fun manager(writeOk: Boolean = true, timeoutMs: Long = 3_000L): ChannelManager =
        ChannelManager(
            transport = FakeTransport(),
            commandQueue = CommandQueue { _, _ -> writeOk },
            onMeasurement = { _, _, _ -> },
            onEvent = { events.add(it) },
            onDiagnostic = { diagnostics.add(it) },
            nameQueryTimeoutMs = timeoutMs
        )

    private fun online(m: ChannelManager, mac: String = "AA:01") {
        m.requestConnect(mac)
        m.onReady(mac)
        events.clear()
        diagnostics.clear()
    }

    private fun reports(): List<ChannelEvent.NameReport> =
        events.filterIsInstance<ChannelEvent.NameReport>()

    private fun flushTail(m: ChannelManager) = m.pollIdle(System.currentTimeMillis() + 200)

    @Test fun query_reply_carries_the_device_broadcast_name() {
        val m = manager()
        try {
            online(m)
            assertTrue(m.queryNam(1))
            m.onBytes("AA:01", "NAM:GL-BLE#0187".toByteArray(Charsets.US_ASCII))
            flushTail(m)
            val report = reports().single()
            assertTrue(report.ok)
            assertEquals("GL-BLE#0187", report.name)
            assertEquals(1, report.channelNo)
            assertEquals("AA:01", report.mac)
            assertTrue(diagnostics.any { it.contains("NAM_QUERY_REPLY") })
            Thread.sleep(150)
            assertEquals("reply must cancel the timeout", 1, reports().size)
        } finally {
            m.close()
        }
    }

    @Test fun missing_reply_reports_timeout_with_reason() {
        val m = manager(timeoutMs = 40L)
        try {
            online(m)
            assertTrue(m.queryNam(1))
            Thread.sleep(250)
            val report = reports().single()
            assertFalse(report.ok)
            assertTrue(report.name.isEmpty())
            assertTrue(report.reason.contains("超时"))
            assertTrue(diagnostics.any { it.contains("NAM_QUERY_TIMEOUT") })
        } finally {
            m.close()
        }
    }

    @Test fun write_failure_reports_reason_without_silence() {
        val m = manager(writeOk = false)
        try {
            online(m)
            assertTrue(m.queryNam(1))
            val report = reports().single()
            assertFalse(report.ok)
            assertTrue(report.reason.contains("写入失败"))
            assertTrue(diagnostics.any { it.contains("NAM_QUERY_FAIL") })
        } finally {
            m.close()
        }
    }

    @Test fun offline_channel_is_rejected_with_diagnostic() {
        val m = manager()
        try {
            online(m)
            assertFalse(m.queryNam(4))
            assertTrue(reports().isEmpty())
            assertTrue(diagnostics.any { it.contains("NAM_QUERY_NO_CHANNEL") })
        } finally {
            m.close()
        }
    }

    @Test fun unsolicited_name_report_is_logged_but_not_emitted() {
        val m = manager()
        try {
            online(m)
            m.onBytes("AA:01", "NAM:GL-BLE#0187".toByteArray(Charsets.US_ASCII))
            flushTail(m)
            assertTrue(reports().isEmpty())
            assertTrue(diagnostics.any { it.contains("NAM_QUERY_UNSOLICITED") })
        } finally {
            m.close()
        }
    }

    @Test fun disconnect_cancels_pending_query_without_late_report() {
        val m = manager(timeoutMs = 40L)
        try {
            online(m)
            assertTrue(m.queryNam(1))
            m.disconnect("AA:01", "用户断开")
            Thread.sleep(250)
            assertTrue(reports().isEmpty())
        } finally {
            m.close()
        }
    }
}
