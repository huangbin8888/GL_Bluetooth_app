package com.guanglu.caliper.core.bluetooth.device

import com.guanglu.caliper.core.model.CaliperFrame
import com.guanglu.caliper.core.model.Units
import java.util.UUID

/**
 * 广陆协议帧解析与命令构建。
 *
 * @author AI-Generated
 */
object GuangluCaliperHandler : CaliperDevice {

    const val NAME_PREFIX = "GL-BLE#"
    const val MAX_NAME_LENGTH = 16
    const val COMMAND_NAM = "NAM"

    /** 获取模块广播名称的查询指令（APP→模块），按接口文档单独定义。 */
    const val COMMAND_NAM_QUERY = "NAM"

    val SERVICE_UUID: UUID = UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb")

    private val DATA = Regex("^(DAT0|DAT1):([mi])([+-])(\\d+(?:\\.\\d+)?)$")
    private val ACK = Regex("^(MEAS|CLR|MOD|NAM):(OK|ERR)$")
    private val NAME_SUFFIX = Regex("^[A-Za-z0-9_-]+$")
    private val NAME_REPORT = Regex("^NAM:(?!OK$|ERR$)(.+)$")

    /** 扫描名称取本次广播名，缺失时回退系统缓存名；改名后缓存名常滞后。 */
    fun pickBroadcastName(cachedName: String?, advertisedName: String?): String? =
        advertisedName?.takeIf { it.isNotBlank() } ?: cachedName?.takeIf { it.isNotBlank() }

    /** 判定广播名是否属于广陆卡尺。 */
    fun matchesBroadcastName(name: String?, prefix: String = NAME_PREFIX): Boolean =
        !name.isNullOrBlank() && name.startsWith(prefix)

    /** 扫描设备稳定标识：广播名带后缀时以广播名为准，否则回退规范化 MAC。 */
    fun stableScanKey(name: String?, mac: String): String {
        val normalized = name?.trim()?.uppercase().orEmpty()
        return if (normalized.length > NAME_PREFIX.length && normalized.startsWith(NAME_PREFIX)) {
            normalized
        } else {
            mac.trim().uppercase()
        }
    }

    /** 按帧头切分协议帧；NAM: 覆盖文档定义的名称查询回包 NAM:&lt;string&gt;。 */
    val FRAME_START_PATTERN = Regex(
        "DAT0:|DAT1:|MEAS:OK|MEAS:ERR|CLR:OK|CLR:ERR|MOD:OK|MOD:ERR|NAM:OK|NAM:ERR|NAM:"
    )

    override val namePrefix: String get() = NAME_PREFIX

    override fun accept(text: String): CaliperFrame {
        val frame = text.trim()
        val data = DATA.matchEntire(frame)
        if (data != null) {
            val sign = data.groupValues[3][0]
            val number = data.groupValues[4]
            val signed = (if (sign == '-') "-" else "") + number
            val valueUm10 = Units.toUm10(signed, data.groupValues[2][0])
                ?: return CaliperFrame.Unknown(frame)
            return CaliperFrame.Measurement(
                source = data.groupValues[1],
                rawValue = number,
                unit = data.groupValues[2][0],
                sign = sign,
                valueUm10 = valueUm10,
                needsAck = data.groupValues[1] == "DAT0"
            )
        }
        val ack = ACK.matchEntire(frame)
        if (ack != null) {
            val command = ack.groupValues[1]
            return CaliperFrame.Ack(
                command = command,
                ok = ack.groupValues[2] == "OK",
                retryable = command == "MEAS" || command == "MOD"
            )
        }
        val nameReport = NAME_REPORT.matchEntire(frame)
        if (nameReport != null) {
            return CaliperFrame.NameReport(nameReport.groupValues[1].trim())
        }
        return CaliperFrame.Unknown(frame)
    }

    /** 完整帧解析成功后返回 DAT0 握手回包字节。 */
    override fun ackBytesFor(frame: CaliperFrame): ByteArray? {
        if (frame is CaliperFrame.Measurement && frame.source == "DAT0") {
            return "DAT0:OK".toByteArray(Charsets.US_ASCII)
        }
        return null
    }

    override fun commandBytes(command: CaliperCommand): ByteArray = when (command) {
        CaliperCommand.MEAS -> "MEAS"
        CaliperCommand.CLR -> "CLR"
        CaliperCommand.MOD_MANUAL -> "MOD:0"
        CaliperCommand.MOD_CONTINUOUS -> "MOD:1"
    }.toByteArray(Charsets.US_ASCII)

    /** 校验名称总长 8..16 与后缀字符集。 */
    fun validateNam(name: String): Boolean {
        if (name.length !in (NAME_PREFIX.length + 1)..MAX_NAME_LENGTH) return false
        if (!name.startsWith(NAME_PREFIX)) return false
        return NAME_SUFFIX.matches(name.removePrefix(NAME_PREFIX))
    }

    fun buildNamCommand(name: String): ByteArray {
        require(validateNam(name)) {
            "NAM 名称非法：须保留 $NAME_PREFIX 前缀，总长 8..16，后缀仅 ASCII 字母数字 _ -"
        }
        return "NAM:$name".toByteArray(Charsets.US_ASCII)
    }

    /** 查询模块当前广播名称；与设置指令不同，不带冒号与参数。 */
    fun buildNamQueryCommand(): ByteArray = COMMAND_NAM_QUERY.toByteArray(Charsets.US_ASCII)

    /**
     * HID 键盘录入数值转成 DAT0 帧。
     */
    fun fromHidToken(token: String): CaliperFrame {
        val text = token.trim()
        if (text.isEmpty()) return CaliperFrame.Unknown(text)
        val inch = text.endsWith(" in", ignoreCase = true)
        val numberText = (if (inch) text.dropLast(3) else text).trim()
        val sign = if (numberText.startsWith("-")) '-' else '+'
        val number = numberText.removePrefix("+").removePrefix("-")
        val unit = if (inch) 'i' else 'm'
        return accept("DAT0:$unit$sign$number")
    }
}
