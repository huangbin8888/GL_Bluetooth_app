package com.guanglu.caliper.core.model

/** 蓝牙恢复后自动补连的调度上限与间隔；循环必须有终止条件。 */
internal const val MAX_RECOVERY_TICKS = 6
internal const val RECOVERY_INTERVAL_MS = 5_000L

/** Android BluetoothAdapter 的开关状态值；core/model 不依赖 android 包，便于 JVM 单测。 */
internal const val BT_STATE_OFF = 10
internal const val BT_STATE_TURNING_ON = 11
internal const val BT_STATE_ON = 12
internal const val BT_STATE_TURNING_OFF = 13

/** 系统蓝牙状态迁移后应采取的动作。 */
internal enum class BluetoothRecoveryAction { NONE, RECOVER, SUSPEND }

/** 只有从非开启态进入开启态才触发补连；关闭态停止补连循环。 */
internal fun bluetoothRecoveryAction(previous: Int, current: Int): BluetoothRecoveryAction = when {
    current == BT_STATE_OFF || current == BT_STATE_TURNING_OFF -> BluetoothRecoveryAction.SUSPEND
    current == BT_STATE_ON && previous != BT_STATE_ON -> BluetoothRecoveryAction.RECOVER
    else -> BluetoothRecoveryAction.NONE
}

/** 需要补连的已登记 MAC：去重、剔除在线与排队中的、按机型并发上限截断。 */
internal fun recoveryTargets(
    recent: List<String>,
    online: Set<String>,
    pending: Set<String>,
    limit: Int = ChannelPalette.MAX_CHANNELS
): List<String> = recent
    .map { it.trim().uppercase() }
    .distinct()
    .filter { mac -> mac.isNotBlank() && mac !in online && mac !in pending }
    .take(limit.coerceAtLeast(0))

/** 补连循环的停止原因；只有 CONTINUE 才继续 postDelayed。 */
internal enum class RecoveryDecision {
    CONTINUE,
    STOP_CONNECTED,
    STOP_BUDGET,
    STOP_DISABLED,
    STOP_BLUETOOTH_OFF
}

/** 判定下一轮补连是否继续；所有分支均有明确终止条件。 */
internal fun recoveryDecision(
    tick: Int,
    targets: Int,
    autoConnect: Boolean,
    bluetoothReady: Boolean,
    maxTicks: Int = MAX_RECOVERY_TICKS
): RecoveryDecision = when {
    !autoConnect -> RecoveryDecision.STOP_DISABLED
    !bluetoothReady -> RecoveryDecision.STOP_BLUETOOTH_OFF
    targets <= 0 -> RecoveryDecision.STOP_CONNECTED
    tick >= maxTicks -> RecoveryDecision.STOP_BUDGET
    else -> RecoveryDecision.CONTINUE
}

/** 扫描重新发现已登记卡尺时是否自动连接，避免重复连接与关闭开关后仍连。 */
internal fun shouldAutoConnectScanned(
    mac: String,
    known: Set<String>,
    online: Set<String>,
    pending: Set<String>,
    autoConnect: Boolean
): Boolean {
    val key = mac.trim().uppercase()
    return autoConnect && key in known && key !in online && key !in pending
}

/** 已登记设备列表的状态说明，蓝牙关闭时明确告知不会自动补连。 */
internal fun knownDevicesCaption(count: Int, autoConnect: Boolean, bluetoothReady: Boolean): String = when {
    count == 0 -> "暂无已连接过的卡尺，先扫描并连接一台后会自动登记"
    !bluetoothReady -> "已登记 $count 台 · 系统蓝牙未开启，打开蓝牙后自动重连"
    !autoConnect -> "已登记 $count 台 · 自动重连已关闭，可点右侧按钮手动重连"
    else -> "已登记 $count 台 · 蓝牙恢复或重新广播时自动重连，也可手动点重连"
}
