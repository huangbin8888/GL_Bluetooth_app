package com.guanglu.caliper.core.bluetooth.device

import com.guanglu.caliper.core.model.CaliperFrame

/**
 * 卡尺设备驱动抽象。
 *
 * @author AI-Generated
 */
interface CaliperDevice {
    val namePrefix: String
    fun accept(text: String): CaliperFrame
    fun ackBytesFor(frame: CaliperFrame): ByteArray?
    fun commandBytes(command: CaliperCommand): ByteArray
}

enum class CaliperCommand { MEAS, CLR, MOD_MANUAL, MOD_CONTINUOUS }
