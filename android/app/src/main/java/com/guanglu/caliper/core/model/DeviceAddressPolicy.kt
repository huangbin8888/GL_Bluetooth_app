package com.guanglu.caliper.core.model

/**
 * 设备列表里的蓝牙地址与名称展示策略；地址必须完整，禁止只取尾段。
 *
 * @author AI-Generated
 */

/** 设备列表中的地址原文：去空白并统一大写，空值返回占位符，绝不截断。 */
internal fun fullBluetoothAddress(mac: String): String {
    val normalized = mac.trim().uppercase()
    return normalized.ifEmpty { "未知地址" }
}

/** 发现设备条目的两行文字：第一行名称，第二行完整 MAC 与信号强度。 */
internal fun scannedDeviceLabel(name: String, mac: String, rssi: Int): String =
    "$name\n${fullBluetoothAddress(mac)} · ${rssi}dBm"

/** 已登记卡尺条目的两行文字：第一行名称，第二行完整 MAC。 */
internal fun knownDeviceLabel(name: String, mac: String): String =
    "$name\n${fullBluetoothAddress(mac)}"

/** 连接类提示里使用的完整地址，避免只显示尾 4 位。 */
internal fun deviceAddressHint(mac: String): String = fullBluetoothAddress(mac)

/** 判断一段文字是否包含完整 MAC，而不是只有尾段。 */
internal fun containsFullAddress(text: String, mac: String): Boolean =
    text.contains(fullBluetoothAddress(mac))
