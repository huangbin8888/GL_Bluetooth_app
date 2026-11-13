package com.guanglu.caliper

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.guanglu.caliper.core.bluetooth.BlessedTransport
import com.guanglu.caliper.core.bluetooth.CommandQueue
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.ScanRegistry
import com.guanglu.caliper.core.bluetooth.channel.ChannelEvent
import com.guanglu.caliper.core.bluetooth.channel.ChannelManager
import com.guanglu.caliper.core.bluetooth.device.CaliperCommand
import com.guanglu.caliper.core.database.AppDatabase
import com.guanglu.caliper.core.datastore.SettingsRepository
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.usecase.BatchUseCase
import com.guanglu.caliper.core.usecase.CaptureMeasurementsUseCase
import com.guanglu.caliper.core.usecase.DeviceConfigUseCase
import com.guanglu.caliper.core.usecase.DiagnosticsUseCase
import com.guanglu.caliper.core.usecase.HidKeyboardInputUseCase
import com.guanglu.caliper.core.usecase.ImportExportUseCases
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手工依赖组装，向 ui/ 提供 usecase 与通道门面。
 *
 * @author AI-Generated
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    val settings = SettingsRepository(context)
    val diagnostics = DiagnosticsUseCase(context)
    private val database = AppDatabase.build(context)

    private val transport = BlessedTransport(
        context,
        onDiagnostic = { diagnostics.log(it) }
    )
    private val commandQueue = CommandQueue { mac, bytes -> transport.write(mac, bytes) }

    val capture = CaptureMeasurementsUseCase(
        dao = database.measurementDao(),
        settings = settings,
        onStored = { count -> diagnostics.log("STORE $count") },
        onDiagnostic = { diagnostics.log(it) }
    )

    val deviceConfig = DeviceConfigUseCase(database.deviceDao())
    val hid = HidKeyboardInputUseCase(capture, settings) { diagnostics.log(it) }
    val io = ImportExportUseCases(context, database.measurementDao(), settings)

    private val batchUseCase = BatchUseCase(database.batchDao())

    private val channelManager = ChannelManager(
        transport = transport,
        commandQueue = commandQueue,
        onMeasurement = { channelNo, mac, frame -> capture.onFrame(channelNo, mac, frame) },
        onEvent = ::handleChannelEvent,
        onDiagnostic = { diagnostics.log(it) }
    )

    // ---- UI 门面 ----
    private val scanRegistry = ScanRegistry()
    private val continuousModes = ConcurrentHashMap<Int, Boolean>()

    fun isContinuous(channelNo: Int): Boolean = continuousModes[channelNo] == true
    fun setContinuous(channelNo: Int, enabled: Boolean) {
        continuousModes[channelNo] = enabled
    }

    fun clearContinuous(channelNo: Int) {
        continuousModes.remove(channelNo)
    }

    /** 扫描设备；按稳定设备标识合并重复广播，转发给界面的均为唯一设备。 */
    fun startScan(onDevice: (ScannedDevice) -> Unit) {
        scanRegistry.clear()
        channelManager.startScan { device ->
            if (scanRegistry.add(device)) {
                onDevice(device)
            } else {
                diagnostics.log("SCAN_DEDUP ${device.mac} ${device.identity}")
            }
        }
    }

    fun stopScan() = channelManager.stopScan()
    fun scanDedupCount(): Int = scanRegistry.mergedCount
    fun connect(mac: String): Boolean {
        resetReconnect(mac)
        return channelManager.requestConnect(mac)
    }
    fun sendCommand(channelNo: Int, command: CaliperCommand): Boolean =
        channelManager.sendCommand(channelNo, command)
    fun applyNam(channelNo: Int, name: String): Boolean = channelManager.applyNam(channelNo, name)
    fun onlineCount(): Int = channelManager.onlineCount()
    fun channelSlots(): Map<Int, String> = channelManager.channelSlots()
    fun disconnectAll(): Int {
        cancelAllReconnects()
        return channelManager.disconnectAll()
    }

    /** 自动连接上次连接过的卡尺；返回未执行的原因，null 表示已受理或无需连接。
     *  跳过已在线或正在排队的 MAC，5 秒内重复调用直接返回。 */
    fun autoConnectKnown(): String? {
        if (!settings.autoConnect) return "自动重连已关闭（设置 · 采集行为可开启）"
        val reason = scanReadiness()
        if (reason != null) return reason
        val now = System.currentTimeMillis()
        if (!shouldAutoConnectNow(lastAutoConnectMs, now)) return null
        lastAutoConnectMs = now
        scope.launch {
            val online = channelManager.channelSlots().values.toSet()
            val pending = channelManager.pendingMacs()
            val recent = deviceConfig.recentDevices(ChannelPalette.MAX_CHANNELS)
            val targets = recent.filter { it.mac !in online && it.mac !in pending }
            targets.forEach { channelManager.requestConnect(it.mac) }
            if (targets.isNotEmpty()) {
                diagnostics.log("AUTO_CONNECT ${targets.size} ${targets.joinToString { it.mac }}")
                if (eventThrottle.allow("auto_connect")) {
                    uiEvent("自动连接上次的 ${targets.size} 台卡尺…")
                }
            }
        }
        return null
    }

    /** 掉线后按有限预算自动重连。 */
    private fun maybeReconnect(mac: String, reason: String) {
        scope.launch {
            val online = channelManager.channelSlots().values.toSet()
            val pending = channelManager.pendingMacs()
            val known = deviceConfig.recentDevices(ChannelPalette.MAX_CHANNELS).any { it.mac == mac }
            var stopped = false
            var attempts = 0
            synchronized(reconnectLock) {
                attempts = reconnectAttempts[mac] ?: 0
                if (shouldAutoReconnect(
                        settings.autoConnect, mac, reason, online, pending, known, attempts
                    )
                ) {
                    reconnectAttempts[mac] = attempts + 1
                    diagnostics.log("AUTO_RECONNECT $mac attempt=${attempts + 1}/$MAX_AUTO_RECONNECT ($reason)")
                    val task = Runnable {
                        pendingReconnects.remove(mac)
                        channelManager.requestConnect(mac)
                    }
                    pendingReconnects[mac] = task
                    mainHandler.postDelayed(task, AUTO_RECONNECT_DELAY_MS)
                } else if (attempts >= MAX_AUTO_RECONNECT) {
                    stopped = reconnectStopped.add(mac)
                }
            }
            if (stopped) {
                diagnostics.log("AUTO_RECONNECT_STOP $mac attempts=$attempts")
                uiEvent("已停止重连 ${mac.takeLast(4)}：连续 $attempts 次失败，请检查卡尺电量与蓝牙后手动连接")
            }
        }
    }

    /** 连接成功后清零连续失败预算并取消待执行的重连任务。 */
    private fun resetReconnect(mac: String) {
        synchronized(reconnectLock) {
            reconnectAttempts.remove(mac)
            reconnectStopped.remove(mac)
            pendingReconnects.remove(mac)?.let { mainHandler.removeCallbacks(it) }
        }
    }

    /** 取消全部待重连任务并清零预算，供用户主动断开与 App 关闭时调用。 */
    private fun cancelAllReconnects() {
        synchronized(reconnectLock) {
            pendingReconnects.values.forEach { mainHandler.removeCallbacks(it) }
            pendingReconnects.clear()
            reconnectAttempts.clear()
            reconnectStopped.clear()
        }
    }

    /** 返回无法扫描的原因，null 表示可扫描。 */
    fun scanReadiness(): String? {
        if (Build.VERSION.SDK_INT >= 31) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN) ||
                !granted(Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                return "蓝牙权限未授予：请点『去系统设置』开启附近设备权限后重试"
            }
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return "定位权限未授予：Android 10/11 扫描蓝牙需要定位权限"
        }
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) return "本机无蓝牙适配器，无法扫描卡尺"
        if (!adapter.isEnabled) return "蓝牙未开启：请先在系统设置打开蓝牙"
        if (Build.VERSION.SDK_INT < 31) {
            val locator = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locator != null && !LocationManagerCompat.isLocationEnabled(locator)) {
                return "系统定位总开关未打开：Android 10/11 扫描不到蓝牙设备"
            }
        }
        return null
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /** UI 订阅的状态文案；MainActivity 在 onResume 里设置。 */
    @Volatile
    var uiEvent: (String) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectLock = Any()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val pendingReconnects = ConcurrentHashMap<String, Runnable>()
    private val reconnectStopped = ConcurrentHashMap.newKeySet<String>()
    private val eventThrottle = UiEventThrottle()

    @Volatile
    private var lastAutoConnectMs = 0L

    private val idleRunnable = object : Runnable {
        override fun run() {
            val idleMs = settings.idleDisconnectMinutes * 60_000L
            channelManager.pollIdle(System.currentTimeMillis(), idleMs)
            mainHandler.postDelayed(this, 60L)
        }
    }

    init {
        batchUseCase.ensureDefault(settings.activeBatchId)
        mainHandler.post(idleRunnable)
    }

    fun measurements() = database.measurementDao().observeAll()

    fun exportCsv(uri: Uri, channelNo: Int? = null) {
        scope.launch {
            val report = io.exportCsv(uri, channelNo)
            uiEvent("已导出 ${report.rows} 行 CSV")
        }
    }

    fun importCsv(uri: Uri) {
        scope.launch {
            val report = io.importCsv(uri)
            uiEvent(
                "导入 ${report.imported}/${report.rows} 行 · 跳过列 ${report.skippedColumns.size} · " +
                    "解析失败 ${report.parseErrors} · 重复 ${report.duplicates}"
            )
        }
    }

    fun exportDiagnostics(uri: Uri) {
        scope.launch {
            val lines = diagnostics.export(uri)
            uiEvent("已导出 $lines 条诊断日志")
        }
    }

    fun close() {
        mainHandler.removeCallbacks(idleRunnable)
        cancelAllReconnects()
        eventThrottle.clear()
        channelManager.close()
        capture.close()
    }

    private fun handleChannelEvent(event: ChannelEvent) {
        when (event) {
            is ChannelEvent.Connected -> {
                resetReconnect(event.mac)
                settings.lastMac = event.mac
                deviceConfig.registerConnected(event.mac, "GL-BLE#${event.mac.takeLast(4)}", event.channelNo)
                uiEvent("CH${event.channelNo} 已连接 ${event.mac}")
            }
            is ChannelEvent.Disconnected -> {
                clearContinuous(event.channelNo)
                if (eventThrottle.allow("disconnect:${event.mac}")) {
                    uiEvent("CH${event.channelNo} 断开：${event.reason}")
                }
                maybeReconnect(event.mac, event.reason)
            }
            is ChannelEvent.Rejected -> {
                if (eventThrottle.allow("reject:${event.mac}")) uiEvent(event.reason)
            }
            is ChannelEvent.LimitReached -> uiEvent("已达该机型并发上限 ${event.actual}，剩余设备未连接；已连通道继续采集")
            is ChannelEvent.Ack -> {
                if (!event.ok && eventThrottle.allow("ack:${event.channelNo}:${event.command}")) {
                    uiEvent("CH${event.channelNo} ${event.command} ERR（重试中）")
                }
            }
            is ChannelEvent.NamResult -> {
                if (event.ok && event.name.isNotBlank()) {
                    deviceConfig.renameDevice(event.mac, event.name)
                }
                diagnostics.log("NAM_RESULT ${event.mac} ${event.name} ok=${event.ok} ${event.reason}")
                if (eventThrottle.allow("nam:${event.mac}")) {
                    uiEvent(
                        if (event.ok) "CH${event.channelNo} 改名成功：${event.name}，可能需重连生效"
                        else "CH${event.channelNo} 改名失败：${event.reason.ifBlank { "设备回 ERR" }}"
                    )
                }
            }
        }
    }
}

/** 自动重连次数上限与两次调度间隔。 */
internal const val MAX_AUTO_RECONNECT = 3
internal const val AUTO_RECONNECT_DELAY_MS = 1_500L
internal const val AUTO_CONNECT_COOLDOWN_MS = 5_000L

/** 主动断连原因前缀。 */
internal val RECONNECT_IGNORED_PREFIXES = listOf("用户断开", "空闲自动断连")

/** 判定断连原因是否属于主动断开。 */
internal fun isManualDisconnect(reason: String): Boolean =
    RECONNECT_IGNORED_PREFIXES.any { reason.startsWith(it) }

/** 判定是否应在掉线后自动重连；attempts 为已连续失败重连次数。 */
internal fun shouldAutoReconnect(
    autoConnect: Boolean,
    mac: String,
    reason: String,
    online: Set<String>,
    pending: Set<String>,
    known: Boolean,
    attempts: Int = 0,
    maxAttempts: Int = MAX_AUTO_RECONNECT
): Boolean {
    if (!autoConnect) return false
    if (isManualDisconnect(reason)) return false
    if (mac in online || mac in pending) return false
    if (!known) return false
    return attempts < maxAttempts
}

/** 判定是否距上次自动补连已超过冷却窗口。 */
internal fun shouldAutoConnectNow(
    lastMs: Long,
    nowMs: Long,
    cooldownMs: Long = AUTO_CONNECT_COOLDOWN_MS
): Boolean = nowMs - lastMs >= cooldownMs

/**
 * UI 事件节流：相同 key 在窗口期内只放行一次。
 *
 * @author AI-Generated
 */
internal class UiEventThrottle(
    private val windowMs: Long = 1_500L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lastAt = HashMap<String, Long>()

    @Synchronized
    fun allow(key: String): Boolean {
        val now = clock()
        val last = lastAt[key]
        if (last != null && now - last < windowMs) return false
        lastAt[key] = now
        return true
    }

    @Synchronized
    fun clear() = lastAt.clear()
}
