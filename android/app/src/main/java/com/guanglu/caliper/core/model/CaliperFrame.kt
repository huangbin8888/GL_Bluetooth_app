package com.guanglu.caliper.core.model

/** 协议解析结果。协议字符串只允许出现在 GuangluCaliperHandler。 */
sealed class CaliperFrame {
    data class Measurement(
        val source: String,
        val rawValue: String,
        val unit: Char,
        val sign: Char,
        val valueUm: Long,
        /** 由驱动判定，避免上层再识别 DAT0 字符串（分层约束 3）。 */
        val needsAck: Boolean = false
    ) : CaliperFrame()

    /** retryable 由驱动判定，仅采集与模式指令的 ERR 回包需要重试（A12）。 */
    data class Ack(val command: String, val ok: Boolean, val retryable: Boolean = false) : CaliperFrame()

    /** 无法识别的帧：记诊断日志后丢弃，不写入 measurement（分层约束 2）。 */
    data class Unknown(val text: String) : CaliperFrame()
}

enum class Verdict { PASS, FAIL, UNJUDGED }

enum class Source { DAT0, DAT1, MANUAL, IMPORT, HID }

/** 落库前的领域对象；数据库写入只允许发生在 usecase（分层约束 4）。 */
data class MeasurementDraft(
    val channelNo: Int,
    val deviceMac: String,
    val valueUm: Long,
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
