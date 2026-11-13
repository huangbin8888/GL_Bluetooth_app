package com.guanglu.caliper.core.bluetooth

import com.guanglu.caliper.core.bluetooth.device.GuangluCaliperHandler

/**
 * 按帧头切分字节流，空闲 60ms 后交出尾帧。
 *
 * @author AI-Generated
 */
class FrameBuffer(
    private val idleFlushMs: Long = 60L,
    private val frameStart: Regex = GuangluCaliperHandler.FRAME_START_PATTERN
) {
    private var pending: String = ""
    private var lastAppendMs: Long = 0L

    /**
     * 追加一段 ASCII 字节流，返回已经可以确认为完整帧的文本。
     */
    fun feed(ascii: String, nowMs: Long = System.currentTimeMillis()): List<String> {
        if (ascii.isEmpty()) return emptyList()
        pending += ascii
        lastAppendMs = nowMs
        val starts = frameStart.findAll(pending).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        val complete = mutableListOf<String>()
        for (i in 0 until starts.size - 1) {
            val text = pending.substring(starts[i], starts[i + 1]).trim()
            if (text.isNotEmpty()) complete += text
        }
        pending = pending.substring(starts.last())
        return complete
    }

    /** 空闲超时后交出尾帧。 */
    fun flush(): String? {
        if (pending.isEmpty()) return null
        val text = pending.trim()
        pending = ""
        return text.ifEmpty { null }
    }

    fun shouldFlush(nowMs: Long = System.currentTimeMillis()): Boolean =
        pending.isNotEmpty() && nowMs - lastAppendMs >= idleFlushMs

    fun hasPending(): Boolean = pending.isNotEmpty()
}
