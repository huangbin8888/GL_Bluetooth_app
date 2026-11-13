package com.guanglu.caliper.core.bluetooth

/**
 * BLE 扫描、连接、写入与连接状态查询接口。
 *
 * @author AI-Generated
 */
data class ScannedDevice(val mac: String, val name: String, val rssi: Int)

interface BleConnectionCallback {
    fun onReady(mac: String)
    fun onBytes(mac: String, bytes: ByteArray)
    fun onDisconnected(mac: String, reason: String)
    fun onConnectionFailed(mac: String, reason: String)
    fun onWriteCompleted(mac: String, success: Boolean)
}

interface BleTransport {
    fun startScan(onDevice: (ScannedDevice) -> Unit)
    fun stopScan()
    fun connect(mac: String, callback: BleConnectionCallback)
    fun disconnect(mac: String)
    fun write(mac: String, bytes: ByteArray): Boolean
    fun isConnected(mac: String): Boolean
}
