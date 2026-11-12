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
 * 单通道状态机：独立 FrameBuffer，按通道实例化，避免 7 路字节流串帧（基线 10.5）。
 * 纯 Kotlin，不持有 Android View / Handler。
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
                // A3：握手回包只在完整帧解析成功后回，半帧不回。
                driver.ackBytesFor(frame)?.let { sink.onWrite(it) }
            }
            is CaliperFrame.Ack -> sink.onAck(frame)
            is CaliperFrame.Unknown -> sink.onUnknown(text)
        }
    }
}
