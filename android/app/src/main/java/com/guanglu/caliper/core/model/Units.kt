package com.guanglu.caliper.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * mm / inch 到 0.1 微米整数（1 单位 = 10^-4 mm）的换算、显示与判定；4 位小数的 mm 值无损存储。
 *
 * @author AI-Generated
 */
object Units {
    private val MM_TO_UM10 = BigDecimal("10000")
    private val INCH_TO_UM10 = BigDecimal("254000")
    private val UM10_TO_MM = BigDecimal("10000")

    /** mm 值最多 4 位小数，×10^4 后为精确整数；inch 换算到 0.1µm 粒度；超出 4 位小数返回 null。 */
    fun toUm10(valueText: String, unit: Char): Long? {
        val raw = try {
            BigDecimal(valueText.trim())
        } catch (e: NumberFormatException) {
            return null
        }
        if (raw.scale() > 4) return null
        val factor = when (unit) {
            'm' -> MM_TO_UM10
            'i' -> INCH_TO_UM10
            else -> return null
        }
        return raw.multiply(factor).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    fun formatMm(valueUm10: Long, decimals: Int = 4): String =
        BigDecimal.valueOf(valueUm10)
            .divide(UM10_TO_MM, decimals, RoundingMode.HALF_UP)
            .toPlainString()

    fun judge(valueUm10: Long, lowerUm10: Long?, upperUm10: Long?): Verdict {
        if (lowerUm10 == null && upperUm10 == null) return Verdict.UNJUDGED
        if (lowerUm10 != null && valueUm10 < lowerUm10) return Verdict.FAIL
        if (upperUm10 != null && valueUm10 > upperUm10) return Verdict.FAIL
        return Verdict.PASS
    }
}
