package com.guanglu.caliper.core.usecase

import com.guanglu.caliper.core.bluetooth.device.GuangluCaliperHandler
import com.guanglu.caliper.core.datastore.SettingsRepository
import com.guanglu.caliper.core.model.CaliperFrame

/** 结束符只取 CR / TAB，且只用于 HID 键盘录入的切分（基线 12.5）。 */
enum class Terminator { CR, TAB }

object HidKeyboardInput {
    fun split(input: String, terminator: Terminator): List<String> {
        val separators = when (terminator) {
            Terminator.CR -> charArrayOf('\r', '\n')
            Terminator.TAB -> charArrayOf('\t', '\r', '\n')
        }
        return input.split(*separators).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * 前台焦点输入框收到系统注入的 HID 字符 → 按登记结束符切分 → 转成 DAT0 帧
 * → 交 CaptureMeasurementsUseCase 同一条落库链路；本类不直接写库。
 */
class HidKeyboardInputUseCase(
    private val capture: CaptureMeasurementsUseCase,
    private val settings: SettingsRepository,
    private val onDiagnostic: (String) -> Unit = {}
) {
    fun submit(channelNo: Int, mac: String, text: String): Int {
        val terminator = if (settings.terminator == "TAB") Terminator.TAB else Terminator.CR
        var accepted = 0
        HidKeyboardInput.split(text, terminator).forEach { token ->
            when (val frame = GuangluCaliperHandler.fromHidToken(token)) {
                is CaliperFrame.Measurement -> {
                    capture.onHidFrame(channelNo, mac, frame)
                    accepted++
                }
                else -> onDiagnostic("HID_SKIP $token")
            }
        }
        return accepted
    }
}
