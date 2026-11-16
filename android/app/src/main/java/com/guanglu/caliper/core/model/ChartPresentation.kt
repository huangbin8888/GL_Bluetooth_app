package com.guanglu.caliper.core.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 图表样本：带采集时刻，用于横轴时间线与最新值高亮。
 *
 * @author AI-Generated
 */
data class ChartSample(
    val channelNo: Int,
    val valueUm10: Long,
    val capturedAt: Long,
    val verdict: String = ""
)

/** 纵轴网格线数量与横轴时间刻度数量。 */
internal const val CHART_GRID_LINES = 4
internal const val CHART_TIME_TICKS = 4

/** 逐点标注的最大样本数，超过后只标首/尾/最高/最低，避免文字重叠。 */
internal const val CHART_FULL_LABEL_LIMIT = 8

/** 涨跌色调：按 A 股习惯，上行红、下行绿。 */
internal enum class TrendTone { UP, DOWN, FLAT }

internal fun trendToneOf(current: Long, previous: Long): TrendTone = when {
    current > previous -> TrendTone.UP
    current < previous -> TrendTone.DOWN
    else -> TrendTone.FLAT
}

internal fun trendMark(tone: TrendTone): String = when (tone) {
    TrendTone.UP -> "↑"
    TrendTone.DOWN -> "↓"
    TrendTone.FLAT -> "—"
}

/** 生成含两端的等距数值刻度（0.1µm 整数），用于右侧价格轴。 */
internal fun chartAxisTicks(minUm10: Long, maxUm10: Long, count: Int = CHART_GRID_LINES): List<Long> {
    if (count < 1) return listOf(minUm10)
    if (maxUm10 <= minUm10) return List(count + 1) { minUm10 }
    return List(count + 1) { index -> minUm10 + ((maxUm10 - minUm10) * index / count) }
}

/** 生成含两端的等距时间刻度，用于横轴时间线。 */
internal fun chartTimeTicks(firstAt: Long, lastAt: Long, count: Int = CHART_TIME_TICKS): List<Long> {
    if (count < 1) return listOf(firstAt)
    if (lastAt <= firstAt) return List(count + 1) { firstAt }
    return List(count + 1) { index -> firstAt + ((lastAt - firstAt) * index / count) }
}

/** 需要标注数值的点：样本少时逐点标注，多时只标首、尾、最高、最低。 */
internal fun chartValueLabelIndices(values: List<Long>): Set<Int> {
    if (values.isEmpty()) return emptySet()
    if (values.size <= CHART_FULL_LABEL_LIMIT) return values.indices.toSet()
    val max = values.maxOrNull() ?: 0L
    val min = values.minOrNull() ?: 0L
    return setOf(0, values.lastIndex, values.indexOf(max), values.indexOf(min))
}

/** 行情式概览：最新值、涨跌、高低、均值与起止时间。 */
internal fun chartHeadline(samples: List<ChartSample>, decimals: Int): String {
    if (samples.isEmpty()) return "暂无测量数据 · 连接卡尺后自动绘制"
    val values = samples.map { it.valueUm10 }
    val latest = values.last()
    val previous = values.getOrElse(values.lastIndex - 1) { latest }
    val tone = trendToneOf(latest, previous)
    val delta = Units.formatMm(latest - previous, decimals)
    val deltaText = when (tone) {
        TrendTone.UP -> "+$delta"
        TrendTone.DOWN -> delta
        TrendTone.FLAT -> "0"
    }
    val average = values.average().roundToLong()
    return "最新 ${Units.formatMm(latest, decimals)} mm  ${trendMark(tone)} $deltaText" +
        "  ·  高 ${Units.formatMm(values.maxOrNull() ?: 0L, decimals)}" +
        "  ·  低 ${Units.formatMm(values.minOrNull() ?: 0L, decimals)}" +
        "  ·  均 ${Units.formatMm(average, decimals)}" +
        "  ·  ${formatChartTime(samples.first().capturedAt)} → ${formatChartTime(samples.last().capturedAt)}"
}

/** 横轴时间刻度文字。 */
internal fun formatChartTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(epochMs))
