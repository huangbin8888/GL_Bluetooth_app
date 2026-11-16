package com.guanglu.caliper.core.model

/**
 * 改名的设备端回读验证策略；区分「App 本地登记名」与「卡尺真实广播名」。
 *
 * @author AI-Generated
 */

/** 查询卡尺广播名的回包超时（毫秒）。 */
internal const val NAM_QUERY_TIMEOUT_MS = 3_000L

/** 改名回读结论。 */
enum class NamVerification { CONFIRMED, MISMATCH, UNCONFIRMED }

/** 卡尺回读到的真实广播名；target 非空表示这是改名后的自动验证。 */
data class DeviceNameReport(
    val mac: String,
    val name: String,
    val target: String? = null,
    val verified: Boolean = false
)

/** 期望名与设备实际广播名比对；任一为空都视为未确认。 */
internal fun verifyNam(expected: String?, reported: String?): NamVerification = when {
    reported.isNullOrBlank() || expected.isNullOrBlank() -> NamVerification.UNCONFIRMED
    reported.trim().equals(expected.trim(), ignoreCase = true) -> NamVerification.CONFIRMED
    else -> NamVerification.MISMATCH
}

/** 面向用户的改名结论；未确认时明确说明本地登记名未改动。 */
internal fun namVerificationMessage(expected: String?, reported: String?, channelNo: Int): String =
    when (verifyNam(expected, reported)) {
        NamVerification.CONFIRMED -> "CH$channelNo 卡尺广播名已回读确认：$reported，改名生效"
        NamVerification.MISMATCH ->
            "CH$channelNo 卡尺当前广播名仍为 $reported，与 $expected 不一致；改名未生效，请重启卡尺后重新扫描"
        NamVerification.UNCONFIRMED ->
            "CH$channelNo 未收到卡尺名称回包，无法确认改名是否生效；App 本地登记名未改动"
    }

/** 主动查询回读的展示文案，强调来源是卡尺而非 App 登记。 */
internal fun namQueryMessage(reported: String): String =
    "卡尺真实广播名：$reported（App 登记名可能不同）"

/** 查询超时或写失败时的明确原因，不静默失败。 */
internal fun namQueryFailureMessage(reason: String): String =
    "无法读取卡尺广播名：$reason；请确认卡尺在线并重新扫描"

/** 改名后的回读失败文案；明确不改动 App 本地登记名。 */
internal fun namVerifyFailureMessage(reason: String, channelNo: Int): String =
    "CH$channelNo 改名回读失败：$reason；无法确认卡尺是否生效，App 本地登记名未改动"
