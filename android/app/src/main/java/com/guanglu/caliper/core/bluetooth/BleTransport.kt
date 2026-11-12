package com.guanglu.caliper.core.bluetooth

/** 与具体 BLE 库解耦的传输接口；BlessedTransport 是唯一实现。 */
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
