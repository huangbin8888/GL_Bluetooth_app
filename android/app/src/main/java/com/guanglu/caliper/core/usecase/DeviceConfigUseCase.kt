package com.guanglu.caliper.core.usecase

import com.guanglu.caliper.core.database.dao.DeviceDao
import com.guanglu.caliper.core.database.entity.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 按 MAC 登记设备端参数。
 *
 * @author AI-Generated
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

    /** 最近连接过的设备，按最后连接时间倒序，用于启动时自动重连。 */
    suspend fun recentDevices(limit: Int): List<Device> = withContext(Dispatchers.IO) { dao.recent(limit) }

    /** NAM 改名成功后同步本地登记名。 */
    fun renameDevice(mac: String, name: String) {
        scope.launch {
            val existing = dao.find(mac) ?: return@launch
            dao.upsert(existing.copy(name = name, customName = name))
        }
    }
}
