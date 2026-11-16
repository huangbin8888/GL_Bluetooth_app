package com.guanglu.caliper

import com.guanglu.caliper.core.model.NAM_QUERY_TIMEOUT_MS
import com.guanglu.caliper.core.model.NamVerification
import com.guanglu.caliper.core.model.namQueryFailureMessage
import com.guanglu.caliper.core.model.namQueryMessage
import com.guanglu.caliper.core.model.namVerificationMessage
import com.guanglu.caliper.core.model.namVerifyFailureMessage
import com.guanglu.caliper.core.model.verifyNam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAM 改名回读验证：一致才确认生效，不一致或未回包绝不宣称成功。
 *
 * @author AI-Generated
 */
class NamVerificationPolicyTest {

    @Test fun matching_names_confirm_device_side_change() {
        assertEquals(
            NamVerification.CONFIRMED,
            verifyNam("GL-BLE#0187", "GL-BLE#0187")
        )
        assertEquals(
            NamVerification.CONFIRMED,
            verifyNam("GL-BLE#0187", "  gl-ble#0187  ")
        )
    }

    @Test fun stale_or_wrong_name_is_reported_as_mismatch() {
        assertEquals(
            NamVerification.MISMATCH,
            verifyNam("GL-BLE#0187", "GL-BLE#9999")
        )
    }

    @Test fun blank_or_missing_reply_is_unconfirmed() {
        assertEquals(NamVerification.UNCONFIRMED, verifyNam("GL-BLE#0187", null))
        assertEquals(NamVerification.UNCONFIRMED, verifyNam("GL-BLE#0187", ""))
        assertEquals(NamVerification.UNCONFIRMED, verifyNam(null, "GL-BLE#0187"))
    }

    @Test fun messages_never_claim_success_without_confirmation() {
        val confirmed = namVerificationMessage("GL-BLE#0187", "GL-BLE#0187", 2)
        assertTrue(confirmed.contains("改名生效"))
        val mismatch = namVerificationMessage("GL-BLE#0187", "GL-BLE#9999", 2)
        assertTrue(mismatch.contains("改名未生效"))
        assertTrue(mismatch.contains("GL-BLE#9999"))
        val unconfirmed = namVerificationMessage("GL-BLE#0187", null, 2)
        assertTrue(unconfirmed.contains("无法确认"))
        assertTrue(unconfirmed.contains("未改动"))
    }

    @Test fun query_messages_name_the_device_as_source() {
        assertTrue(namQueryMessage("GL-BLE#0187").contains("卡尺真实广播名"))
        assertTrue(namQueryFailureMessage("3 秒超时").contains("无法读取卡尺广播名"))
        assertEquals(3_000L, NAM_QUERY_TIMEOUT_MS)
    }

    @Test fun verification_failure_keeps_local_registration_untouched() {
        val message = namVerifyFailureMessage("3 秒超时", 2)
        assertTrue(message.contains("无法确认"))
        assertTrue(message.contains("本地登记名未改动"))
        assertTrue(message.contains("3 秒超时"))
        assertTrue(message.startsWith("CH2"))
    }
}
