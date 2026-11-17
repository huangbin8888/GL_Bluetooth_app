package com.guanglu.caliper

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.guanglu.caliper.core.audio.OverLimitBeep
import com.guanglu.caliper.core.bluetooth.BlessedTransport
import com.guanglu.caliper.core.bluetooth.CommandQueue
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.ScanRegistry
import com.guanglu.caliper.core.bluetooth.channel.ChannelEvent
import com.guanglu.caliper.core.bluetooth.channel.ChannelManager
import com.guanglu.caliper.core.bluetooth.device.CaliperCommand
import com.guanglu.caliper.core.database.AppDatabase
import com.guanglu.caliper.core.datastore.SettingsRepository
import com.guanglu.caliper.core.model.BEEP_COOLDOWN_MS
import com.guanglu.caliper.core.model.BT_STATE_OFF
import com.guanglu.caliper.core.model.BT_STATE_ON
import com.guanglu.caliper.core.model.BluetoothRecoveryAction
import com.guanglu.caliper.core.model.ChannelNamePolicy
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.DeviceNameReport
import com.guanglu.caliper.core.model.IDLE_POLL_INTERVAL_MS
import com.guanglu.caliper.core.model.MAX_RECOVERY_TICKS
import com.guanglu.caliper.core.model.NamVerification
import com.guanglu.caliper.core.model.RECOVERY_INTERVAL_MS
import com.guanglu.caliper.core.model.RecoveryDecision
import com.guanglu.caliper.core.model.bluetoothRecoveryAction
import com.guanglu.caliper.core.model.deviceDisplayName
import com.guanglu.caliper.core.model.namQueryFailureMessage
import com.guanglu.caliper.core.model.namQueryMessage
import com.guanglu.caliper.core.model.namVerificationMessage
import com.guanglu.caliper.core.model.namVerifyFailureMessage
import com.guanglu.caliper.core.model.overLimitAlertMessage
import com.guanglu.caliper.core.model.recoveryDecision
import com.guanglu.caliper.core.model.recoveryTargets
import com.guanglu.caliper.core.model.shouldAutoConnectScanned
import com.guanglu.caliper.core.model.shouldBeep
import com.guanglu.caliper.core.model.verifyNam
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 连接页展示的已登记卡尺摘要。
 *
 * @author AI-Generated
 */
data class KnownDevice(val mac: String, val name: String, val lastConnectedAt: Long)

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
    private val overLimitBeep = OverLimitBeep(context) { diagnostics.log(it) }
    private var lastBeepAt = 0L

    private val transport = BlessedTransport(
        context,
        onDiagnostic = { diagnostics.log(it) }
    )
    private val commandQueue = CommandQueue { mac, bytes -> transport.write(mac, bytes) }

    val capture = CaptureMeasurementsUseCase(
        dao = database.measurementDao(),
        settings = settings,
        onStored = { count, overLimit ->
            diagnostics.log("STORE $count")
            maybeBeep(overLimit)
        },
        onDiagnostic = { diagnostics.log(it) },
        onStoreFailed = { message ->
            diagnostics.log(message)
            mainHandler.post { uiEvent(message) }
        }
    )

    val deviceConfig = DeviceConfigUseCase(database.deviceDao())
    val hid = HidKeyboardInputUseCase(capture, settings) { diagnostics.log(it) }
    val io = ImportExportUseCases(context, database.measurementDao(), database.deviceDao(), settings)

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

    /** 已登记 MAC 缓存，扫描命中时用于判定是否自动补连。 */
    @Volatile
    private var knownMacs: Set<String> = emptySet()

    @Volatile
    private var bluetoothState = -1
    private var recoveryTick = 0

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

    /** 下发 NAM 查询读取卡尺真实广播名；写失败与超时由 NameReport 事件反馈。 */
    fun queryNam(channelNo: Int): Boolean {
        val accepted = channelManager.queryNam(channelNo)
        diagnostics.log("NAM_QUERY_REQUEST CH$channelNo accepted=$accepted")
        return accepted
    }

    fun onlineCount(): Int = channelManager.onlineCount()
    fun channelSlots(): Map<Int, String> = channelManager.channelSlots()

    /** 1..7 通道的编辑名；未编辑时逐项回退默认名。 */
    fun channelNames(): Map<Int, String> = ChannelNamePolicy.decode(settings.channelNames)

    /** 编辑单个通道名：规范化后写回；返回实际保存的名称。 */
    fun renameChannel(channelNo: Int, name: String): String? {
        if (channelNo !in 1..ChannelPalette.MAX_CHANNELS) return null
        val current = channelNames().toMutableMap()
        val normalized = ChannelNamePolicy.normalize(name, channelNo)
        current[channelNo] = normalized
        settings.channelNames = ChannelNamePolicy.encode(current)
        diagnostics.log("CHANNEL_RENAME CH$channelNo -> $normalized")
        return normalized
    }

    /** 恢复全部通道名为默认值。 */
    fun resetChannelNames() {
        settings.channelNames = ""
        diagnostics.log("CHANNEL_RENAME_RESET")
    }
    fun disconnectAll(): Int {
        cancelAllReconnects()
        stopRecoveryCycle()
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
                diagnostics.log("AUTO_RECONNECT_STOP $mac attempts=$attempts reason=连续失败，需手动连接")
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

    /** 已登记卡尺摘要；连接页据此常驻显示，即使本轮未扫描到也可见。 */
    suspend fun knownDevices(): List<KnownDevice> =
        deviceConfig.recentDevices(ChannelPalette.MAX_CHANNELS).map { device ->
            KnownDevice(
                mac = device.mac,
                name = deviceDisplayName(device.name, device.customName, device.mac),
                lastConnectedAt = device.lastConnectedAt
            )
        }

    /** 刷新已登记 MAC 缓存；连接成功会立即追加，避免重启扫描时查库滞后。 */
    private fun refreshKnownMacs() {
        scope.launch {
            val loaded = runCatching {
                deviceConfig.recentDevices(ChannelPalette.MAX_CHANNELS)
                    .map { it.mac.trim().uppercase() }
                    .toSet()
            }.getOrElse { error ->
                diagnostics.log("KNOWN_MAC_LOAD_FAIL ${error.javaClass.simpleName}")
                knownMacs
            }
            knownMacs = loaded
            diagnostics.log("KNOWN_MAC_LOADED ${loaded.size}")
        }
    }

    /** 扫描重新发现已登记卡尺时自动补连；每个跳过分支都留诊断，不静默失败。 */
    fun autoConnectScanned(mac: String) {
        val key = mac.trim().uppercase()
        if (!settings.autoConnect) {
            diagnostics.log("SCAN_AUTO_SKIP $key autoConnect=off")
            return
        }
        scope.launch {
            val recent = runCatching {
                deviceConfig.recentDevices(ChannelPalette.MAX_CHANNELS)
                    .map { it.mac.trim().uppercase() }
                    .toSet()
            }.getOrElse { error ->
                diagnostics.log("SCAN_AUTO_KNOWN_FAIL ${error.javaClass.simpleName}")
                knownMacs
            }
            val online = channelManager.channelSlots().values.map { it.uppercase() }.toSet()
            val pending = channelManager.pendingMacs().map { it.uppercase() }.toSet()
            if (!shouldAutoConnectScanned(key, recent, online, pending, settings.autoConnect)) {
                if (eventThrottle.allow("scan_auto_skip:$key")) {
                    diagnostics.log("SCAN_AUTO_SKIP $key known=${key in recent} online=${key in online}")
                }
                return@launch
            }
            diagnostics.log("SCAN_AUTO_CONNECT $key")
            mainHandler.post {
                channelManager.requestConnect(key)
                diagnostics.log("SCAN_AUTO_CONNECT_SILENT $key")
            }
        }
    }

    /** 监听系统蓝牙开关；关闭再打开时清零重连预算并启动有限次补连。 */
    private fun registerBluetoothWatcher() {
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        bluetoothState = if (adapter?.isEnabled == true) BT_STATE_ON else BT_STATE_OFF
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val sticky = ContextCompat.registerReceiver(
            appContext, bluetoothReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        diagnostics.log("BT_WATCHER_REGISTERED state=$bluetoothState sticky=${sticky != null}")
    }

    private fun onBluetoothStateChanged(previous: Int, current: Int) {
        diagnostics.log("BT_STATE $previous -> $current")
        when (bluetoothRecoveryAction(previous, current)) {
            BluetoothRecoveryAction.RECOVER -> {
                synchronized(reconnectLock) {
                    reconnectAttempts.clear()
                    reconnectStopped.clear()
                }
                lastAutoConnectMs = 0L
                startRecoveryCycle()
                diagnostics.log("BT_RECOVER_SILENT")
            }
            BluetoothRecoveryAction.SUSPEND -> {
                stopRecoveryCycle()
                diagnostics.log("BT_OFF_SILENT")
            }
            BluetoothRecoveryAction.NONE -> Unit
        }
    }

    /** 蓝牙恢复后按固定轮次补连；每轮都有终止判定，不会无限 postDelayed。 */
    private fun startRecoveryCycle() {
        mainHandler.removeCallbacks(recoveryRunnable)
        recoveryTick = 0
        diagnostics.log("RECOVERY_START max=$MAX_RECOVERY_TICKS interval=$RECOVERY_INTERVAL_MS")
        mainHandler.post(recoveryRunnable)
    }

    private fun stopRecoveryCycle() {
        mainHandler.removeCallbacks(recoveryRunnable)
    }

    private fun onRecoveryTick() {
        val ready = scanReadiness() == null
        scope.launch {
            val online = channelManager.channelSlots().values.map { it.uppercase() }.toSet()
            val pending = channelManager.pendingMacs().map { it.uppercase() }.toSet()
            val recent = runCatching {
                deviceConfig.recentDevices(ChannelPalette.MAX_CHANNELS).map { it.mac }
            }.getOrElse { error ->
                diagnostics.log("RECOVERY_KNOWN_FAIL ${error.javaClass.simpleName}")
                emptyList()
            }
            val targets = recoveryTargets(recent, online, pending, ChannelPalette.MAX_CHANNELS)
            val decision = recoveryDecision(recoveryTick, targets.size, settings.autoConnect, ready)
            if (decision != RecoveryDecision.CONTINUE) {
                diagnostics.log("RECOVERY_STOP $decision tick=$recoveryTick reason=后台补连结束")
                return@launch
            }
            diagnostics.log("RECOVERY_TICK ${recoveryTick + 1}/$MAX_RECOVERY_TICKS targets=${targets.size}")
            mainHandler.post { targets.forEach { channelManager.requestConnect(it) } }
            recoveryTick++
            mainHandler.postDelayed(recoveryRunnable, RECOVERY_INTERVAL_MS)
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

    /** 卡尺真实广播名回读结果；无 replay，切页重建时不会用旧名覆盖输入框。 */
    private val nameReports = MutableSharedFlow<DeviceNameReport>(extraBufferCapacity = NAME_REPORT_BUFFER)

    /** 已下发 NAM 但尚未回读验证的目标名，按 MAC 隔离，确认前不改本地登记名。 */
    private val pendingNamTargets = ConcurrentHashMap<String, String>()

    @Volatile
    private var lastAutoConnectMs = 0L

    private val idleRunnable = object : Runnable {
        override fun run() {
            val idleMs = if (settings.autoDisconnectEnabled) settings.idleDisconnectMinutes * 60_000L else null
            channelManager.pollIdle(System.currentTimeMillis(), idleMs)
            mainHandler.postDelayed(this, IDLE_POLL_INTERVAL_MS)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val current = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                val previous = bluetoothState
                bluetoothState = current
                onBluetoothStateChanged(previous, current)
            } else {
                diagnostics.log("BT_BROADCAST_SKIP ${intent.action.orEmpty()}")
            }
        }
    }

    private val recoveryRunnable = object : Runnable {
        override fun run() = onRecoveryTick()
    }

    init {
        batchUseCase.ensureDefault(settings.activeBatchId)
        mainHandler.post(idleRunnable)
        refreshKnownMacs()
        registerBluetoothWatcher()
    }

    fun measurements() = database.measurementDao().observeAll()

    /** MAC 到设备显示名；自定义名优先、登记名其次、缺登记时按 MAC 尾 4 位兜底。 */
    fun deviceNames(): Flow<Map<String, String>> =
        database.deviceDao().observeAll().map { devices ->
            devices.associate { device ->
                device.mac to deviceDisplayName(device.name, device.customName, device.mac)
            }
        }

    /** 卡尺真实广播名回读流；设置页订阅后回填输入框，不携带本地登记名。 */
    fun deviceNameReports(): Flow<DeviceNameReport> = nameReports.asSharedFlow()

    /** 清空全部检测记录；Room Flow 会刷新表格与统计，回调带回删除行数。 */
    fun clearMeasurements(onCleared: (Int) -> Unit) {
        scope.launch {
            val result = runCatching { database.measurementDao().clearAll() }
            result.onSuccess { count ->
                diagnostics.log("CLEAR_DATA $count")
                mainHandler.post { onCleared(count) }
            }.onFailure { error ->
                diagnostics.log("CLEAR_DATA_FAIL ${error.javaClass.simpleName}")
                mainHandler.post { uiEvent("清除失败：${error.message ?: "数据库异常"}，请重试") }
            }
        }
    }

    fun exportCsv(uri: Uri, channelNo: Int? = null) {
        scope.launch {
            runCatching { io.exportCsv(uri, channelNo) }
                .onSuccess { report -> uiEvent("已导出 ${report.rows} 行 CSV") }
                .onFailure { error -> reportIoFailure("CSV 导出", error) }
        }
    }

    /** 导出中文 7 列 xlsx；超差测量值使用红色字体。 */
    fun exportColoredSheet(uri: Uri) {
        scope.launch {
            runCatching { io.exportColoredXlsx(uri) }
                .onSuccess { report -> uiEvent("已导出 ${report.rows} 行彩色表格，超差数值已标红") }
                .onFailure { error -> reportIoFailure("彩色表格导出", error) }
        }
    }

    fun importCsv(uri: Uri) {
        scope.launch {
            runCatching { io.importCsv(uri) }
                .onSuccess { report ->
                    uiEvent(
                        "导入 ${report.imported}/${report.rows} 行 · 跳过列 ${report.skippedColumns.size} · " +
                            "解析失败 ${report.parseErrors} · 重复 ${report.duplicates}"
                    )
                }
                .onFailure { error -> reportIoFailure("CSV 导入", error) }
        }
    }

    fun exportDiagnostics(uri: Uri) {
        scope.launch {
            runCatching { diagnostics.export(uri) }
                .onSuccess { lines -> uiEvent("已导出 $lines 条诊断日志") }
                .onFailure { error -> reportIoFailure("诊断日志导出", error) }
        }
    }

    /** 文件操作失败必须给出可见原因并留下可导出日志，不静默结束协程。 */
    private fun reportIoFailure(action: String, error: Throwable) {
        val reason = error.message?.take(60) ?: error.javaClass.simpleName
        diagnostics.log("IO_FAIL $action ${error.javaClass.simpleName}")
        uiEvent("$action 失败：$reason，请检查文件位置后重试")
    }

    /** 新落库批次含超差数据时播放蜂鸣；开关与冷却由纯策略决定。 */
    private fun maybeBeep(overLimit: Boolean) {
        val now = System.currentTimeMillis()
        if (!shouldBeep(overLimit, settings.overLimitBeep, now - lastBeepAt, BEEP_COOLDOWN_MS)) return
        lastBeepAt = now
        overLimitBeep.play()
        if (eventThrottle.allow("over_limit_alert")) {
            mainHandler.post { uiEvent(overLimitAlertMessage()) }
        }
    }

    fun close() {
        mainHandler.removeCallbacks(idleRunnable)
        stopRecoveryCycle()
        cancelAllReconnects()
        runCatching { appContext.unregisterReceiver(bluetoothReceiver) }
            .onFailure { error -> diagnostics.log("BT_WATCHER_UNREG_FAIL ${error.javaClass.simpleName}") }
        eventThrottle.clear()
        pendingNamTargets.clear()
        channelManager.close()
        capture.close()
        overLimitBeep.close()
    }

    private fun handleChannelEvent(event: ChannelEvent) {
        when (event) {
            is ChannelEvent.Connected -> {
                resetReconnect(event.mac)
                knownMacs = knownMacs + event.mac.trim().uppercase()
                settings.lastMac = event.mac
                deviceConfig.registerConnected(event.mac, "GL-BLE#${event.mac.takeLast(4)}", event.channelNo)
                uiEvent("CH${event.channelNo} 已连接 ${event.mac}")
            }
            is ChannelEvent.Disconnected -> {
                clearContinuous(event.channelNo)
                pendingNamTargets.remove(event.mac)
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
            is ChannelEvent.NamResult -> handleNamResult(event)
            is ChannelEvent.NameReport -> handleNameReport(event)
        }
    }

    /** NAM:OK 只表示设备受理；必须回读真实广播名确认后才同步本地登记名。 */
    private fun handleNamResult(event: ChannelEvent.NamResult) {
        diagnostics.log("NAM_RESULT ${event.mac} ${event.name} ok=${event.ok} ${event.reason}")
        if (!event.ok) {
            val reason = event.reason.ifBlank { "设备回 ERR" }
            pendingNamTargets.remove(event.mac)
            diagnostics.log("NAM_NOT_APPLIED ${event.mac} $reason")
            uiEvent("CH${event.channelNo} 改名失败：$reason；App 本地登记名未改动")
            return
        }
        pendingNamTargets[event.mac] = event.name
        diagnostics.log("NAM_VERIFY_REQUEST ${event.mac} target=${event.name}")
        uiEvent("CH${event.channelNo} 设备已回 NAM:OK，正在回读卡尺真实广播名验证…")
        mainHandler.post {
            val accepted = channelManager.queryNam(event.channelNo)
            diagnostics.log("NAM_VERIFY_QUERY ${event.mac} accepted=$accepted")
            if (!accepted) {
                pendingNamTargets.remove(event.mac)
                diagnostics.log("NAM_VERIFY_SEND_FAIL ${event.mac}")
                uiEvent(namVerifyFailureMessage("通道已断开，无法下发查询", event.channelNo))
            }
        }
    }

    /** 回读名与期望名一致才确认生效；不一致、超时或写失败都不改本地登记名。 */
    private fun handleNameReport(event: ChannelEvent.NameReport) {
        val target = pendingNamTargets.remove(event.mac)
        diagnostics.log(
            "NAM_REPORT ${event.mac} name=${event.name} ok=${event.ok} " +
                "target=${target.orEmpty()} ${event.reason}"
        )
        val reason = event.reason.ifBlank { "卡尺未回名称查询" }
        if (!event.ok) {
            diagnostics.log("NAM_QUERY_FAIL_EVENT ${event.mac} $reason")
            uiEvent(
                if (target == null) namQueryFailureMessage(reason)
                else namVerifyFailureMessage(reason, event.channelNo)
            )
            return
        }
        val verified = target != null && verifyNam(target, event.name) == NamVerification.CONFIRMED
        if (!nameReports.tryEmit(DeviceNameReport(event.mac, event.name, target, verified))) {
            diagnostics.log("NAM_REPORT_EMIT_FAIL ${event.mac}")
        }
        if (target == null) {
            uiEvent(namQueryMessage(event.name))
            return
        }
        if (verified) deviceConfig.renameDevice(event.mac, event.name)
        diagnostics.log("NAM_VERIFY ${event.mac} verified=$verified target=$target actual=${event.name}")
        uiEvent(namVerificationMessage(target, event.name, event.channelNo))
    }
}

/** 名称回读流的缓冲容量；无订阅者时也不阻塞回包线程。 */
internal const val NAME_REPORT_BUFFER = 8

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
