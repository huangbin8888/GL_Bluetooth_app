package com.guanglu.caliper.core.bluetooth

import com.guanglu.caliper.core.bluetooth.device.GuangluCaliperHandler

/**
 * 无结束符协议的拆包 / 粘包拼帧：按帧头切分，尾帧暂存，空闲 60ms 后 flush。
 * 帧头规则由 CaliperDevice 驱动提供，本类不持有任何协议字符串（分层约束 3）。
 * 纯 Kotlin，不依赖 Android，可用 JVM 单元测试覆盖。
 * M0 抓包若确认 notify 帧带 CR/LF/Tab 尾，可改为按结束符切帧（基线 12.6）。
 */
class FrameBuffer(
    private val idleFlushMs: Long = 60L,
    private val frameStart: Regex = GuangluCaliperHandler.FRAME_START_PATTERN
) {
    private var pending: String = ""
    private var lastAppendMs: Long = 0L

    /**
     * 追加一段 ASCII 字节流，返回已经可以确认为完整帧的文本。
     * 最后一段没有后续帧头，可能是被拆开的半帧，留在 pending 里等 flush。
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

    /** 空闲超时后交出尾帧；调用方负责在 Handler/Coroutine 定时器里判断 shouldFlush。 */
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
