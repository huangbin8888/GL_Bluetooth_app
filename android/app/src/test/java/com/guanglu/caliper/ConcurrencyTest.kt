package com.guanglu.caliper

import com.guanglu.caliper.core.bluetooth.CommandQueue
import com.guanglu.caliper.core.bluetooth.channel.ChannelAllocator
import com.guanglu.caliper.core.usecase.Debouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrencyTest {

    @Test fun seventh_device_gets_last_slot_and_eighth_is_rejected() {
        val allocator = ChannelAllocator()
        for (i in 1..7) assertEquals(i, allocator.acquire("MAC$i"))
        assertNull(allocator.acquire("MAC8"))
        assertEquals(7, allocator.onlineCount())
    }

    @Test fun releasing_one_channel_does_not_disturb_the_others() {
        val allocator = ChannelAllocator()
        repeat(7) { allocator.acquire("MAC${it + 1}") }
        assertEquals(3, allocator.release("MAC3"))
        assertEquals(6, allocator.onlineCount())
        assertEquals(1, allocator.slotOf("MAC1"))
        assertEquals(7, allocator.slotOf("MAC7"))
        assertEquals(3, allocator.acquire("MAC8"))
    }

    @Test fun device_limit_downgrade_is_enforced() {
        val allocator = ChannelAllocator()
        allocator.setDeviceLimit(3)
        assertEquals(1, allocator.acquire("A"))
        assertEquals(2, allocator.acquire("B"))
        assertEquals(3, allocator.acquire("C"))
        assertNull(allocator.acquire("D"))
        assertEquals(3, allocator.deviceLimit)
    }

    @Test fun command_queue_writes_serially() {
        val writes = mutableListOf<String>()
        val queue = CommandQueue { mac, bytes ->
            writes += "$mac:${String(bytes, Charsets.US_ASCII)}"
            true
        }
        queue.submit("A", "MEAS".toByteArray())
        queue.submit("B", "CLR".toByteArray())
        assertEquals(listOf("A:MEAS"), writes)
        queue.onWriteCompleted(true)
        assertEquals(listOf("A:MEAS", "B:CLR"), writes)
    }

    @Test fun cancelling_one_channel_keeps_other_commands() {
        val writes = mutableListOf<String>()
        val queue = CommandQueue { mac, bytes ->
            writes += "$mac:${String(bytes, Charsets.US_ASCII)}"
            true
        }
        queue.submit("A", "MEAS".toByteArray())
        queue.submit("A", "CLR".toByteArray())
        queue.submit("B", "MOD:1".toByteArray())
        queue.cancelFor("A")
        queue.onWriteCompleted(true)
        queue.onWriteCompleted(true)
        assertTrue(writes.contains("B:MOD:1"))
        assertFalse(writes.contains("A:CLR"))
    }

    @Test fun seven_channels_with_the_same_reading_each_get_a_row() {
        val debouncer = Debouncer()
        for (channel in 1..7) {
            assertTrue(debouncer.shouldAccept(channel, 155150L, 1000L))
        }
        assertFalse(debouncer.shouldAccept(1, 155150L, 1100L))
        debouncer.clear(1)
        assertTrue(debouncer.shouldAccept(1, 155150L, 1100L))
        assertFalse(debouncer.shouldAccept(2, 155150L, 1100L))
    }

    @Test fun debounce_honours_value_delta_and_time_window() {
        val debouncer = Debouncer()
        assertTrue(debouncer.shouldAccept(1, 155150L, 1000L))
        assertFalse(debouncer.shouldAccept(1, 155155L, 1200L))  // ≤10um 且 ≤300ms
        assertTrue(debouncer.shouldAccept(1, 155155L, 1400L))   // 超窗
        assertTrue(debouncer.shouldAccept(1, 155300L, 1450L))   // 值变化 >10um
    }
}
