package com.guanglu.caliper.core.model

/** 气泡停留时长：短消息明显短于系统 Toast，长消息略放宽但不超过上限。 */
internal const val BUBBLE_BASE_DURATION_MS = 1_500L
internal const val BUBBLE_MAX_DURATION_MS = 2_200L
internal const val BUBBLE_LONG_TEXT_LENGTH = 24

/** 气泡视觉：字号放大但宽度封顶，避免遮住页面正文。 */
internal const val BUBBLE_TEXT_SIZE_SP = 15f
internal const val BUBBLE_MAX_WIDTH_RATIO = 0.86f
internal const val BUBBLE_MAX_LINES = 2
internal const val BUBBLE_CORNER_RADIUS_DP = 10
internal const val BUBBLE_PADDING_H_DP = 14
internal const val BUBBLE_PADDING_V_DP = 9
internal const val BUBBLE_SIDE_MARGIN_DP = 18
internal const val BUBBLE_BOTTOM_MARGIN_DP = 68
internal const val BUBBLE_NORMAL_BG = 0xE616202F.toInt()
internal const val BUBBLE_ALERT_BG = 0xE6B42333.toInt()

/** 超差提醒文案前缀；命中时使用红色警示气泡。 */
internal const val OVER_LIMIT_BUBBLE_PREFIX = "⚠"

internal enum class BubbleTone { NORMAL, ALERT }

/** 按文案前缀区分普通提示与超差警示。 */
internal fun bubbleToneOf(message: String): BubbleTone =
    if (message.startsWith(OVER_LIMIT_BUBBLE_PREFIX)) BubbleTone.ALERT else BubbleTone.NORMAL

/** 气泡背景色；半透明保证下方文字仍可辨认。 */
internal fun bubbleFill(tone: BubbleTone): Int = when (tone) {
    BubbleTone.NORMAL -> BUBBLE_NORMAL_BG
    BubbleTone.ALERT -> BUBBLE_ALERT_BG
}

/** 按文案长度给出停留时长，长文放慢但不堆叠。 */
internal fun bubbleDurationMs(textLength: Int): Long =
    if (textLength <= BUBBLE_LONG_TEXT_LENGTH) BUBBLE_BASE_DURATION_MS else BUBBLE_MAX_DURATION_MS

/** 气泡最大宽度不超过屏宽的固定比例，避免变成遮挡整屏的横幅。 */
internal fun bubbleMaxWidthPx(screenWidthPx: Int, sideMarginPx: Int): Int {
    val usable = (screenWidthPx - sideMarginPx * 2).coerceAtLeast(0)
    return (usable * BUBBLE_MAX_WIDTH_RATIO).toInt().coerceAtLeast(sideMarginPx)
}

/** 超差视觉提醒文案；冷却由调用方 UiEventThrottle 控制。 */
internal fun overLimitAlertMessage(): String =
    "${OVER_LIMIT_BUBBLE_PREFIX} 检测到超差数据：数值已标红并伴有蜂鸣振动，请核对公差上下限"
