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
import com.welie.blessed.ConnectionState
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.WriteType
import java.util.concurrent.ConcurrentHashMap

/**
 * blessed-android 传输适配器：设备扫描、连接、通知订阅、命令写入与连接状态查询。
 *
 * @author AI-Generated
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
            if (characteristic == null) {
                callbacks[peripheral.address]
                    ?.onConnectionFailed(peripheral.address, "CHARACTERISTIC_MISSING")
                return
            }
            if (!peripheral.setNotify(characteristic, true)) {
                callbacks[peripheral.address]
                    ?.onConnectionFailed(peripheral.address, "NOTIFY_SUBSCRIBE_REJECTED")
                return
            }
            callbacks[peripheral.address]?.onReady(peripheral.address)
        }

        override fun onNotificationStateUpdate(
            peripheral: BluetoothPeripheral,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (status != GattStatus.SUCCESS) {
                callbacks[peripheral.address]
                    ?.onConnectionFailed(peripheral.address, "NOTIFY_STATE_$status")
            }
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
        // 连接前停止扫描
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
        if (!isConnected(mac)) return false
        val peripheral = peripherals[mac] ?: return false
        val characteristic = peripheral.getCharacteristic(
            GuangluCaliperHandler.SERVICE_UUID,
            GuangluCaliperHandler.CHARACTERISTIC_UUID
        ) ?: return false
        return peripheral.writeCharacteristic(characteristic, bytes, WriteType.WITHOUT_RESPONSE)
    }

    override fun isConnected(mac: String): Boolean =
        peripherals[mac]?.state == ConnectionState.CONNECTED
}
