package com.guanglu.caliper.ui.pages

import android.content.Context
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 检测记录表格框：手势进入时独占纵向拖动，滚到边界后把滑动交还外层页面。
 *
 * @author AI-Generated
 */
internal class TableFrame(context: Context) : ScrollView(context) {

    /** 用户手指是否仍在表格框内。 */
    var interacting = false
        private set

    /** 手指离开后需要补一次刷新时回调。 */
    var onInteractionEnd: (() -> Unit)? = null

    private var downY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                interacting = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = downY - event.y
                parent?.requestDisallowInterceptTouchEvent(
                    shouldKeepInnerScroll(deltaY, canScrollVertically(1), canScrollVertically(-1))
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                interacting = false
                post { onInteractionEnd?.invoke() }
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

/**
 * 检测记录表格的可变引用集合。
 *
 * @author AI-Generated
 */
internal class LiveTable(
    val card: LinearLayout,
    val body: LinearLayout,
    val scope: TextView,
    val frame: TableFrame,
    val clearButton: TextView
) {
    var restoreScroll: Runnable? = null
    var pendingRender = false
}
