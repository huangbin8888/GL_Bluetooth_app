package com.guanglu.caliper.core.model

/** 通道筛选按钮网格每行的列数。 */
internal const val CHANNEL_FILTER_COLUMNS = 4

/** 通道筛选按钮网格允许的最大行数，超出即会改变页面高度。 */
internal const val CHANNEL_FILTER_MAX_ROWS = 2

/** 放大后的通道按钮文字字号。 */
internal const val CHIP_TEXT_SIZE_SP = 12f

/** 放大后的通道按钮横向内边距。 */
internal const val CHIP_PADDING_H_DP = 10

/** 放大后的通道按钮纵向内边距。 */
internal const val CHIP_PADDING_V_DP = 8

/** 放大后的通道按钮最小高度。 */
internal const val CHIP_MIN_HEIGHT_DP = 32

/** 放大后的通道圆点直径。 */
internal const val CHIP_DOT_DP = 9

/** 等宽网格中按钮左右两侧的间距。 */
internal const val CHIP_GAP_DP = 4

/** 等宽网格中相邻两行的间距。 */
internal const val CHIP_ROW_GAP_DP = 5

/** 返回按钮数占用的行数。 */
internal fun channelFilterRows(itemCount: Int): Int =
    if (itemCount <= 0) 0 else (itemCount + CHANNEL_FILTER_COLUMNS - 1) / CHANNEL_FILTER_COLUMNS

/** 返回一行补齐等宽网格所需的占位数量。 */
internal fun channelFilterPlaceholders(itemCount: Int): Int =
    if (itemCount <= 0) 0 else (CHANNEL_FILTER_COLUMNS - itemCount % CHANNEL_FILTER_COLUMNS) % CHANNEL_FILTER_COLUMNS

/** 判断按钮数是否仍在不增加页面高度的行数上限内。 */
internal fun channelFilterFits(itemCount: Int): Boolean =
    channelFilterRows(itemCount) <= CHANNEL_FILTER_MAX_ROWS
