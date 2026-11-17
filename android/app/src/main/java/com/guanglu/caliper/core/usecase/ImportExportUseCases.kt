package com.guanglu.caliper.core.usecase

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.guanglu.caliper.core.database.dao.DeviceDao
import com.guanglu.caliper.core.database.dao.MeasurementDao
import com.guanglu.caliper.core.database.entity.Measurement
import com.guanglu.caliper.core.datastore.SettingsRepository
import com.guanglu.caliper.core.model.ChannelNamePolicy
import com.guanglu.caliper.core.model.Units
import com.guanglu.caliper.core.model.deviceDisplayName
import com.guanglu.caliper.core.model.exportValueHighlighted
import com.guanglu.caliper.core.model.measurementDeviceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ExportReport(val rows: Int, val bytes: Int)

data class ImportReport(
    val rows: Int,
    val imported: Int,
    val skippedColumns: List<String>,
    val parseErrors: Int,
    val duplicates: Int,
    val bomRemoved: Boolean,
    val fatal: String? = null
)

private data class ExportData(
    val csvRows: List<Map<String, String>>,
    val sheetRows: List<SheetRow>
)

/**
 * 通过 SAF 完成 CSV、彩色表格与 PNG 导入导出。
 *
 * @author AI-Generated
 */
class ImportExportUseCases(
    private val context: Context,
    private val dao: MeasurementDao,
    private val deviceDao: DeviceDao,
    private val settings: SettingsRepository
) {
    suspend fun exportCsv(uri: Uri, channelNo: Int? = null): ExportReport = withContext(Dispatchers.IO) {
        val data = buildExportData(channelNo)
        val bytes = (CsvCodec.BOM + CsvCodec.export(data.csvRows)).toByteArray(Charsets.UTF_8)
        writeBytes(uri, bytes)
        ExportReport(data.csvRows.size, bytes.size)
    }

    /** 导出中文 7 列 xlsx，超差测量值使用红色字体。 */
    suspend fun exportColoredXlsx(uri: Uri, channelNo: Int? = null): ExportReport = withContext(Dispatchers.IO) {
        val data = buildExportData(channelNo)
        val bytes = ColoredSheetCodec.build(CsvCodec.EXPORT_HEADERS, data.sheetRows)
        writeBytes(uri, bytes)
        ExportReport(data.sheetRows.size, bytes.size)
    }

    suspend fun importCsv(uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return@withContext ImportReport(0, 0, emptyList(), 0, 0, false, "无法打开 SAF 输入流")

        val parsed = CsvCodec.parse(text)
        if (parsed.fatal != null) {
            return@withContext ImportReport(
                0, 0, parsed.skippedColumns, parsed.parseErrors, 0, parsed.bomRemoved, parsed.fatal
            )
        }
        val channelNames = ChannelNamePolicy.decode(settings.channelNames)
        val seen = HashSet<String>()
        val entities = mutableListOf<Measurement>()
        var duplicates = 0
        var parseErrors = parsed.parseErrors
        parsed.rows.forEach { row ->
            val valueUm10 = Units.toUm10(row["VALUE_MM"].orEmpty(), 'm')
            if (valueUm10 == null) {
                parseErrors++
                return@forEach
            }
            val capturedAt = try {
                parseDateTime(row["DATE"].orEmpty(), row["TIME"].orEmpty())
            } catch (e: DateTimeException) {
                parseErrors++
                return@forEach
            }
            val channelRaw = row["CHANNEL"].orEmpty().ifBlank { "CH1" }
            val (byName, legacyByName) = ChannelNamePolicy.numberFromName(channelRaw, channelNames)
            val (channelNo, legacy) = if (byName != 0) {
                byName to legacyByName
            } else {
                CsvCodec.channelOf(channelRaw)
            }
            val key = "${row["DATE"]}|${row["TIME"]}|$channelNo|$valueUm10"
            if (!seen.add(key)) {
                duplicates++
                return@forEach
            }
            entities += Measurement(
                batchId = settings.activeBatchId,
                seq = entities.size + 1,
                channelNo = channelNo,
                deviceMac = row["DEVICE_MAC"].orEmpty(),
                valueUm10 = valueUm10,
                rawValue = row["RAW_VALUE"].orEmpty().ifBlank { row["VALUE_MM"].orEmpty() },
                rawUnit = row["RAW_UNIT"].orEmpty().ifBlank { "m" },
                sign = if (valueUm10 < 0) "-" else "+",
                capturedAt = capturedAt,
                elapsedRealtime = capturedAt,
                verdict = Units.judge(valueUm10, settings.lowerLimitUm10, settings.upperLimitUm10).name,
                note = row["NOTE"].orEmpty(),
                source = "IMPORT",
                legacyChannel = legacy
            )
        }
        if (entities.isNotEmpty()) dao.insertAll(entities)
        ImportReport(
            rows = parsed.rows.size,
            imported = entities.size,
            skippedColumns = parsed.skippedColumns,
            parseErrors = parseErrors,
            duplicates = duplicates,
            bomRemoved = parsed.bomRemoved
        )
    }

    suspend fun exportPng(uri: Uri, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } ?: false
    }

    /** CSV 与彩色表格共用同一份 7 列数据。 */
    private suspend fun buildExportData(channelNo: Int?): ExportData {
        val all = dao.all()
        val selected = if (channelNo == null) all else all.filter { it.channelNo == channelNo }
        val names = runCatching {
            deviceDao.all().associate { device ->
                device.mac to deviceDisplayName(device.name, device.customName, device.mac)
            }
        }.getOrDefault(emptyMap())
        val lower = settings.lowerLimitUm10?.let { Units.formatMm(it, settings.decimals) }.orEmpty()
        val upper = settings.upperLimitUm10?.let { Units.formatMm(it, settings.decimals) }.orEmpty()
        val channelNames = ChannelNamePolicy.decode(settings.channelNames)
        val csvRows = mutableListOf<Map<String, String>>()
        val sheetRows = mutableListOf<SheetRow>()
        selected.forEach { item ->
            val (date, time) = formatDateTime(item.capturedAt)
            val value = Units.formatMm(item.valueUm10, settings.decimals)
            val device = measurementDeviceName(names, item.deviceMac)
            val channel = ChannelNamePolicy.nameOf(channelNames, item.channelNo)
            val highlighted = exportValueHighlighted(
                item.verdict, item.valueUm10, settings.lowerLimitUm10, settings.upperLimitUm10
            )
            csvRows += mapOf(
                "DATE" to date,
                "TIME" to time,
                "DEVICE_MAC" to device,
                "VALUE_MM" to value,
                "UPPER_TOL" to upper,
                "LOWER_TOL" to lower,
                "CHANNEL" to channel
            )
            sheetRows += SheetRow(
                listOf(
                    SheetCell(date),
                    SheetCell(time),
                    SheetCell(device),
                    SheetCell(value, numeric = true, highlighted = highlighted),
                    SheetCell(upper, numeric = upper.isNotEmpty()),
                    SheetCell(lower, numeric = lower.isNotEmpty()),
                    SheetCell(channel)
                )
            )
        }
        return ExportData(csvRows, sheetRows)
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("无法打开 SAF 输出流")
    }

    private fun formatDateTime(epochMs: Long): Pair<String, String> {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        return ldt.format(DATE_FMT) to ldt.format(TIME_FMT)
    }

    private fun parseDateTime(date: String, time: String): Long {
        val hhmmss = when {
            time.isBlank() -> "12:00:00"
            time.length == 5 -> "$time:00"
            else -> time
        }
        val ldt = LocalDateTime.parse("${date}T$hhmmss", DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
