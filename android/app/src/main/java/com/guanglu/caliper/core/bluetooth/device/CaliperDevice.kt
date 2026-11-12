package com.guanglu.caliper.core.bluetooth.device

import com.guanglu.caliper.core.model.CaliperFrame

/**
 * 设备驱动抽象（对应 openScale 的 scale driver）。
 * 新增第二种卡尺时只需新增一个实现，不改 CaptureMeasurementsUseCase（分层约束 3）。
 */
interface CaliperDevice {
    val namePrefix: String
    fun accept(text: String): CaliperFrame
    fun ackBytesFor(frame: CaliperFrame): ByteArray?
    fun commandBytes(command: CaliperCommand): ByteArray
}

enum class CaliperCommand { MEAS, CLR, MOD_MANUAL, MOD_CONTINUOUS }
