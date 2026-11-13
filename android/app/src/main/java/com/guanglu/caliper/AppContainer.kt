package com.guanglu.caliper

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.guanglu.caliper.core.bluetooth.BlessedTransport
import com.guanglu.caliper.core.bluetooth.CommandQueue
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.channel.ChannelEvent
import com.guanglu.caliper.core.bluetooth.channel.ChannelManager
import com.guanglu.caliper.core.bluetooth.device.CaliperCommand
import com.guanglu.caliper.core.database.AppDatabase
import com.guanglu.caliper.core.datastore.SettingsRepository
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

    val settings = SettingsRepository(context)
    val diagnostics = DiagnosticsUseCase(context)
    private val database = AppDatabase.build(context)

    private val transport = BlessedTransport(context)
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
    fun disconnectAll() = channelSlots().values.forEach { channelManager.disconnect(it) }

    /** UI 订阅的状态文案；MainActivity 在 onResume 里设置。 */
    @Volatile
    var uiEvent: (String) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = object : Runnable {
        override fun run() {
            channelManager.pollIdle()
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
            is ChannelEvent.Disconnected -> uiEvent("CH${event.channelNo} 断开：${event.reason}")
            is ChannelEvent.Rejected -> uiEvent(event.reason)
            is ChannelEvent.LimitReached -> uiEvent("已达该机型并发上限 ${event.actual}，剩余设备未连接；已连通道继续采集")
            is ChannelEvent.Ack -> uiEvent(
                "CH${event.channelNo} ${event.command} ${if (event.ok) "OK" else "ERR（重试中）"}"
            )
            is ChannelEvent.NamResult -> uiEvent(
                if (event.ok) "CH${event.channelNo} 改名成功，可能需重连生效"
                else "CH${event.channelNo} 改名失败（设备回 ERR）"
            )
        }
    }
}
