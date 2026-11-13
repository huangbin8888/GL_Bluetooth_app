package com.guanglu.caliper.core.usecase

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.guanglu.caliper.core.database.dao.MeasurementDao
import com.guanglu.caliper.core.database.entity.Measurement
import com.guanglu.caliper.core.datastore.SettingsRepository
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.Units
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

/**
 * 通过 SAF 完成 CSV 与 PNG 导入导出。
 *
 * @author AI-Generated
 */
class ImportExportUseCases(
    private val context: Context,
    private val dao: MeasurementDao,
    private val settings: SettingsRepository
) {
    suspend fun exportCsv(uri: Uri, channelNo: Int? = null): ExportReport = withContext(Dispatchers.IO) {
        val all = dao.all()
        val selected = if (channelNo == null) all else all.filter { it.channelNo == channelNo }
        val rows = selected.map { toRow(it) }
        val bytes = (CsvCodec.BOM + CsvCodec.export(rows)).toByteArray(Charsets.UTF_8)
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("无法打开 SAF 输出流")
        ExportReport(rows.size, bytes.size)
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
        val seen = HashSet<String>()
        val entities = mutableListOf<Measurement>()
        var duplicates = 0
        var parseErrors = parsed.parseErrors
        parsed.rows.forEach { row ->
            val valueUm = Units.toMicrometres(row["VALUE_MM"].orEmpty(), 'm')
            if (valueUm == null) {
                parseErrors++
                return@forEach
            }
            val capturedAt = try {
                parseDateTime(row["DATE"].orEmpty(), row["TIME"].orEmpty())
            } catch (e: DateTimeException) {
                parseErrors++
                return@forEach
            }
            val (channelNo, legacy) = CsvCodec.channelOf(
                row["CHANNEL"].orEmpty().ifBlank { "CH1" }
            )
            val key = "${row["DATE"]}|${row["TIME"]}|$channelNo|$valueUm"
            if (!seen.add(key)) {
                duplicates++
                return@forEach
            }
            entities += Measurement(
                batchId = settings.activeBatchId,
                seq = entities.size + 1,
                channelNo = channelNo,
                deviceMac = row["DEVICE_MAC"].orEmpty(),
                valueUm = valueUm,
                rawValue = row["RAW_VALUE"].orEmpty().ifBlank { row["VALUE_MM"].orEmpty() },
                rawUnit = row["RAW_UNIT"].orEmpty().ifBlank { "m" },
                sign = if (valueUm < 0) "-" else "+",
                capturedAt = capturedAt,
                elapsedRealtime = capturedAt,
                verdict = Units.judge(valueUm, settings.lowerLimitUm, settings.upperLimitUm).name,
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

    private fun toRow(item: Measurement): Map<String, String> {
        val (date, time) = formatDateTime(item.capturedAt)
        // 历史通道按 CH8–CH10 原值写回。
        val channel = "CH${item.channelNo}"
        return mapOf(
            "DATE" to date,
            "TIME" to time,
            "SEQ" to item.seq.toString(),
            "BATCH" to item.batchId.toString(),
            "ITEM" to "",
            "NOMINAL" to "",
            "LOWER_TOL" to "",
            "UPPER_TOL" to "",
            "VALUE_MM" to Units.formatMm(item.valueUm, settings.decimals),
            "RAW_VALUE" to item.rawValue,
            "RAW_UNIT" to item.rawUnit,
            "VERDICT" to item.verdict,
            "SOURCE" to item.source,
            "DEVICE_MAC" to item.deviceMac,
            "CHANNEL" to channel,
            "NOTE" to item.note
        )
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
