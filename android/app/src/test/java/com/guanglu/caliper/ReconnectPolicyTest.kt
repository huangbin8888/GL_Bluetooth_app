package com.guanglu.caliper

import com.guanglu.caliper.core.bluetooth.CommandQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 自动重连预算、主动断连前缀匹配、补连冷却、UI 事件节流与命令队列排空。
 *
 * @author AI-Generated
 */
class ReconnectPolicyTest {

    @Test
    fun manual_disconnect_reason_uses_prefix_match() {
        assertTrue(isManualDisconnect("用户断开"))
        assertTrue(isManualDisconnect("空闲自动断连（10 分钟无数据）"))
        assertTrue(isManualDisconnect("空闲自动断连（1 分钟无数据）"))
        assertFalse(isManualDisconnect("连接超时（15s 未收到服务发现回调）"))
        assertFalse(isManualDisconnect("CONNECTION_TIMEOUT"))
        assertFalse(isManualDisconnect(""))
    }

    @Test
    fun reconnect_stops_after_budget_exhausted() {
        val online = emptySet<String>()
        val pending = emptySet<String>()
        assertTrue(shouldAutoReconnect(true, "AA:01", "连接超时", online, pending, true, 0))
        assertTrue(
            shouldAutoReconnect(
                true, "AA:01", "连接超时", online, pending, true, MAX_AUTO_RECONNECT - 1
            )
        )
        assertFalse(
            shouldAutoReconnect(
                true, "AA:01", "连接超时", online, pending, true, MAX_AUTO_RECONNECT
            )
        )
        assertFalse(
            shouldAutoReconnect(
                true, "AA:01", "连接超时", online, pending, true, MAX_AUTO_RECONNECT + 5
            )
        )
    }

    @Test
    fun idle_disconnect_with_detail_suffix_does_not_trigger_reconnect() {
        assertFalse(
            shouldAutoReconnect(
                true, "AA:01", "空闲自动断连（5 分钟无数据）", emptySet(), emptySet(), true, 0
            )
        )
    }

    @Test
    fun auto_connect_cooldown_blocks_rapid_repeats() {
        assertTrue(shouldAutoConnectNow(0L, 10_000L))
        assertFalse(shouldAutoConnectNow(10_000L, 12_000L))
        assertTrue(shouldAutoConnectNow(10_000L, 15_001L))
        assertFalse(shouldAutoConnectNow(20_000L, 20_000L))
    }

    @Test
    fun ui_event_throttle_suppresses_duplicate_keys_in_window() {
        var now = 1_000L
        val throttle = UiEventThrottle(windowMs = 1_500L) { now }
        assertTrue(throttle.allow("ack:1:MEAS"))
        assertFalse(throttle.allow("ack:1:MEAS"))
        assertTrue(throttle.allow("ack:2:CLR"))
        now += 1_500L
        assertTrue(throttle.allow("ack:1:MEAS"))
        throttle.clear()
        assertTrue(throttle.allow("ack:2:CLR"))
    }

    @Test
    fun command_queue_drains_all_entries_when_write_always_fails() {
        var attempts = 0
        val results = CopyOnWriteArrayList<Boolean>()
        val queue = CommandQueue { _, _ ->
            attempts++
            false
        }
        repeat(2_000) {
            queue.submit("AA:01", "MEAS".toByteArray(Charsets.US_ASCII)) { ok -> results.add(ok) }
        }
        assertEquals(2_000, attempts)
        assertEquals(2_000, results.size)
        assertTrue(results.all { !it })
        assertEquals(0, queue.pendingCount())
    }
}
