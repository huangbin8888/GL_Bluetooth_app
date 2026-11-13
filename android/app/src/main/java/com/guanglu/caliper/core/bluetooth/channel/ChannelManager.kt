package com.guanglu.caliper.core.bluetooth.channel

import com.guanglu.caliper.core.bluetooth.BleConnectionCallback
import com.guanglu.caliper.core.bluetooth.BleTransport
import com.guanglu.caliper.core.bluetooth.CommandQueue
import com.guanglu.caliper.core.bluetooth.ScannedDevice
import com.guanglu.caliper.core.bluetooth.device.CaliperCommand
import com.guanglu.caliper.core.bluetooth.device.CaliperDevice
import com.guanglu.caliper.core.bluetooth.device.GuangluCaliperHandler
import com.guanglu.caliper.core.model.CaliperFrame
import com.guanglu.caliper.core.model.ChannelPalette
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.schedule

private const val CONNECT_TIMEOUT_MS = 15_000L

sealed class ChannelEvent {
    data class Connected(val channelNo: Int, val mac: String) : ChannelEvent()
    data class Disconnected(val channelNo: Int, val mac: String, val reason: String) : ChannelEvent()
    data class Rejected(val mac: String, val reason: String) : ChannelEvent()
    data class LimitReached(val actual: Int) : ChannelEvent()
    data class Ack(val channelNo: Int, val command: String, val ok: Boolean) : ChannelEvent()
    data class NamResult(val channelNo: Int, val mac: String, val name: String, val ok: Boolean) : ChannelEvent()
}

/**
 * 7 路并发总控：槽位分配、串行连接、串行命令与断连隔离。
 *
 * @author AI-Generated
 */
class ChannelManager(
    private val transport: BleTransport,
    private val commandQueue: CommandQueue,
    private val driver: CaliperDevice = GuangluCaliperHandler,
    private val onMeasurement: (Int, String, CaliperFrame.Measurement) -> Unit,
    private val onEvent: (ChannelEvent) -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {},
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : BleConnectionCallback {

    val allocator = ChannelAllocator()
    private val pendingConnections = ArrayDeque<String>()
    private val slots = ConcurrentHashMap<String, ChannelSlot>()
    private val lastCommand = ConcurrentHashMap<String, ByteArray>()
    private val retryCount = ConcurrentHashMap<String, Int>()
    private val connectTimers = ConcurrentHashMap<String, TimerTask>()
    private val lastActivity = ConcurrentHashMap<String, Long>()
    private val pendingNam = ConcurrentHashMap<String, String>()
    private val retryTimer = Timer("cmd-retry", true)

    @Volatile
    private var connectingMac: String? = null

    fun startScan(onDevice: (ScannedDevice) -> Unit) = transport.startScan(onDevice)
    fun stopScan() = transport.stopScan()
    fun onlineCount(): Int = allocator.onlineCount()
    fun channelSlots(): Map<Int, String> = allocator.slots()

    /** 连接前先申请槽位；第 8 台或超出机型上限直接拒绝并给出明确事件。已在线或已在排队/连接中的 MAC 直接返回，避免重复占槽。 */
    @Synchronized
    fun requestConnect(mac: String): Boolean {
        if (allocator.slotOf(mac) != null) return true
        if (mac == connectingMac || pendingConnections.contains(mac)) return true
        if (allocator.acquire(mac) == null) {
            onEvent(
                ChannelEvent.Rejected(
                    mac,
                    "通道已满（上限 ${allocator.deviceLimit} 台），第 ${allocator.deviceLimit + 1} 台未连接"
                )
            )
            return false
        }
        pendingConnections.addLast(mac)
        pumpConnections()
        return true
    }

    /** 已排队与正在连接（未拿到槽位）的设备 MAC 集合，用于自动重连去重。 */
    @Synchronized
    fun pendingMacs(): Set<String> = buildSet {
        addAll(pendingConnections)
        connectingMac?.let { add(it) }
    }

    @Synchronized
    private fun pumpConnections() {
        if (connectingMac != null) return
        val mac = pendingConnections.removeFirstOrNull() ?: return
        connectingMac = mac
        armConnectTimeout(mac)
        transport.connect(mac, this)
    }

    private fun armConnectTimeout(mac: String) {
        connectTimers.remove(mac)?.cancel()
        val task = retryTimer.schedule(connectTimeoutMs) {
            if (connectingMac == mac) {
                connectingMac = null
                connectTimers.remove(mac)
                val slotNo = allocator.release(mac) ?: 0
                slots.remove(mac)
                commandQueue.cancelFor(mac)
                onEvent(
                    ChannelEvent.Disconnected(
                        slotNo,
                        mac,
                        "连接超时（${connectTimeoutMs / 1000}s 未收到服务发现回调）"
                    )
                )
                pumpConnections()
            }
        }
        connectTimers[mac] = task
    }

    private fun clearConnectTimeout(mac: String) {
        connectTimers.remove(mac)?.cancel()
    }

    /** 60ms 帧尾 flush 轮询入口，由 AppContainer 的定时器驱动；同时执行空闲自动断连。 */
    fun pollIdle(nowMs: Long = clock(), idleTimeoutMs: Long? = null) {
        slots.values.forEach { it.onIdle(nowMs) }
        val timeout = idleTimeoutMs ?: return
        if (timeout <= 0L) return
        val targets = slots.keys.filter { mac ->
            val last = lastActivity[mac] ?: nowMs
            nowMs - last > timeout
        }
        targets.forEach { mac ->
            val minutes = timeout / 60_000L
            disconnect(mac, "空闲自动断连（${minutes} 分钟无数据）")
        }
    }

    fun sendCommand(channelNo: Int, command: CaliperCommand): Boolean {
        val mac = allocator.macOf(channelNo) ?: return false
        val bytes = driver.commandBytes(command)
        lastCommand[mac] = bytes
        retryCount[mac] = 0
        commandQueue.submit(mac, bytes)
        onDiagnostic("TX CH$channelNo $mac ${String(bytes, Charsets.US_ASCII)}")
        return true
    }

    /** 对指定通道下发 NAM 名称。 */
    fun applyNam(channelNo: Int, name: String): Boolean {
        val mac = allocator.macOf(channelNo) ?: return false
        if (!GuangluCaliperHandler.validateNam(name)) return false
        val bytes = GuangluCaliperHandler.buildNamCommand(name)
        pendingNam[mac] = name
        commandQueue.submit(mac, bytes)
        onDiagnostic("TX CH$channelNo $mac ${String(bytes, Charsets.US_ASCII)}")
        return true
    }

    @Synchronized
    fun disconnect(mac: String, reason: String = "用户断开") {
        clearConnectTimeout(mac)
        if (connectingMac == mac) connectingMac = null
        pendingConnections.remove(mac)
        transport.disconnect(mac)
        val slot = allocator.release(mac) ?: 0
        slots.remove(mac)
        commandQueue.cancelFor(mac)
        lastCommand.remove(mac)
        lastActivity.remove(mac)
        onEvent(ChannelEvent.Disconnected(slot, mac, reason))
    }

    /** 断开全部在线通道并取消所有排队 / 进行中的连接，返回断开与取消的设备数。 */
    @Synchronized
    fun disconnectAll(): Int {
        val macs = slots.keys.toList() + pendingConnections.toList() + listOfNotNull(connectingMac)
        val unique = macs.distinct()
        unique.forEach { mac -> disconnect(mac, "用户断开") }
        return unique.size
    }

    fun setDeviceLimit(limit: Int) = allocator.setDeviceLimit(limit)

    /** 释放所有通道、待发命令与重试任务。 */
    fun close() {
        connectTimers.values.forEach { it.cancel() }
        connectTimers.clear()
        retryTimer.cancel()
        pendingConnections.clear()
        connectingMac = null
        val macs = slots.keys.toList()
        slots.clear()
        macs.forEach { mac ->
            transport.disconnect(mac)
            commandQueue.cancelFor(mac)
            allocator.release(mac)
        }
        lastCommand.clear()
        retryCount.clear()
        lastActivity.clear()
        pendingNam.clear()
    }

    // ---- BleConnectionCallback ----

    override fun onReady(mac: String) {
        clearConnectTimeout(mac)
        connectingMac = null
        val slotNo = allocator.slotOf(mac) ?: return
        slots[mac] = ChannelSlot(driver, sinkFor(mac))
        lastActivity[mac] = clock()
        onEvent(ChannelEvent.Connected(slotNo, mac))
        pumpConnections()
    }

    override fun onBytes(mac: String, bytes: ByteArray) {
        val text = String(bytes, Charsets.US_ASCII)
        onDiagnostic("RX $mac $text")
        lastActivity[mac] = clock()
        slots[mac]?.onBytes(bytes)
    }

    override fun onDisconnected(mac: String, reason: String) {
        clearConnectTimeout(mac)
        if (connectingMac == mac) connectingMac = null
        val slotNo = allocator.release(mac) ?: 0
        slots.remove(mac)
        commandQueue.cancelFor(mac)
        lastCommand.remove(mac)
        onEvent(ChannelEvent.Disconnected(slotNo, mac, reason))
        pumpConnections()
    }

    override fun onConnectionFailed(mac: String, reason: String) {
        clearConnectTimeout(mac)
        val slotNo = allocator.release(mac) ?: 0
        slots.remove(mac)
        commandQueue.cancelFor(mac)
        connectingMac = null
        onDiagnostic("CONNECT_FAIL $mac $reason")
        // 错误指向资源耗尽时下调机型上限。
        val looksLikeLimit = listOf("133", "RESOURCE", "LIMIT", "MAX", "TOO_MANY")
            .any { reason.uppercase().contains(it) }
        val online = allocator.onlineCount()
        if (looksLikeLimit && online in 1 until ChannelPalette.MAX_CHANNELS) {
            allocator.setDeviceLimit(online)
            onEvent(ChannelEvent.LimitReached(online))
        } else {
            onEvent(ChannelEvent.Disconnected(slotNo, mac, reason))
        }
        pumpConnections()
    }

    override fun onWriteCompleted(mac: String, success: Boolean) {
        commandQueue.onWriteCompleted(success)
        if (!success) onDiagnostic("WRITE_FAIL $mac")
    }

    // ---- 内部 ----

    private fun sinkFor(mac: String) = object : FrameSink {
        override fun onMeasurement(frame: CaliperFrame.Measurement) {
            val channelNo = allocator.slotOf(mac) ?: return
            onMeasurement(channelNo, mac, frame)
        }

        override fun onWrite(bytes: ByteArray) {
            commandQueue.submit(mac, bytes)
        }

        override fun onAck(frame: CaliperFrame.Ack) {
            val channelNo = allocator.slotOf(mac) ?: return
            if (frame.command == GuangluCaliperHandler.COMMAND_NAM) {
                val name = pendingNam.remove(mac).orEmpty()
                onEvent(ChannelEvent.NamResult(channelNo, mac, name, frame.ok))
                return
            }
            onEvent(ChannelEvent.Ack(channelNo, frame.command, frame.ok))
            if (!frame.ok && frame.retryable) {
                retry(mac, frame.command)
            }
        }

        override fun onUnknown(text: String) {
            onDiagnostic("UNKNOWN $mac $text")
        }
    }

    /** 采集与模式指令的 ERR 回包按指数退避重试 2 次。 */
    private fun retry(mac: String, command: String) {
        val attempt = (retryCount[mac] ?: 0) + 1
        if (attempt > 2) {
            onDiagnostic("RETRY_GIVEUP $mac $command")
            return
        }
        retryCount[mac] = attempt
        val bytes = lastCommand[mac] ?: return
        val delay = 500L * (1L shl (attempt - 1))
        onDiagnostic("RETRY $mac $command attempt=$attempt delay=${delay}ms")
        retryTimer.schedule(delay) {
            commandQueue.submit(mac, bytes)
        }
    }
}
