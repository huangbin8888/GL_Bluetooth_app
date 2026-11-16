package com.guanglu.caliper

import com.guanglu.caliper.core.model.CHART_FULL_LABEL_LIMIT
import com.guanglu.caliper.core.model.CHART_GRID_LINES
import com.guanglu.caliper.core.model.CHART_TIME_TICKS
import com.guanglu.caliper.core.model.ChartSample
import com.guanglu.caliper.core.model.TrendTone
import com.guanglu.caliper.core.model.chartAxisTicks
import com.guanglu.caliper.core.model.chartHeadline
import com.guanglu.caliper.core.model.chartTimeTicks
import com.guanglu.caliper.core.model.chartValueLabelIndices
import com.guanglu.caliper.core.model.formatChartTime
import com.guanglu.caliper.core.model.trendMark
import com.guanglu.caliper.core.model.trendToneOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图表数值刻度、时间线与关键点标注的回归测试。
 *
 * @author AI-Generated
 */
class ChartPresentationTest {

    @Test
    fun axis_ticks_include_both_ends_and_are_ordered() {
        val ticks = chartAxisTicks(10_000L, 10_400L, CHART_GRID_LINES)
        assertEquals(CHART_GRID_LINES + 1, ticks.size)
        assertEquals(10_000L, ticks.first())
        assertEquals(10_400L, ticks.last())
        assertEquals(ticks.sorted(), ticks)
    }

    @Test
    fun axis_ticks_degenerate_range_does_not_divide_by_zero() {
        val ticks = chartAxisTicks(12_345L, 12_345L, CHART_GRID_LINES)
        assertEquals(CHART_GRID_LINES + 1, ticks.size)
        assertTrue(ticks.all { it == 12_345L })
        assertEquals(listOf(5L), chartAxisTicks(5L, 5L, 0))
    }

    @Test
    fun time_ticks_include_both_ends() {
        val first = 1_700_000_000_000L
        val last = first + 60_000L
        val ticks = chartTimeTicks(first, last, CHART_TIME_TICKS)
        assertEquals(CHART_TIME_TICKS + 1, ticks.size)
        assertEquals(first, ticks.first())
        assertEquals(last, ticks.last())
        assertEquals(ticks.sorted(), ticks)
        assertEquals(List(CHART_TIME_TICKS + 1) { first }, chartTimeTicks(first, first))
    }

    @Test
    fun short_series_labels_every_point() {
        val values = listOf(10L, 11L, 12L, 13L)
        assertEquals(values.indices.toSet(), chartValueLabelIndices(values))
        assertTrue(chartValueLabelIndices(List(CHART_FULL_LABEL_LIMIT) { it.toLong() }).size == CHART_FULL_LABEL_LIMIT)
    }

    @Test
    fun long_series_labels_only_extremes_head_and_tail() {
        val values = listOf(50L, 20L, 40L, 60L, 30L, 45L, 55L, 25L, 35L)
        val indices = chartValueLabelIndices(values)
        assertTrue(indices.contains(0))
        assertTrue(indices.contains(values.lastIndex))
        assertTrue(indices.contains(values.indexOf(60L)))
        assertTrue(indices.contains(values.indexOf(20L)))
        assertTrue(indices.size <= 4)
        assertTrue(chartValueLabelIndices(emptyList()).isEmpty())
    }

    @Test
    fun trend_tone_follows_a_share_convention() {
        assertEquals(TrendTone.UP, trendToneOf(12_000L, 11_000L))
        assertEquals(TrendTone.DOWN, trendToneOf(11_000L, 12_000L))
        assertEquals(TrendTone.FLAT, trendToneOf(11_000L, 11_000L))
        assertEquals("↑", trendMark(TrendTone.UP))
        assertEquals("↓", trendMark(TrendTone.DOWN))
    }

    @Test
    fun headline_shows_numeric_values_and_time_range() {
        val samples = listOf(
            ChartSample(1, 120_000L, 1_700_000_000_000L),
            ChartSample(1, 120_500L, 1_700_000_030_000L),
            ChartSample(1, 119_800L, 1_700_000_060_000L, "FAIL")
        )
        val text = chartHeadline(samples, 4)
        assertTrue(text.contains("最新"))
        assertTrue(text.contains("11.9800"))
        assertTrue(text.contains("高"))
        assertTrue(text.contains("低"))
        assertTrue(text.contains("均"))
        assertTrue(text.contains("→"))
        assertTrue(text.contains("↓"))
        assertTrue(chartHeadline(emptyList(), 4).contains("暂无"))
    }

    @Test
    fun chart_time_is_hh_mm_ss() {
        assertTrue(Regex("\\d{2}:\\d{2}:\\d{2}").matches(formatChartTime(1_700_000_000_000L)))
    }
}
