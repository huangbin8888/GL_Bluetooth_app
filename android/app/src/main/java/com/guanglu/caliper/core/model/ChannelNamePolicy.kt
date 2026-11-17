package com.guanglu.caliper.core.model

/**
 * 通道中文命名的默认值、规范化与导入反查策略。
 *
 * @author AI-Generated
 */
object ChannelNamePolicy {
    const val MAX_NAME_CHARS = 12
    private const val SEP = "|"
    private val FORBIDDEN = Regex("[,\"\\r\\n\\t|/\\\\:*?<>\\u0000-\\u001F]")
    private val NUMBER = Regex("^(?:CH|通道)\\s*(\\d+)$", RegexOption.IGNORE_CASE)

    /** 通道号对应的默认名称；越界通道归为历史通道。 */
    fun defaultName(channelNo: Int): String =
        if (channelNo in 1..ChannelPalette.MAX_CHANNELS) channelDisplayName(channelNo) else "历史通道"

    /** 去 CSV 与文件名非法字符、限长；空白或非法规整为默认名。 */
    fun normalize(raw: String?, channelNo: Int): String {
        val cleaned = raw.orEmpty().trim().replace(FORBIDDEN, "").take(MAX_NAME_CHARS).trim()
        return cleaned.ifEmpty { defaultName(channelNo) }
    }

    /** 从持久化字符串还原 1..7 通道名，缺项或损坏项回退默认名。 */
    fun decode(raw: String?): Map<Int, String> {
        val parts = raw.orEmpty().split(SEP, limit = ChannelPalette.MAX_CHANNELS)
        return (1..ChannelPalette.MAX_CHANNELS).associate { no ->
            no to normalize(parts.getOrNull(no - 1), no)
        }
    }

    /** 把 1..7 通道名编码为持久化字符串，保证往返一致。 */
    fun encode(names: Map<Int, String>): String =
        (1..ChannelPalette.MAX_CHANNELS).joinToString(SEP) { normalize(names[it], it) }

    /** 取编辑后的通道名，缺失时回退默认名。 */
    fun nameOf(names: Map<Int, String>, channelNo: Int): String = normalize(names[channelNo], channelNo)

    /**
     * 导入时按通道名反查通道号：先匹配自定义名，再解析 CHn / 通道n。
     * 返回通道号与历史通道标记；未识别时通道号为 0。
     */
    fun numberFromName(raw: String, names: Map<Int, String>): Pair<Int, Boolean> {
        val value = raw.trim()
        val custom = names.entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
        if (custom != null) return custom.key to false
        val digits = NUMBER.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull() ?: return 0 to true
        return digits to (digits !in 1..ChannelPalette.MAX_CHANNELS)
    }

    /** 单通道导出的建议文件名；全量导出返回固定名。 */
    fun csvFileName(channelNo: Int?, names: Map<Int, String>): String =
        if (channelNo == null) "测量数据.csv" else "${nameOf(names, channelNo)}_测量数据.csv"
}
