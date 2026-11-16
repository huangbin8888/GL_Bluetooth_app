package com.guanglu.caliper

import com.guanglu.caliper.core.model.BT_STATE_OFF
import com.guanglu.caliper.core.model.BT_STATE_ON
import com.guanglu.caliper.core.model.BT_STATE_TURNING_OFF
import com.guanglu.caliper.core.model.BT_STATE_TURNING_ON
import com.guanglu.caliper.core.model.BluetoothRecoveryAction
import com.guanglu.caliper.core.model.MAX_RECOVERY_TICKS
import com.guanglu.caliper.core.model.RecoveryDecision
import com.guanglu.caliper.core.model.RECOVERY_INTERVAL_MS
import com.guanglu.caliper.core.model.bluetoothRecoveryAction
import com.guanglu.caliper.core.model.knownDevicesCaption
import com.guanglu.caliper.core.model.recoveryDecision
import com.guanglu.caliper.core.model.recoveryTargets
import com.guanglu.caliper.core.model.shouldAutoConnectScanned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝牙恢复补连、已知设备列表与循环终止条件的回归测试。
 *
 * @author AI-Generated
 */
class ReconnectRecoveryPolicyTest {

    @Test
    fun bluetooth_on_after_off_triggers_recovery() {
        assertEquals(
            BluetoothRecoveryAction.RECOVER,
            bluetoothRecoveryAction(BT_STATE_OFF, BT_STATE_ON)
        )
        assertEquals(
            BluetoothRecoveryAction.RECOVER,
            bluetoothRecoveryAction(BT_STATE_TURNING_ON, BT_STATE_ON)
        )
    }

    @Test
    fun turning_on_or_repeated_on_does_not_trigger_recovery() {
        assertEquals(
            BluetoothRecoveryAction.NONE,
            bluetoothRecoveryAction(BT_STATE_OFF, BT_STATE_TURNING_ON)
        )
        assertEquals(
            BluetoothRecoveryAction.NONE,
            bluetoothRecoveryAction(BT_STATE_ON, BT_STATE_ON)
        )
    }

    @Test
    fun bluetooth_off_suspends_recovery() {
        assertEquals(
            BluetoothRecoveryAction.SUSPEND,
            bluetoothRecoveryAction(BT_STATE_ON, BT_STATE_OFF)
        )
        assertEquals(
            BluetoothRecoveryAction.SUSPEND,
            bluetoothRecoveryAction(BT_STATE_ON, BT_STATE_TURNING_OFF)
        )
    }

    @Test
    fun recovery_targets_skip_online_pending_duplicates_and_blank() {
        val recent = listOf("aa:01", "AA:01", " aa:02 ", "AA:03", "", "AA:04")
        assertEquals(
            listOf("AA:02", "AA:04"),
            recoveryTargets(recent, setOf("AA:01"), setOf("AA:03"), 7)
        )
    }

    @Test
    fun recovery_targets_are_capped_by_device_limit() {
        val recent = (1..10).map { "AA:0$it" }
        assertEquals(7, recoveryTargets(recent, emptySet(), emptySet(), 7).size)
        assertEquals(2, recoveryTargets(recent, emptySet(), emptySet(), 2).size)
        assertTrue(recoveryTargets(recent, emptySet(), emptySet(), 0).isEmpty())
    }

    @Test
    fun recovery_loop_stops_on_all_branches() {
        assertEquals(
            RecoveryDecision.CONTINUE,
            recoveryDecision(0, 2, true, true)
        )
        assertEquals(
            RecoveryDecision.STOP_CONNECTED,
            recoveryDecision(1, 0, true, true)
        )
        assertEquals(
            RecoveryDecision.STOP_DISABLED,
            recoveryDecision(1, 2, false, true)
        )
        assertEquals(
            RecoveryDecision.STOP_BLUETOOTH_OFF,
            recoveryDecision(1, 2, true, false)
        )
        assertEquals(
            RecoveryDecision.STOP_BUDGET,
            recoveryDecision(MAX_RECOVERY_TICKS, 2, true, true)
        )
        assertEquals(
            RecoveryDecision.STOP_BUDGET,
            recoveryDecision(MAX_RECOVERY_TICKS + 20, 2, true, true)
        )
    }

    @Test
    fun recovery_budget_is_finite_and_interval_is_positive() {
        assertTrue(MAX_RECOVERY_TICKS in 1..20)
        assertTrue(RECOVERY_INTERVAL_MS in 1_000L..30_000L)
    }

    @Test
    fun scanned_known_device_auto_connects_only_when_needed() {
        val known = setOf("AA:01")
        assertTrue(shouldAutoConnectScanned(" aa:01 ", known, emptySet(), emptySet(), true))
        assertFalse(shouldAutoConnectScanned("AA:01", known, setOf("AA:01"), emptySet(), true))
        assertFalse(shouldAutoConnectScanned("AA:01", known, emptySet(), setOf("AA:01"), true))
        assertFalse(shouldAutoConnectScanned("AA:09", known, emptySet(), emptySet(), true))
        assertFalse(shouldAutoConnectScanned("AA:01", known, emptySet(), emptySet(), false))
    }

    @Test
    fun known_devices_caption_explains_all_states() {
        assertTrue(knownDevicesCaption(0, true, true).contains("暂无"))
        assertTrue(knownDevicesCaption(2, true, false).contains("蓝牙未开启"))
        assertTrue(knownDevicesCaption(2, false, true).contains("已关闭"))
        assertTrue(knownDevicesCaption(2, true, true).contains("自动重连"))
    }
}
