package com.guanglu.caliper.core.model

/** 超差蜂鸣的最小间隔（毫秒），避免连续采集时蜂鸣风暴。 */
internal const val BEEP_COOLDOWN_MS = 1_500L

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
