package com.guanglu.caliper.core.bluetooth.channel

import com.guanglu.caliper.core.bluetooth.FrameBuffer
import com.guanglu.caliper.core.bluetooth.device.CaliperDevice
import com.guanglu.caliper.core.model.CaliperFrame

interface FrameSink {
    fun onMeasurement(frame: CaliperFrame.Measurement)
    fun onWrite(bytes: ByteArray)
    fun onAck(frame: CaliperFrame.Ack)
    fun onUnknown(text: String)
}

/**
 * 单通道状态机，按通道持有独立 FrameBuffer。
 *
 * @author AI-Generated
 */
class ChannelSlot(
    private val driver: CaliperDevice,
    private val sink: FrameSink
) {
    private val buffer = FrameBuffer()

    fun onBytes(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        val ascii = String(bytes, Charsets.US_ASCII)
        for (text in buffer.feed(ascii, nowMs)) handle(text)
    }

    /** 由上层每 60ms 轮询；空闲超时后把尾帧交出去解析。 */
    fun onIdle(nowMs: Long = System.currentTimeMillis()) {
        if (buffer.shouldFlush(nowMs)) buffer.flush()?.let { handle(it) }
    }

    private fun handle(text: String) {
        when (val frame = driver.accept(text)) {
            is CaliperFrame.Measurement -> {
                sink.onMeasurement(frame)
                // 完整帧解析成功后回握手回包。
                driver.ackBytesFor(frame)?.let { sink.onWrite(it) }
            }
            is CaliperFrame.Ack -> sink.onAck(frame)
            is CaliperFrame.Unknown -> sink.onUnknown(text)
        }
    }
}
