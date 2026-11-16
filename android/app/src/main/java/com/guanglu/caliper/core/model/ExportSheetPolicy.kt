package com.guanglu.caliper.core.model

/**
 * 彩色表格中测量值是否需要标红：落库判定超差，或按当前公差重新判定超差。
 *
 * @author AI-Generated
 */
internal fun exportValueHighlighted(
    verdict: String,
    valueUm10: Long,
    lowerLimitUm10: Long?,
    upperLimitUm10: Long?
): Boolean = isOverLimit(verdict) || Units.judge(valueUm10, lowerLimitUm10, upperLimitUm10) == Verdict.FAIL

/** 通道号转中文通道名称，例如 3 -> 通道3。 */
internal fun channelDisplayName(channelNo: Int): String = "通道$channelNo"
