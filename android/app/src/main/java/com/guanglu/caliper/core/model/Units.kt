package com.guanglu.caliper.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 单位换算唯一入口。协议帧同时支持 m=mm 与 i=inch，落库前统一为整数微米，
 * 避免公差边界的浮点误判（V1开发基线 A2 / 4.2）。
 */
object Units {
    private val MM_TO_UM = BigDecimal("1000")
    private val INCH_TO_UM = BigDecimal("25400")
    private val UM_TO_MM = BigDecimal("1000")

    /** 最多 4 位小数；超出返回 null，由调用方记诊断日志并丢弃该帧。 */
    fun toMicrometres(valueText: String, unit: Char): Long? {
        val raw = try {
            BigDecimal(valueText.trim())
        } catch (e: NumberFormatException) {
            return null
        }
        if (raw.scale() > 4) return null
        val factor = when (unit) {
            'm' -> MM_TO_UM
            'i' -> INCH_TO_UM
            else -> return null
        }
        return raw.multiply(factor).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    fun formatMm(valueUm: Long, decimals: Int = 2): String =
        BigDecimal.valueOf(valueUm)
            .divide(UM_TO_MM, decimals, RoundingMode.HALF_UP)
            .toPlainString()

    fun judge(valueUm: Long, lowerUm: Long?, upperUm: Long?): Verdict {
        if (lowerUm == null && upperUm == null) return Verdict.UNJUDGED
        if (lowerUm != null && valueUm < lowerUm) return Verdict.FAIL
        if (upperUm != null && valueUm > upperUm) return Verdict.FAIL
        return Verdict.PASS
    }
}
