package com.guanglu.caliper

import com.guanglu.caliper.ui.pages.CLEAR_CONFIRM_WINDOW_MS
import com.guanglu.caliper.ui.pages.ClearConfirm
import com.guanglu.caliper.ui.pages.MAX_TABLE_ROWS
import com.guanglu.caliper.ui.pages.TABLE_FRAME_HEIGHT_DP
import com.guanglu.caliper.ui.pages.shouldKeepInnerScroll
import com.guanglu.caliper.ui.pages.shouldRenderRows
import com.guanglu.caliper.ui.pages.tableFrameHeightDp
import com.guanglu.caliper.ui.pages.visibleRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检测页表格框固定高度与清除数据二次确认的回归测试。
 *
 * @author AI-Generated
 */
class CaptureTablePolicyTest {

    @Test
    fun frameHeightIsConstantRegardlessOfRowCount() {
        assertEquals(TABLE_FRAME_HEIGHT_DP, tableFrameHeightDp(0))
        assertEquals(TABLE_FRAME_HEIGHT_DP, tableFrameHeightDp(1))
        assertEquals(TABLE_FRAME_HEIGHT_DP, tableFrameHeightDp(50))
        assertEquals(TABLE_FRAME_HEIGHT_DP, tableFrameHeightDp(10_000))
    }

    @Test
    fun frameHeightRejectsNegativeRowCount() {
        assertEquals(0, tableFrameHeightDp(-1))
    }

    @Test
    fun visibleRowsCappedAtMax() {
        assertEquals(0, visibleRows(0))
        assertEquals(1, visibleRows(1))
        assertEquals(MAX_TABLE_ROWS, visibleRows(MAX_TABLE_ROWS))
        assertEquals(MAX_TABLE_ROWS, visibleRows(MAX_TABLE_ROWS + 1))
        assertEquals(MAX_TABLE_ROWS, visibleRows(10_000))
    }

    @Test
    fun firstTapOnlyArms() {
        val confirm = ClearConfirm()
        assertFalse(confirm.tap(1_000L))
        assertTrue(confirm.isArmed(1_000L))
    }

    @Test
    fun secondTapInsideWindowExecutesClear() {
        val confirm = ClearConfirm()
        assertFalse(confirm.tap(1_000L))
        assertTrue(confirm.tap(1_000L + CLEAR_CONFIRM_WINDOW_MS - 1))
        assertFalse(confirm.isArmed(1_000L + CLEAR_CONFIRM_WINDOW_MS - 1))
    }

    @Test
    fun secondTapAtWindowBoundaryDoesNotExecute() {
        val confirm = ClearConfirm()
        assertFalse(confirm.tap(1_000L))
        assertFalse(confirm.tap(1_000L + CLEAR_CONFIRM_WINDOW_MS))
        assertTrue(confirm.isArmed(1_000L + CLEAR_CONFIRM_WINDOW_MS))
    }

    @Test
    fun armedStateExpiresAtWindowBoundary() {
        val confirm = ClearConfirm()
        confirm.tap(1_000L)
        assertTrue(confirm.isArmed(1_000L + CLEAR_CONFIRM_WINDOW_MS - 1))
        assertFalse(confirm.isArmed(1_000L + CLEAR_CONFIRM_WINDOW_MS))
    }

    @Test
    fun resetEndsTheWindow() {
        val confirm = ClearConfirm()
        confirm.tap(1_000L)
        confirm.reset()
        assertFalse(confirm.isArmed(1_001L))
        assertFalse(confirm.tap(1_001L))
    }

    @Test
    fun clockMovingBackwardsNeverExecutes() {
        val confirm = ClearConfirm()
        confirm.tap(5_000L)
        assertFalse(confirm.isArmed(1_000L))
        assertFalse(confirm.tap(1_000L))
    }

    @Test
    fun afterExecutingTheNextTapArmsAgain() {
        val confirm = ClearConfirm()
        assertFalse(confirm.tap(1_000L))
        assertTrue(confirm.tap(1_500L))
        assertFalse(confirm.tap(2_000L))
        assertTrue(confirm.tap(2_500L))
    }

    @Test
    fun fingerMovingUpKeepsInnerScrollWhileRowsRemainBelow() {
        assertTrue(shouldKeepInnerScroll(30f, canScrollDown = true, canScrollUp = true))
    }

    @Test
    fun fingerMovingUpHandsBackToPageAtBottom() {
        assertFalse(shouldKeepInnerScroll(30f, canScrollDown = false, canScrollUp = true))
    }

    @Test
    fun fingerMovingDownHandsBackToPageAtTop() {
        assertFalse(shouldKeepInnerScroll(-30f, canScrollDown = true, canScrollUp = false))
    }

    @Test
    fun fingerMovingDownKeepsInnerScrollWhenRowsRemainAbove() {
        assertTrue(shouldKeepInnerScroll(-30f, canScrollDown = true, canScrollUp = true))
    }

    @Test
    fun zeroDeltaUsesUpwardDirection() {
        assertFalse(shouldKeepInnerScroll(0f, canScrollDown = true, canScrollUp = false))
        assertTrue(shouldKeepInnerScroll(0f, canScrollDown = true, canScrollUp = true))
    }

    @Test
    fun renderRowsIsPausedWhileDragging() {
        assertFalse(shouldRenderRows(interacting = true))
    }

    @Test
    fun renderRowsResumesAfterFingerLeaves() {
        assertTrue(shouldRenderRows(interacting = false))
    }

    @Test
    fun repeatedRefreshesDuringDragStayDeferredUntilInteractionEnds() {
        var interacting = true
        var pending = false
        var rendered = 0
        fun refresh() {
            if (shouldRenderRows(interacting)) {
                pending = false
                rendered++
            } else {
                pending = true
            }
        }
        repeat(5) { refresh() }
        assertEquals(0, rendered)
        assertTrue(pending)
        interacting = false
        refresh()
        assertEquals(1, rendered)
        assertFalse(pending)
    }
}
