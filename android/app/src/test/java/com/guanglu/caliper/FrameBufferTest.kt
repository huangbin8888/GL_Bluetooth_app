package com.guanglu.caliper

import com.guanglu.caliper.core.bluetooth.FrameBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBufferTest {

    @Test fun split_packet_is_reassembled_then_flushed() {
        val buffer = FrameBuffer()
        assertTrue(buffer.feed("DAT0:m+15", nowMs = 1000).isEmpty())
        assertTrue(buffer.feed("5.15", nowMs = 1005).isEmpty())
        assertTrue(buffer.shouldFlush(1070))
        assertEquals("DAT0:m+155.15", buffer.flush())
        assertFalse(buffer.hasPending())
    }

    @Test fun glued_packets_split_by_frame_header() {
        val buffer = FrameBuffer()
        val out = buffer.feed("DAT0:m+155.15DAT1:m+155.08", nowMs = 1000)
        assertEquals(listOf("DAT0:m+155.15"), out)
        assertEquals("DAT1:m+155.08", buffer.flush())
    }

    @Test fun ack_is_a_frame_too() {
        val buffer = FrameBuffer()
        val out = buffer.feed("MEAS:OKMOD:OK", nowMs = 1000)
        assertEquals(listOf("MEAS:OK"), out)
        assertEquals("MOD:OK", buffer.flush())
    }

    @Test fun trailing_noise_before_header_is_dropped() {
        val buffer = FrameBuffer()
        val out = buffer.feed("\u0000noiseDAT0:m+1.00DAT0:m+2.00", nowMs = 1000)
        assertEquals(1, out.size)
        assertEquals("DAT0:m+1.00", out[0])
    }

    @Test fun flush_on_empty_returns_null() {
        assertNull(FrameBuffer().flush())
    }
}
