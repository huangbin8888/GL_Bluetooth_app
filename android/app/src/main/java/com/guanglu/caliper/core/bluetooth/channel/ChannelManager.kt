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
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.schedule

sealed class ChannelEvent {
    data class Connected(val channelNo: Int, val mac: String) : ChannelEvent()
    data class Disconnected(val channelNo: Int, val mac: String, val reason: String) : ChannelEvent()
    data class Rejected(val mac: String, val reason: String) : ChannelEvent()
    data class LimitReached(val actual: Int) : ChannelEvent()
    data class Ack(val channelNo: Int, val command: String, val ok: Boolean) : ChannelEvent()
    data class NamResult(val channelNo: Int, val ok: Boolean) : ChannelEvent()
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
    private val onDiagnostic: (String) -> Unit = {}
) : BleConnectionCallback {

    val allocator = ChannelAllocator()
    private val pendingConnections = ArrayDeque<String>()
    private val slots = ConcurrentHashMap<String, ChannelSlot>()
    private val lastCommand = ConcurrentHashMap<String, ByteArray>()
    private val retryCount = ConcurrentHashMap<String, Int>()
    private val retryTimer = Timer("cmd-retry", true)

    @Volatile
    private var connectingMac: String? = null

    fun startScan(onDevice: (ScannedDevice) -> Unit) = transport.startScan(onDevice)
    fun stopScan() = transport.stopScan()
    fun onlineCount(): Int = allocator.onlineCount()
    fun channelSlots(): Map<Int, String> = allocator.slots()

    /** 连接前先申请槽位；第 8 台或超出机型上限直接拒绝并给出明确事件。 */
    @Synchronized
    fun requestConnect(mac: String): Boolean {
        if (allocator.slotOf(mac) != null) return true
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

    private fun pumpConnections() {
        if (connectingMac != null) return
        val mac = pendingConnections.removeFirstOrNull() ?: return
        connectingMac = mac
        transport.connect(mac, this)
    }

    /** 60ms 帧尾 flush 轮询入口，由 AppContainer 的定时器驱动。 */
    fun pollIdle(nowMs: Long = System.currentTimeMillis()) {
        slots.values.forEach { it.onIdle(nowMs) }
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
        commandQueue.submit(mac, bytes)
        onDiagnostic("TX CH$channelNo $mac ${String(bytes, Charsets.US_ASCII)}")
        return true
    }

    fun disconnect(mac: String) {
        transport.disconnect(mac)
        val slot = allocator.release(mac) ?: 0
        slots.remove(mac)
        commandQueue.cancelFor(mac)
        lastCommand.remove(mac)
        onEvent(ChannelEvent.Disconnected(slot, mac, "用户断开"))
    }

    fun setDeviceLimit(limit: Int) = allocator.setDeviceLimit(limit)

    /** 释放所有通道、待发命令与重试任务。 */
    fun close() {
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
    }

    // ---- BleConnectionCallback ----

    override fun onReady(mac: String) {
        connectingMac = null
        val slotNo = allocator.slotOf(mac) ?: return
        slots[mac] = ChannelSlot(driver, sinkFor(mac))
        onEvent(ChannelEvent.Connected(slotNo, mac))
        pumpConnections()
    }

    override fun onBytes(mac: String, bytes: ByteArray) {
        val text = String(bytes, Charsets.US_ASCII)
        onDiagnostic("RX $mac $text")
        slots[mac]?.onBytes(bytes)
    }

    override fun onDisconnected(mac: String, reason: String) {
        val slotNo = allocator.release(mac) ?: 0
        slots.remove(mac)
        commandQueue.cancelFor(mac)
        lastCommand.remove(mac)
        onEvent(ChannelEvent.Disconnected(slotNo, mac, reason))
        pumpConnections()
    }

    override fun onConnectionFailed(mac: String, reason: String) {
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
                onEvent(ChannelEvent.NamResult(channelNo, frame.ok))
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
