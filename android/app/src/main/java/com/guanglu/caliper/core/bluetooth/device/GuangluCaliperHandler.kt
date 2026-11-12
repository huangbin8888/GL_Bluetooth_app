package com.guanglu.caliper.core.bluetooth.device

import com.guanglu.caliper.core.model.CaliperFrame
import com.guanglu.caliper.core.model.Units
import java.util.UUID

/**
 * 广陆协议唯一实现：DAT0/DAT1/MEAS/CLR/MOD/NAM/DAT0:OK 字符串只在本文件出现。
 * 依据《广陆蓝牙设备数字化接口_通用.doc》；NAM 是 V1 唯一可真实下发的配置项。
 */
object GuangluCaliperHandler : CaliperDevice {

    const val NAME_PREFIX = "GL-BLE#"
    const val MAX_NAME_LENGTH = 16
    const val COMMAND_NAM = "NAM"

    val SERVICE_UUID: UUID = UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb")

    private val DATA = Regex("^(DAT0|DAT1):([mi])([+-])(\\d+(?:\\.\\d+)?)$")
    private val ACK = Regex("^(MEAS|CLR|MOD|NAM):(OK|ERR)$")
    private val NAME_SUFFIX = Regex("^[A-Za-z0-9_-]+$")

    /** 帧头切分规则也由驱动提供，FrameBuffer 不持有协议字符串（分层约束 3）。 */
    val FRAME_START_PATTERN = Regex(
        "DAT0:|DAT1:|MEAS:OK|MEAS:ERR|CLR:OK|CLR:ERR|MOD:OK|MOD:ERR|NAM:OK|NAM:ERR"
    )

    override val namePrefix: String get() = NAME_PREFIX

    override fun accept(text: String): CaliperFrame {
        val frame = text.trim()
        val data = DATA.matchEntire(frame)
        if (data != null) {
            val sign = data.groupValues[3][0]
            val number = data.groupValues[4]
            val signed = (if (sign == '-') "-" else "") + number
            val valueUm = Units.toMicrometres(signed, data.groupValues[2][0])
                ?: return CaliperFrame.Unknown(frame)
            return CaliperFrame.Measurement(
                source = data.groupValues[1],
                rawValue = number,
                unit = data.groupValues[2][0],
                sign = sign,
                valueUm = valueUm,
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
        return CaliperFrame.Unknown(frame)
    }

    /** DAT0 握手只在完整帧解析成功后回；DAT1 不回（接口文档强制）。 */
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

    /** 名称总长 8..16、必须保留 GL-BLE# 前缀、后缀仅 ASCII 字母数字 _ -（基线 17.3 / Assumptions）。 */
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

    /**
     * HID 键盘录入的数值转成 DAT0 帧后复用同一个 accept()，保证协议字符串仍只在本文件。
     * 支持 "155.15" / "-1.2485 in"；转换完走同一条解析 → 换算 → 去抖 → 落库链路。
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
