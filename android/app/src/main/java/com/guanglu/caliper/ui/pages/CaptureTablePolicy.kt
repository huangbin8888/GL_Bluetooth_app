package com.guanglu.caliper.ui.pages

/** 检测记录表格框固定高度（dp）。 */
internal const val TABLE_FRAME_HEIGHT_DP = 220

/** 表格框内最多渲染的行数。 */
internal const val MAX_TABLE_ROWS = 50

/** 清除数据按钮两次点击的确认窗口（毫秒）。 */
internal const val CLEAR_CONFIRM_WINDOW_MS = 3_000L

/** 表格框高度恒定，不随记录条数增长。 */
internal fun tableFrameHeightDp(rowCount: Int): Int =
    if (rowCount < 0) 0 else TABLE_FRAME_HEIGHT_DP

/** 框内渲染的行数上限，超出部分不创建 View。 */
internal fun visibleRows(rowCount: Int): Int = minOf(rowCount, MAX_TABLE_ROWS)

/** 本次手势是否继续由表格框处理；滚到边界后交还外层页面。 */
internal fun shouldKeepInnerScroll(
    deltaY: Float,
    canScrollDown: Boolean,
    canScrollUp: Boolean
): Boolean = if (deltaY > 0f) canScrollDown else canScrollUp

/** 用户拖动表格框期间不重建行，避免刷新清空滚动位置。 */
internal fun shouldRenderRows(interacting: Boolean): Boolean = !interacting

/**
 * 清除数据按钮的二次确认状态机。
 *
 * @author AI-Generated
 */
internal class ClearConfirm(private val windowMs: Long = CLEAR_CONFIRM_WINDOW_MS) {

    private var armedAt = Long.MIN_VALUE

    /** 本次点击是否应执行清除。 */
    fun tap(now: Long): Boolean {
        val armed = isArmed(now)
        armedAt = if (armed) Long.MIN_VALUE else now
        return armed
    }

    /** 是否仍在等待再次点击的窗口内。 */
    fun isArmed(now: Long): Boolean {
        if (armedAt == Long.MIN_VALUE) return false
        val delta = now - armedAt
        return delta >= 0 && delta < windowMs
    }

    /** 结束等待窗口。 */
    fun reset() {
        armedAt = Long.MIN_VALUE
    }
}
