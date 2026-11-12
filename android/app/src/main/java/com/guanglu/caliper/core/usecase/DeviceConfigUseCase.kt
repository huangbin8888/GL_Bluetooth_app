package com.guanglu.caliper.core.usecase

import com.guanglu.caliper.core.database.dao.DeviceDao
import com.guanglu.caliper.core.database.entity.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P7 蓝牙配置的「登记」写入口：自动发射时长 / 休眠 / 结束符按 MAC 存到本机 device 表。
 * 这些是设备端参数且无厂商指令集，App 不下发；界面不得提示「已配置成功」（基线 12.5）。
 */
class DeviceConfigUseCase(
    private val dao: DeviceDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    fun registerConnected(mac: String, name: String, channelNo: Int) {
        scope.launch {
            val existing = dao.find(mac)
            dao.upsert(
                (existing ?: Device(mac = mac, name = name)).copy(
                    name = name,
                    lastConnectedAt = System.currentTimeMillis(),
                    isLastUsed = true,
                    channelNo = channelNo
                )
            )
            dao.clearLastUsed()
            dao.markLastUsed(mac)
        }
    }

    fun saveRegistration(
        mac: String,
        name: String,
        channelNo: Int,
        customName: String?,
        autoEmitSeconds: Int?,
        sleepEnabled: Boolean,
        sleepMinutes: Int?,
        terminator: String
    ) {
        scope.launch {
            val existing = dao.find(mac)
            dao.upsert(
                (existing ?: Device(mac = mac, name = name)).copy(
                    name = name,
                    channelNo = channelNo,
                    customName = customName,
                    autoEmitSeconds = autoEmitSeconds,
                    sleepEnabled = sleepEnabled,
                    sleepMinutes = sleepMinutes,
                    terminator = if (terminator == "TAB") "TAB" else "CR"
                )
            )
        }
    }

    suspend fun load(mac: String): Device? = withContext(Dispatchers.IO) { dao.find(mac) }
}
