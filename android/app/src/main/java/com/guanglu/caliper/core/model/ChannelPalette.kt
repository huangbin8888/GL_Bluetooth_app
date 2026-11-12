package com.guanglu.caliper.core.model

/** 固定 7 色编码，全局一致；色标必须同时带 CHn 文字，不能只靠颜色（基线 10.4 / 11.2）。 */
object ChannelPalette {
    const val MAX_CHANNELS = 7

    val COLORS: IntArray = intArrayOf(
        0xFF1668E3.toInt(), // CH1 蓝
        0xFF0F9D58.toInt(), // CH2 绿
        0xFFF5A623.toInt(), // CH3 橙
        0xFF8E44AD.toInt(), // CH4 紫
        0xFFE5397F.toInt(), // CH5 品红
        0xFF00A6A6.toInt(), // CH6 青
        0xFFE14B3B.toInt()  // CH7 红
    )

    fun isValid(channelNo: Int): Boolean = channelNo in 1..MAX_CHANNELS

    fun colorOf(channelNo: Int): Int =
        if (isValid(channelNo)) COLORS[channelNo - 1] else 0xFF6B778C.toInt()

    fun labelOf(channelNo: Int): String = if (isValid(channelNo)) "CH$channelNo" else "历史通道"
}
