package com.guanglu.caliper.core.usecase

import com.guanglu.caliper.core.model.ChannelPalette

/**
 * V1 CSV 编解码（基线 4.2 / 11.2）：稳定大写列 key + 中文别名、UTF-8 BOM、
 * 未识别列整列跳过、单行解析失败不中断整表。纯 Kotlin，零 Android 依赖，可 JVM 测试。
 * V1 只支持「一行表头 + N 行数据」的平表；合并单元格 / 公式 / 样式属 V2。
 */
object CsvCodec {

    const val BOM = "\uFEFF"

    val COLUMNS = listOf(
        "DATE", "TIME", "SEQ", "BATCH", "ITEM", "NOMINAL", "LOWER_TOL", "UPPER_TOL",
        "VALUE_MM", "RAW_VALUE", "RAW_UNIT", "VERDICT", "SOURCE", "DEVICE_MAC",
        "CHANNEL", "NOTE"
    )

    private val ALIASES: Map<String, List<String>> = mapOf(
        "DATE" to listOf("日期", "检验日期", "检测日期"),
        "TIME" to listOf("时间", "检验时间"),
        "SEQ" to listOf("序号", "编号"),
        "BATCH" to listOf("批次", "生产批次", "批号"),
        "ITEM" to listOf("检验项目", "项目", "尺寸名称"),
        "NOMINAL" to listOf("公称值", "规格", "检验标准", "标准值"),
        "LOWER_TOL" to listOf("下偏差", "公差下限"),
        "UPPER_TOL" to listOf("上偏差", "公差上限"),
        "VALUE_MM" to listOf("实测值", "测量值", "读数"),
        "RAW_VALUE" to listOf("原始值"),
        "RAW_UNIT" to listOf("单位"),
        "VERDICT" to listOf("判定", "结论"),
        "SOURCE" to listOf("来源", "数据来源"),
        "DEVICE_MAC" to listOf("设备MAC", "卡尺编号"),
        "CHANNEL" to listOf("通道"),
        "NOTE" to listOf("备注", "说明")
    )

    data class ParseResult(
        val headers: List<String>,
        val rows: List<Map<String, String>>,
        val skippedColumns: List<String>,
        val parseErrors: Int,
        val bomRemoved: Boolean,
        val fatal: String? = null
    )

    fun canonical(header: String): String? {
        val key = header.trim()
        if (COLUMNS.any { it.equals(key, ignoreCase = true) }) {
            return COLUMNS.first { it.equals(key, ignoreCase = true) }
        }
        return ALIASES.entries.firstOrNull { (_, aliases) ->
            aliases.any { it.equals(key, ignoreCase = true) }
        }?.key
    }

    fun parse(text: String): ParseResult {
        val bomRemoved = text.startsWith(BOM)
        val clean = if (bomRemoved) text.removePrefix(BOM) else text
        val lines = clean.split(Regex("\\r\\n|\\n|\\r")).filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return ParseResult(emptyList(), emptyList(), emptyList(), 0, bomRemoved, "空文件")
        }
        val headers = parseLine(lines[0])
        val mapping = headers.map { canonical(it) }
        val skipped = headers.filter { canonical(it) == null }
        if (!mapping.contains("DATE")) {
            return ParseResult(headers, emptyList(), skipped, 0, bomRemoved, "缺少强制列 DATE")
        }
        val rows = mutableListOf<Map<String, String>>()
        var parseErrors = 0
        for (i in 1 until lines.size) {
            val cells = parseLine(lines[i])
            if (cells.all { it.isBlank() }) continue
            val row = LinkedHashMap<String, String>()
            mapping.forEachIndexed { index, key ->
                if (key != null && !row.containsKey(key)) {
                    row[key] = cells.getOrElse(index) { "" }.trim()
                }
            }
            val value = row["VALUE_MM"]
            if (value.isNullOrBlank() || value.toBigDecimalOrNull() == null) {
                parseErrors++
                continue
            }
            if (row["DATE"].isNullOrBlank()) {
                parseErrors++
                continue
            }
            rows += row
        }
        return ParseResult(headers, rows, skipped, parseErrors, bomRemoved)
    }

    /** 返回 (通道号, 是否历史通道)。CH8–CH10 保留原值并标 legacy，不丢历史数据。 */
    fun channelOf(raw: String): Pair<Int, Boolean> {
        val value = raw.trim().uppercase()
        val number = value.removePrefix("CH").toIntOrNull() ?: return 0 to true
        return if (number in 1..ChannelPalette.MAX_CHANNELS) number to false else number to true
    }

    fun export(rows: List<Map<String, String>>): String {
        val sb = StringBuilder()
        sb.append(COLUMNS.joinToString(",")).append("\r\n")
        rows.forEach { row ->
            sb.append(COLUMNS.joinToString(",") { escape(row[it].orEmpty()) }).append("\r\n")
        }
        return sb.toString()
    }

    fun escape(cell: String): String {
        val needsQuotes = cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = cell.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    private fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    out += cell.toString()
                    cell.setLength(0)
                }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }
}
