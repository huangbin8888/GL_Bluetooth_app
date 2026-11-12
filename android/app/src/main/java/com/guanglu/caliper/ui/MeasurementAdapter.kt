package com.guanglu.caliper.ui

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.guanglu.caliper.core.database.entity.Measurement
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.Units
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2 合并页的两列表格：时间 + 数值。通道用行内 CH 色标、判定用左侧色条、
 * 备注与来源收进点行弹窗，均不占列（基线 10.4）。
 */
class MeasurementAdapter(
    private val onRowClick: (Measurement) -> Unit
) : RecyclerView.Adapter<MeasurementAdapter.RowHolder>() {

    private var items: List<Measurement> = emptyList()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    fun submit(list: List<Measurement>) {
        items = list
        notifyDataSetChanged()
    }

    class RowHolder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val verdictBar: View = root.getChildAt(0)
        val time: TextView = root.getChildAt(1) as TextView
        val value: TextView = root.getChildAt(2) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val ctx = parent.context
        val bar = View(ctx).apply { setBackgroundColor(Color.TRANSPARENT) }
        val time = TextView(ctx).apply { textSize = 13f; setTextColor(0xFF16202F.toInt()) }
        val value = TextView(ctx).apply { textSize = 15f; setTextColor(0xFF16202F.toInt()) }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(10))
            addView(bar, LinearLayout.LayoutParams(ctx.dp(4), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(time, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return RowHolder(row)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val item = items[position]
        holder.time.text = clock.format(Date(item.capturedAt))
        val badge = if (item.legacyChannel) "历史" else "CH${item.channelNo}"
        val text = SpannableString("$badge  ${Units.formatMm(item.valueUm, 2)} mm")
        text.setSpan(
            ForegroundColorSpan(ChannelPalette.colorOf(item.channelNo)),
            0,
            badge.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        holder.value.text = text
        holder.verdictBar.setBackgroundColor(
            when (item.verdict) {
                "PASS" -> 0xFF0F9D58.toInt()
                "FAIL" -> 0xFFE14B3B.toInt()
                else -> Color.TRANSPARENT
            }
        )
        holder.root.setOnClickListener { onRowClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
