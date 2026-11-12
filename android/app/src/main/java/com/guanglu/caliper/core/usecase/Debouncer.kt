package com.guanglu.caliper.core.usecase

import kotlin.math.abs

/**
 * A4 量化去重：同一通道内「值变化 ≤ 0.01mm(10um) 且间隔 ≤ 300ms」才丢弃。
 * 去重键含 channel_no，7 台同一读数必须各记一条（基线 10.3 跨通道不去重）。
 */
class Debouncer(
    private val sameValueDeltaUm: Long = 10L,
    private val windowMs: Long = 300L
) {
    private data class Sample(val valueUm: Long, val elapsedMs: Long)
    private val lastByChannel = HashMap<Int, Sample>()

    @Synchronized
    fun shouldAccept(channelNo: Int, valueUm: Long, elapsedMs: Long): Boolean {
        val previous = lastByChannel[channelNo]
        if (previous != null &&
            abs(valueUm - previous.valueUm) <= sameValueDeltaUm &&
            elapsedMs - previous.elapsedMs <= windowMs
        ) {
            return false
        }
        lastByChannel[channelNo] = Sample(valueUm, elapsedMs)
        return true
    }

    @Synchronized
    fun clear(channelNo: Int) {
        lastByChannel.remove(channelNo)
    }

    @Synchronized
    fun clearAll() {
        lastByChannel.clear()
    }
}
