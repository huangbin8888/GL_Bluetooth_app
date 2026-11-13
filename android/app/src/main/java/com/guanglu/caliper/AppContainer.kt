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
        onStored = { count -> uiEvent("已落库 $count 条") },
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
    fun startScan(onDevice: (ScannedDevice) -> Unit) = channelManager.startScan(onDevice)
    fun stopScan() = channelManager.stopScan()
    fun connect(mac: String): Boolean = channelManager.requestConnect(mac)
    fun sendCommand(channelNo: Int, command: CaliperCommand): Boolean =
        channelManager.sendCommand(channelNo, command)
    fun applyNam(channelNo: Int, name: String): Boolean = channelManager.applyNam(channelNo, name)
    fun onlineCount(): Int = channelManager.onlineCount()
    fun channelSlots(): Map<Int, String> = channelManager.channelSlots()
    fun disconnectAll(): Int = channelManager.disconnectAll()

    /** 自动连接上次连接过的卡尺；返回未执行的原因，null 表示已受理或无需连接。
     *  幂等：已在线或正在排队的 MAC 会被跳过，可安全地被 onStart / 权限回调 / 掉线重连反复调用。 */
    fun autoConnectKnown(): String? {
        if (!settings.autoConnect) return "自动重连已关闭（设置 · 采集行为可开启）"
        val reason = scanReadiness()
        if (reason != null) return reason
        scope.launch {
            val online = channelManager.channelSlots().values.toSet()
            val pending = channelManager.pendingMacs()
            val recent = deviceConfig.recentDevices(ChannelPalette.MAX_CHANNELS)
            val targets = recent.filter { it.mac !in online && it.mac !in pending }
            targets.forEach { channelManager.requestConnect(it.mac) }
            if (targets.isNotEmpty()) {
                diagnostics.log("AUTO_CONNECT ${targets.size} ${targets.joinToString { it.mac }}")
                uiEvent("自动连接上次的 ${targets.size} 台卡尺…")
            }
        }
        return null
    }

    /** 用户主动断开与空闲断连不重连；其余掉线（信号丢失 / 超时 / 失败）延迟 1.5s 后自动重连一次。
     *  受 15s 连接超时与槽位约束兜底，重复失败不会死循环。 */
    private val ignoredReconnectReasons = RECONNECT_IGNORED_REASONS
    private fun maybeReconnect(mac: String, reason: String) {
        scope.launch {
            val online = channelManager.channelSlots().values.toSet()
            val pending = channelManager.pendingMacs()
            val known = deviceConfig.recentDevices(ChannelPalette.MAX_CHANNELS).any { it.mac == mac }
            if (shouldAutoReconnect(settings.autoConnect, mac, reason, online, pending, known)) {
                diagnostics.log("AUTO_RECONNECT $mac ($reason)")
                mainHandler.postDelayed({ channelManager.requestConnect(mac) }, 1500L)
            }
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
        channelManager.close()
        capture.close()
    }

    private fun handleChannelEvent(event: ChannelEvent) {
        when (event) {
            is ChannelEvent.Connected -> {
                settings.lastMac = event.mac
                deviceConfig.registerConnected(event.mac, "GL-BLE#${event.mac.takeLast(4)}", event.channelNo)
                uiEvent("CH${event.channelNo} 已连接 ${event.mac}")
            }
            is ChannelEvent.Disconnected -> {
                uiEvent("CH${event.channelNo} 断开：${event.reason}")
                maybeReconnect(event.mac, event.reason)
            }
            is ChannelEvent.Rejected -> uiEvent(event.reason)
            is ChannelEvent.LimitReached -> uiEvent("已达该机型并发上限 ${event.actual}，剩余设备未连接；已连通道继续采集")
            is ChannelEvent.Ack -> uiEvent(
                "CH${event.channelNo} ${event.command} ${if (event.ok) "OK" else "ERR（重试中）"}"
            )
            is ChannelEvent.NamResult -> {
                if (event.ok && event.name.isNotBlank()) {
                    deviceConfig.renameDevice(event.mac, event.name)
                }
                uiEvent(
                    if (event.ok) "CH${event.channelNo} 改名成功：${event.name}，可能需重连生效"
                    else "CH${event.channelNo} 改名失败（设备回 ERR）"
                )
            }
        }
    }
}

/** 用户主动断开与空闲断连这两个原因下不自动重连；其余掉线（信号丢失 / 超时 / 失败）才考虑重连。 */
internal val RECONNECT_IGNORED_REASONS = setOf("用户断开", "空闲自动断连")

/** 是否应在掉线后自动重连：纯函数，供单测直接覆盖决策。
 *  自动重连关闭、用户/空闲主动断开、已在在线或排队集合、或未知设备，都不重连。 */
internal fun shouldAutoReconnect(
    autoConnect: Boolean,
    mac: String,
    reason: String,
    online: Set<String>,
    pending: Set<String>,
    known: Boolean
): Boolean {
    if (!autoConnect) return false
    if (reason in RECONNECT_IGNORED_REASONS) return false
    if (mac in online || mac in pending) return false
    return known
}
