package com.guanglu.caliper.core.bluetooth

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.guanglu.caliper.core.bluetooth.device.GuangluCaliperHandler
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.WriteType
import java.util.concurrent.ConcurrentHashMap

/**
 * blessed-android 2.5.3 适配器。API 签名于 2026-11-12 对照官方 README 核实：
 * BluetoothCentralManager(Context, BluetoothCentralManagerCallback, Handler)、
 * scanForPeripherals / stopScan / connectPeripheral / cancelConnection / getPeripheral、
 * getCharacteristic(UUID, UUID) / setNotify / writeCharacteristic / onCharacteristicUpdate。
 * 回调统一跑在主线程 Handler；跨 7 路的写命令由 CommandQueue 另行串行化。
 */
class BlessedTransport(
    context: Context,
    private val namePrefix: String = GuangluCaliperHandler.NAME_PREFIX
) : BleTransport {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val peripherals = ConcurrentHashMap<String, BluetoothPeripheral>()
    private val callbacks = ConcurrentHashMap<String, BleConnectionCallback>()

    @Volatile
    private var onDevice: (ScannedDevice) -> Unit = {}

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            val name = peripheral.name ?: return
            if (!name.startsWith(namePrefix)) return
            onDevice(ScannedDevice(peripheral.address, name, scanResult.rssi))
        }

        override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
            peripherals[peripheral.address] = peripheral
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            peripherals.remove(peripheral.address)
            callbacks.remove(peripheral.address)
                ?.onConnectionFailed(peripheral.address, status.toString())
        }

        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
            peripherals.remove(peripheral.address)
            callbacks.remove(peripheral.address)
                ?.onDisconnected(peripheral.address, status.toString())
        }
    }

    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            val characteristic = peripheral.getCharacteristic(
                GuangluCaliperHandler.SERVICE_UUID,
                GuangluCaliperHandler.CHARACTERISTIC_UUID
            )
            if (characteristic != null) {
                // setNotify 会由库完成 CCCD 写入；成功与否在 onNotificationStateUpdate 观察。
                peripheral.setNotify(characteristic, true)
            }
            callbacks[peripheral.address]?.onReady(peripheral.address)
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (status == GattStatus.SUCCESS) {
                callbacks[peripheral.address]?.onBytes(peripheral.address, value)
            }
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            callbacks[peripheral.address]
                ?.onWriteCompleted(peripheral.address, status == GattStatus.SUCCESS)
        }
    }

    private val central = BluetoothCentralManager(appContext, centralCallback, mainHandler)

    override fun startScan(onDevice: (ScannedDevice) -> Unit) {
        this.onDevice = onDevice
        central.scanForPeripherals()
    }

    override fun stopScan() {
        central.stopScan()
    }

    override fun connect(mac: String, callback: BleConnectionCallback) {
        // 基线 10.3：连接前先停扫描；connectPeripheral 同时只允许一个 outstanding。
        central.stopScan()
        callbacks[mac] = callback
        val peripheral = central.getPeripheral(mac)
        peripherals[mac] = peripheral
        central.connectPeripheral(peripheral, peripheralCallback)
    }

    override fun disconnect(mac: String) {
        peripherals.remove(mac)?.let { central.cancelConnection(it) }
        callbacks.remove(mac)
    }

    override fun write(mac: String, bytes: ByteArray): Boolean {
        val peripheral = peripherals[mac] ?: return false
        val characteristic = peripheral.getCharacteristic(
            GuangluCaliperHandler.SERVICE_UUID,
            GuangluCaliperHandler.CHARACTERISTIC_UUID
        ) ?: return false
        return peripheral.writeCharacteristic(characteristic, bytes, WriteType.WITHOUT_RESPONSE)
    }

    override fun isConnected(mac: String): Boolean = peripherals.containsKey(mac)
}
