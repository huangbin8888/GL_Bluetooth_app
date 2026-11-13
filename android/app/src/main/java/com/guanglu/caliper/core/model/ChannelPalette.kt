package com.guanglu.caliper.core.model

/**
 * 固定 7 色编码表。
 *
 * @author AI-Generated
 */
object ChannelPalette {
    const val MAX_CHANNELS = 7

    // CH1 蓝 → CH7 红，顺序与通道号一一对应
    val COLORS: IntArray = intArrayOf(
        0xFF1668E3.toInt(),
        0xFF0F9D58.toInt(),
        0xFFF5A623.toInt(),
        0xFF8E44AD.toInt(),
        0xFFE5397F.toInt(),
        0xFF00A6A6.toInt(),
        0xFFE14B3B.toInt()
    )

    fun isValid(channelNo: Int): Boolean = channelNo in 1..MAX_CHANNELS

    fun colorOf(channelNo: Int): Int =
        if (isValid(channelNo)) COLORS[channelNo - 1] else 0xFF6B778C.toInt()

    fun labelOf(channelNo: Int): String = if (isValid(channelNo)) "CH$channelNo" else "历史通道"
}
