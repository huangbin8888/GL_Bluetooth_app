package com.guanglu.caliper

import com.guanglu.caliper.core.bluetooth.device.CaliperCommand
import com.guanglu.caliper.core.bluetooth.device.GuangluCaliperHandler
import com.guanglu.caliper.core.model.CaliperFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test fun dat0_parses_with_sign_and_unit() {
        val frame = GuangluCaliperHandler.accept("DAT0:m+155.15") as CaliperFrame.Measurement
        assertEquals("DAT0", frame.source)
        assertEquals(1551500L, frame.valueUm10)
        assertEquals('m', frame.unit)
        assertEquals('+', frame.sign)
        assertEquals("155.15", frame.rawValue)
    }

    @Test fun dat1_inch_negative_is_converted() {
        val frame = GuangluCaliperHandler.accept("DAT1:i-1.2485") as CaliperFrame.Measurement
        assertEquals("DAT1", frame.source)
        assertEquals(-317119L, frame.valueUm10)
        assertEquals('i', frame.unit)
        assertEquals('-', frame.sign)
    }

    @Test fun dat0_requires_ack_but_dat1_does_not() {
        val dat0 = GuangluCaliperHandler.accept("DAT0:m+1.00")
        val dat1 = GuangluCaliperHandler.accept("DAT1:m+1.00")
        assertArrayEquals("DAT0:OK".toByteArray(), GuangluCaliperHandler.ackBytesFor(dat0))
        assertNull(GuangluCaliperHandler.ackBytesFor(dat1))
    }

    @Test fun unknown_frames_do_not_enter_the_database() {
        assertTrue(GuangluCaliperHandler.accept("DAT0:m+") is CaliperFrame.Unknown)
        assertTrue(GuangluCaliperHandler.accept("random") is CaliperFrame.Unknown)
        // 短值仍是合法帧：协议层无法凭内容判断完整性，靠 FrameBuffer 尾帧暂存 + 60ms flush。
        assertTrue(GuangluCaliperHandler.accept("DAT0:m+15") is CaliperFrame.Measurement)
    }

    @Test fun commands_use_exact_protocol_strings() {
        assertEquals("MEAS", String(GuangluCaliperHandler.commandBytes(CaliperCommand.MEAS)))
        assertEquals("CLR", String(GuangluCaliperHandler.commandBytes(CaliperCommand.CLR)))
        assertEquals("MOD:0", String(GuangluCaliperHandler.commandBytes(CaliperCommand.MOD_MANUAL)))
        assertEquals("MOD:1", String(GuangluCaliperHandler.commandBytes(CaliperCommand.MOD_CONTINUOUS)))
    }

    @Test fun nam_validation_keeps_prefix_and_16_char_limit() {
        assertTrue(GuangluCaliperHandler.validateNam("GL-BLE#0187"))
        assertFalse(GuangluCaliperHandler.validateNam("0187"))
        assertFalse(GuangluCaliperHandler.validateNam("GL-BLE#"))
        assertFalse(GuangluCaliperHandler.validateNam("GL-BLE#中文"))
        assertFalse(GuangluCaliperHandler.validateNam("GL-BLE#1234567890"))
        assertEquals(
            "NAM:GL-BLE#0187",
            String(GuangluCaliperHandler.buildNamCommand("GL-BLE#0187"))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalid_nam_never_reaches_the_characteristic() {
        GuangluCaliperHandler.buildNamCommand("bad")
    }

    @Test fun nam_and_error_acks_are_recognised() {
        assertEquals(CaliperFrame.Ack("NAM", true), GuangluCaliperHandler.accept("NAM:OK"))
        assertEquals(CaliperFrame.Ack("MOD", false, true), GuangluCaliperHandler.accept("MOD:ERR"))
    }

    @Test fun hid_token_reuses_the_same_parser() {
        val mm = GuangluCaliperHandler.fromHidToken("155.15") as CaliperFrame.Measurement
        assertEquals(1551500L, mm.valueUm10)
        val inch = GuangluCaliperHandler.fromHidToken("-1.2485 in") as CaliperFrame.Measurement
        assertEquals(-317119L, inch.valueUm10)
        assertTrue(GuangluCaliperHandler.fromHidToken("") is CaliperFrame.Unknown)
    }
}
