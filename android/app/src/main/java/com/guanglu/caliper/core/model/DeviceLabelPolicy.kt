package com.guanglu.caliper.core.model

/** 无 MAC 可用时的设备显示名兜底。 */
internal const val UNKNOWN_DEVICE_NAME = "未登记设备"

/** 通道 chip 副行短标签的最大字符数。 */
internal const val DEVICE_TAG_MAX_CHARS = 10

/** 自动登记名的前缀。 */
internal const val DEVICE_NAME_PREFIX = "GL-BLE#"

private val MAC_SEPARATORS = Regex("[:-]")

/** 去掉 MAC 分隔符，返回紧凑大写串。 */
internal fun compactMac(mac: String): String = mac.replace(MAC_SEPARATORS, "").uppercase()

/** 按 MAC 尾 4 位生成稳定兜底名。 */
internal fun autoDeviceName(mac: String): String {
    val compact = compactMac(mac)
    return if (compact.isEmpty()) UNKNOWN_DEVICE_NAME else DEVICE_NAME_PREFIX + compact.takeLast(4)
}

/** 用户自定义名优先、登记名其次、最后按 MAC 尾 4 位生成稳定兜底名。 */
fun deviceDisplayName(name: String?, customName: String?, mac: String): String {
    val preferred = (customName ?: "").ifBlank { name ?: "" }.trim()
    if (preferred.isEmpty()) return autoDeviceName(mac)
    if (preferred.startsWith(DEVICE_NAME_PREFIX) && preferred.contains(':')) return autoDeviceName(mac)
    return preferred
}

/** 把完整设备名压成通道 chip 副行的短标签，例如 GL-BLE#EEFF -> #EEFF。 */
fun deviceShortTag(displayName: String, mac: String): String {
    val compact = compactMac(mac)
    val fallback = if (compact.isEmpty()) "#未知" else "#${compact.takeLast(4)}"
    val hash = displayName.lastIndexOf('#')
    val tag = if (hash >= 0) "#${displayName.substring(hash + 1)}" else displayName
    return tag.trim().take(DEVICE_TAG_MAX_CHARS).ifBlank { fallback }
}

/** 表格「设备名称」列：登记名优先，缺登记时用兜底名，绝不空白。 */
fun measurementDeviceName(names: Map<String, String>, mac: String): String =
    names[mac]?.takeIf { it.isNotBlank() }?.trim() ?: autoDeviceName(mac)

/** 通道筛选 chip 副行：该通道已连接时返回设备短标签，空闲时返回 null。 */
fun channelDeviceTag(names: Map<String, String>, mac: String?): String? =
    if (mac.isNullOrBlank()) null else deviceShortTag(measurementDeviceName(names, mac), mac)

/** 通道号到设备短标签；未占用通道不含条目。 */
fun channelDeviceTags(slots: Map<Int, String>, names: Map<String, String>): Map<Int, String> =
    slots.mapNotNull { (channelNo, mac) ->
        channelDeviceTag(names, mac)?.let { channelNo to it }
    }.toMap()
