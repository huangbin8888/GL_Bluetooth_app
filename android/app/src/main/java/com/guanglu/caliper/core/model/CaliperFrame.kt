package com.guanglu.caliper.core.model

/**
 * 协议解析结果与落库前的领域对象。
 *
 * @author AI-Generated
 */
sealed class CaliperFrame {
    data class Measurement(
        val source: String,
        val rawValue: String,
        val unit: Char,
        val sign: Char,
        val valueUm10: Long,
        /** 帧是否需要握手回包。 */
        val needsAck: Boolean = false
    ) : CaliperFrame()

    /** 回包是否需要重试。 */
    data class Ack(val command: String, val ok: Boolean, val retryable: Boolean = false) : CaliperFrame()

    /** 无法识别的帧。 */
    data class Unknown(val text: String) : CaliperFrame()
}

enum class Verdict { PASS, FAIL, UNJUDGED }

enum class Source { DAT0, DAT1, MANUAL, IMPORT, HID }

/** 落库前的领域对象。 */
data class MeasurementDraft(
    val channelNo: Int,
    val deviceMac: String,
    val valueUm10: Long,
    val rawValue: String,
    val rawUnit: Char,
    val sign: Char,
    val source: Source,
    val capturedAt: Long,
    val elapsedRealtime: Long,
    val verdict: Verdict = Verdict.UNJUDGED,
    val note: String = "",
    val legacyChannel: Boolean = false
)
