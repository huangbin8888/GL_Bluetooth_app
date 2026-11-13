package com.guanglu.caliper.core.usecase

import kotlin.math.abs

/**
 * 同通道重复读数去抖。
 *
 * @author AI-Generated
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
