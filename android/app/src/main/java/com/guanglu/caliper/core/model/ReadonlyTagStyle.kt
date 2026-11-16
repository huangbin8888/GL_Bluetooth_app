package com.guanglu.caliper.core.model

/** 只读状态标签的圆角（dp）；小圆角描边，与胶囊状可点击 chip 区分。 */
internal const val READONLY_TAG_CORNER_RADIUS_DP = 4

/** 可点击通道筛选 chip 的圆角（dp）。 */
internal const val INTERACTIVE_CHIP_CORNER_RADIUS_DP = 18

/** 只读状态标签不可点击，也不接收焦点。 */
internal const val READONLY_TAG_CLICKABLE = false

/** 只读标签与可点击 chip 必须在视觉与交互上同时可区分。 */
internal fun readonlyTagIsDistinct(): Boolean =
    READONLY_TAG_CORNER_RADIUS_DP < INTERACTIVE_CHIP_CORNER_RADIUS_DP && !READONLY_TAG_CLICKABLE
