package com.guanglu.caliper.ui.chart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import com.guanglu.caliper.core.model.CHART_GRID_LINES
import com.guanglu.caliper.core.model.CHART_TIME_TICKS
import com.guanglu.caliper.core.model.ChartSample
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.TrendTone
import com.guanglu.caliper.core.model.Units
import com.guanglu.caliper.core.model.chartAxisTicks
import com.guanglu.caliper.core.model.chartTimeTicks
import com.guanglu.caliper.core.model.chartValueLabelIndices
import com.guanglu.caliper.core.model.formatChartTime
import com.guanglu.caliper.core.model.isOverLimit
import com.guanglu.caliper.core.model.trendMark
import com.guanglu.caliper.core.model.trendToneOf
import com.guanglu.caliper.ui.Web
import com.guanglu.caliper.ui.dp
import kotlin.math.roundToLong

/**
 * 股市分时图风格的原生 Canvas 图表：数值刻度、时间轴、关键点标注与最新值高亮。
 *
 * @author AI-Generated
 */
class ChartCanvasView(context: Context) : View(context) {

    enum class Mode { LINE, BAR }

    var mode: Mode = Mode.LINE
    /** 旧版通道与数值对；新版页面改用 samples，此属性保留供导出兼容。 */
    var points: List<Pair<Int, Long>> = emptyList()
    /** 带采集时刻的样本，用于横轴时间线与最新值高亮。 */
    var samples: List<ChartSample> = emptyList()
    var decimals: Int = 4
    var lowerUm10: Long? = null
    var upperUm10: Long? = null

    private val textSizePx = context.dp(10).toFloat()
    private val smallTextSizePx = context.dp(9).toFloat()
    private val dash = DashPathEffect(floatArrayOf(8f, 8f), 0f)

    private val seriesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Web.LINE
        strokeWidth = 1.5f
        pathEffect = dash
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Web.LINE
        strokeWidth = 2f
    }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5A623.toInt()
        strokeWidth = 2.5f
        pathEffect = dash
    }
    private val meanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Web.PRIMARY
        strokeWidth = 2f
        pathEffect = dash
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Web.SUB
        textSize = textSizePx
    }
    private val rightTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Web.SUB
        textSize = smallTextSizePx
        textAlign = Paint.Align.RIGHT
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Web.TEXT
        textSize = textSizePx
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Web.SUB
        textSize = smallTextSizePx
        textAlign = Paint.Align.CENTER
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = textSizePx
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        if (width == 0 || height == 0) return
        val data = samples
        if (data.isEmpty()) {
            canvas.drawText("暂无测量数据", context.dp(24).toFloat(), (height / 2).toFloat(), textPaint)
            return
        }
        val plotLeft = context.dp(12).toFloat()
        val plotRight = width - context.dp(58).toFloat()
        val plotTop = context.dp(28).toFloat()
        val plotBottom = height - context.dp(36).toFloat()
        val values = data.map { it.valueUm10 }
        val (low, high) = bounds(values)
        val span = (high - low).coerceAtLeast(1L).toFloat()
        val yOf: (Long) -> Float = { value ->
            plotBottom - (plotBottom - plotTop) * (value - low).toFloat() / span
        }
        val xOf: (Int) -> Float = { index ->
            plotLeft + (plotRight - plotLeft) * index / (data.size - 1).coerceAtLeast(1)
        }
        canvas.drawText(
            "${ChannelPalette.labelOf(data.last().channelNo)} · ${data.size} 点 · mm",
            plotLeft, plotTop - context.dp(8), textPaint
        )
        drawGrid(canvas, plotLeft, plotRight, low, high, values, yOf)
        drawSeries(canvas, data, plotLeft, plotRight, plotBottom, xOf, yOf)
        drawLatestBadge(canvas, data, plotLeft, plotRight, plotTop, xOf, yOf)
        drawTimeAxis(canvas, data, plotLeft, plotRight, plotBottom)
    }

    /** 纵轴范围包含数据、公差线并留 12% 余量，避免点被裁到视图外。 */
    private fun bounds(values: List<Long>): Pair<Long, Long> {
        val dataMin = values.minOrNull() ?: 0L
        val dataMax = values.maxOrNull() ?: 1L
        val min = minOf(dataMin, lowerUm10 ?: dataMin)
        val max = maxOf(dataMax, upperUm10 ?: dataMax)
        val pad = ((max - min).coerceAtLeast(1L) * 0.12).toLong().coerceAtLeast(1L)
        return (min - pad) to (max + pad)
    }

    /** 右侧价格轴、网格线与上/下限、均值线。 */
    private fun drawGrid(
        canvas: Canvas,
        plotLeft: Float,
        plotRight: Float,
        low: Long,
        high: Long,
        values: List<Long>,
        yOf: (Long) -> Float
    ) {
        val rightX = width - context.dp(4).toFloat()
        chartAxisTicks(low, high, CHART_GRID_LINES).forEach { tick ->
            val y = yOf(tick)
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            canvas.drawText(Units.formatMm(tick, decimals), rightX, y + smallTextSizePx / 3, rightTextPaint)
        }
        lowerUm10?.let { drawGuide(canvas, plotLeft, plotRight, rightX, yOf(it), "下限", limitPaint) }
        upperUm10?.let { drawGuide(canvas, plotLeft, plotRight, rightX, yOf(it), "上限", limitPaint) }
        val average = values.average().roundToLong()
        val meanLabel = "均 ${Units.formatMm(average, decimals)}"
        drawGuide(canvas, plotLeft, plotRight, rightX, yOf(average), meanLabel, meanPaint)
    }

    private fun drawGuide(
        canvas: Canvas,
        plotLeft: Float,
        plotRight: Float,
        rightX: Float,
        y: Float,
        label: String,
        paint: Paint
    ) {
        canvas.drawLine(plotLeft, y, plotRight, y, paint)
        canvas.drawText(label, rightX, y - context.dp(3), rightTextPaint)
    }

    /** 折线或柱状，并在首/尾/极值或全部点上标注 mm 数值。 */
    private fun drawSeries(
        canvas: Canvas,
        data: List<ChartSample>,
        plotLeft: Float,
        plotRight: Float,
        plotBottom: Float,
        xOf: (Int) -> Float,
        yOf: (Long) -> Float
    ) {
        val labelled = chartValueLabelIndices(data.map { it.valueUm10 })
        if (mode == Mode.LINE) {
            seriesPaint.style = Paint.Style.STROKE
            data.forEachIndexed { index, sample ->
                if (index == 0) return@forEachIndexed
                seriesPaint.color = ChannelPalette.colorOf(sample.channelNo)
                canvas.drawLine(
                    xOf(index - 1), yOf(data[index - 1].valueUm10),
                    xOf(index), yOf(sample.valueUm10), seriesPaint
                )
            }
        } else {
            val barWidth = (plotRight - plotLeft) / data.size.coerceAtLeast(1) * 0.6f
            seriesPaint.style = Paint.Style.FILL
            data.forEachIndexed { index, sample ->
                val x = xOf(index)
                val y = yOf(sample.valueUm10)
                seriesPaint.color = if (isOverLimit(sample.verdict)) {
                    Web.BAD
                } else {
                    ChannelPalette.colorOf(sample.channelNo)
                }
                canvas.drawRect(x, y, x + barWidth, plotBottom, seriesPaint)
            }
        }
        data.forEachIndexed { index, sample ->
            val x = xOf(index)
            val y = yOf(sample.valueUm10)
            val bad = isOverLimit(sample.verdict)
            if (mode == Mode.LINE) {
                seriesPaint.style = Paint.Style.FILL
                seriesPaint.color = if (bad) Web.BAD else ChannelPalette.colorOf(sample.channelNo)
                canvas.drawCircle(x, y, if (bad) 7f else 5f, seriesPaint)
            }
            if (index in labelled) {
                valuePaint.color = if (bad) Web.BAD else Web.TEXT
                canvas.drawText(
                    Units.formatMm(sample.valueUm10, decimals),
                    x,
                    (y - context.dp(7)).coerceAtLeast(context.dp(28) + textSizePx),
                    valuePaint
                )
            }
        }
    }

    /** 最新值行情标签：涨红跌绿，超差优先标红。 */
    private fun drawLatestBadge(
        canvas: Canvas,
        data: List<ChartSample>,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        xOf: (Int) -> Float,
        yOf: (Long) -> Float
    ) {
        val last = data.last()
        val previous = data.getOrElse(data.lastIndex - 1) { last }
        val tone = trendToneOf(last.valueUm10, previous.valueUm10)
        val color = when {
            isOverLimit(last.verdict) -> Web.BAD
            tone == TrendTone.UP -> Web.BAD
            tone == TrendTone.DOWN -> Web.OK
            else -> Web.PRIMARY
        }
        val x = xOf(data.lastIndex)
        val y = yOf(last.valueUm10)
        seriesPaint.style = Paint.Style.FILL
        seriesPaint.color = color
        canvas.drawCircle(x, y, 8f, seriesPaint)
        seriesPaint.style = Paint.Style.STROKE
        seriesPaint.strokeWidth = 2.5f
        canvas.drawCircle(x, y, 13f, seriesPaint)
        seriesPaint.strokeWidth = 4f
        val label = "${trendMark(tone)} ${Units.formatMm(last.valueUm10, decimals)}"
        val badgeWidth = valuePaint.measureText(label) + context.dp(12)
        val badgeLeft = (x - badgeWidth / 2)
            .coerceAtLeast(plotLeft)
            .coerceAtMost((plotRight - badgeWidth).coerceAtLeast(plotLeft))
        val badgeTop = (y - context.dp(32)).coerceAtLeast(plotTop)
        badgePaint.color = color
        canvas.drawRoundRect(
            badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + context.dp(22),
            context.dp(6).toFloat(), context.dp(6).toFloat(), badgePaint
        )
        canvas.drawText(
            label, badgeLeft + context.dp(6), badgeTop + context.dp(16), badgeTextPaint
        )
    }

    /** 底部时间线：等距 HH:mm:ss 刻度，展示数据的时间跨度。 */
    private fun drawTimeAxis(
        canvas: Canvas,
        data: List<ChartSample>,
        plotLeft: Float,
        plotRight: Float,
        plotBottom: Float
    ) {
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint)
        val times = chartTimeTicks(data.first().capturedAt, data.last().capturedAt, CHART_TIME_TICKS)
        times.forEachIndexed { index, time ->
            val x = plotLeft + (plotRight - plotLeft) * index / CHART_TIME_TICKS
            canvas.drawLine(x, plotBottom, x, plotBottom + context.dp(4), axisPaint)
            val minX = plotLeft + context.dp(24)
            val maxX = (plotRight - context.dp(24)).coerceAtLeast(minX)
            canvas.drawText(formatChartTime(time), x.coerceIn(minX, maxX), plotBottom + context.dp(18), timePaint)
        }
    }

    /** 把 View 绘制到 Bitmap。 */
    fun exportBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(
            width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888
        )
        draw(Canvas(bitmap))
        return bitmap
    }

    fun labelOf(valueUm10: Long): String = Units.formatMm(valueUm10, decimals)
}
