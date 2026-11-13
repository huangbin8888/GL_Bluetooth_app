package com.guanglu.caliper.core.usecase

import kotlin.math.abs

/**
 * 同通道重复读数去抖。
 *
 * @author AI-Generated
 */
class Debouncer(
    private val sameValueDeltaUm10: Long = 100L,
    private val windowMs: Long = 300L
) {
    private data class Sample(val valueUm10: Long, val elapsedMs: Long)
    private val lastByChannel = HashMap<Int, Sample>()

    @Synchronized
    fun shouldAccept(channelNo: Int, valueUm10: Long, elapsedMs: Long): Boolean {
        val previous = lastByChannel[channelNo]
        if (previous != null &&
            abs(valueUm10 - previous.valueUm10) <= sameValueDeltaUm10 &&
            elapsedMs - previous.elapsedMs <= windowMs
        ) {
            return false
        }
        lastByChannel[channelNo] = Sample(valueUm10, elapsedMs)
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
