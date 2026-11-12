package com.guanglu.caliper.ui.chart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.Units

/**
 * M0 前的可编译图表实现：原生 Canvas 自绘折线 / 柱状 + 公差参考线 + 按通道色着色。
 * 基线 5.1 的「首选 Vico」决策不推翻；M0 通过且确认 Vico 的 PNG 导出稳定后，
 * 在此接口下替换适配器即可。Vico API 未经本机编译验证，故 V1 骨架不引入其调用。
 */
class ChartCanvasView(context: Context) : View(context) {

    enum class Mode { LINE, BAR }

    var mode: Mode = Mode.LINE
    var points: List<Pair<Int, Long>> = emptyList()   // channelNo -> valueUm
    var lowerUm: Long? = null
    var upperUm: Long? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE2E7F0.toInt()
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B778C.toInt()
        textSize = 24f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        if (width == 0 || height == 0) return
        if (points.isEmpty()) {
            canvas.drawText("暂无测量数据", 24f, (height / 2).toFloat(), textPaint)
            return
        }
        val values = points.map { it.second }
        var min = (lowerUm ?: values.minOrNull() ?: 0L).toFloat()
        var max = (upperUm ?: values.maxOrNull() ?: 1L).toFloat()
        if (max - min < 1f) {
            min -= 1f
            max += 1f
        }
        val pad = 48f
        val chartHeight = height - pad * 2
        val yOf: (Long) -> Float = { v -> pad + chartHeight * (1f - (v - min) / (max - min)) }

        lowerUm?.let { drawLimit(canvas, yOf(it)) }
        upperUm?.let { drawLimit(canvas, yOf(it)) }
        canvas.drawLine(pad, height - pad, width - pad, height - pad, axisPaint)

        if (mode == Mode.LINE) drawLine(canvas, pad, yOf) else drawBars(canvas, pad, yOf)
    }

    private fun drawLimit(canvas: Canvas, y: Float) {
        paint.color = 0xFFF5A623.toInt()
        paint.style = Paint.Style.STROKE
        canvas.drawLine(48f, y, width - 48f, y, paint)
    }

    private fun drawLine(canvas: Canvas, pad: Float, yOf: (Long) -> Float) {
        val step = (width - pad * 2) / points.size.coerceAtLeast(2).toFloat()
        paint.style = Paint.Style.STROKE
        var previous: Pair<Float, Float>? = null
        points.forEachIndexed { index, point ->
            val x = pad + step * index
            val y = yOf(point.second)
            paint.color = ChannelPalette.colorOf(point.first)
            previous?.let { canvas.drawLine(it.first, it.second, x, y, paint) }
            canvas.drawCircle(x, y, 5f, paint)
            previous = x to y
        }
    }

    private fun drawBars(canvas: Canvas, pad: Float, yOf: (Long) -> Float) {
        val step = (width - pad * 2) / points.size.coerceAtLeast(1)
        paint.style = Paint.Style.FILL
        points.forEachIndexed { index, point ->
            val x = pad + step * index
            val y = yOf(point.second)
            paint.color = ChannelPalette.colorOf(point.first)
            canvas.drawRect(x, y, x + step * 0.6f, height - pad, paint)
        }
    }

    /** P6 图表 PNG 导出：View 自身绘制到 Bitmap，不依赖第三方库的截图 API。 */
    fun exportBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(
            width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888
        )
        draw(Canvas(bitmap))
        return bitmap
    }

    fun labelOf(valueUm: Long): String = Units.formatMm(valueUm, 2)
}
