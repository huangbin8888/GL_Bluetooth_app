package com.guanglu.caliper.core.model

/** 超差蜂鸣的最小间隔（毫秒），避免连续采集时蜂鸣风暴。 */
internal const val BEEP_COOLDOWN_MS = 1_200L

/** 单轮提醒的蜂鸣脉冲参数：三连急促高音量，比单声更容易被听见。 */
internal const val BEEP_PULSE_COUNT = 3
internal const val BEEP_PULSE_DURATION_MS = 130L
internal const val BEEP_PULSE_GAP_MS = 110L
internal const val BEEP_VOLUME_PERCENT = 100

/** 振动波形与三声蜂鸣对齐；函数返回新数组避免调用方修改共享状态。 */
internal fun beepVibrationPattern(): LongArray = longArrayOf(0L, 130L, 110L, 130L, 110L, 130L)

/** 每声蜂鸣相对起点的延迟，用于主线程串行调度。 */
internal fun beepPulseDelays(
    count: Int = BEEP_PULSE_COUNT,
    durationMs: Long = BEEP_PULSE_DURATION_MS,
    gapMs: Long = BEEP_PULSE_GAP_MS
): List<Long> = List(count.coerceAtLeast(0)) { index -> index * (durationMs + gapMs) }

/**
 * 数值文字色调：常规（近黑）或超差（红）。
 *
 * @author AI-Generated
 */
internal enum class ValueTone { NORMAL, OVER_LIMIT }

/** 仅 FAIL 判定视为超差；PASS 与 UNJUDGED 均按正常显示。 */
internal fun isOverLimit(verdict: String): Boolean = verdict == "FAIL"

/** 按判定结果给出数值文字色调。 */
internal fun valueToneOf(verdict: String): ValueTone =
    if (isOverLimit(verdict)) ValueTone.OVER_LIMIT else ValueTone.NORMAL

/** 是否播放超差蜂鸣：必须超差、开关已开且冷却已过。 */
internal fun shouldBeep(
    overLimit: Boolean,
    enabled: Boolean,
    elapsedMs: Long,
    cooldownMs: Long = BEEP_COOLDOWN_MS
): Boolean = overLimit && enabled && elapsedMs >= cooldownMs
