package com.guanglu.caliper.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 数值一律以整数微米（0.001mm）存储；公差边界比较与统计不用浮点（基线 A2 / 第 6 节）。
 * enum 以稳定大写字符串存储，避免 R8 混淆后枚举名变化导致旧数据读不出。
 */
@Entity(
    tableName = "measurement",
    indices = [Index("batch_id"), Index("channel_no"), Index("captured_at")]
)
/**
 * Room 实体定义，数值以整数微米存储。
 *
 * @author AI-Generated
 */
data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "batch_id") val batchId: Long,
    @ColumnInfo(name = "seq") val seq: Int,
    @ColumnInfo(name = "channel_no") val channelNo: Int,
    @ColumnInfo(name = "device_mac") val deviceMac: String,
    @ColumnInfo(name = "value_um") val valueUm: Long,
    @ColumnInfo(name = "raw_value") val rawValue: String,
    @ColumnInfo(name = "raw_unit") val rawUnit: String,
    @ColumnInfo(name = "sign") val sign: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "elapsed_realtime") val elapsedRealtime: Long,
    @ColumnInfo(name = "verdict") val verdict: String,
    @ColumnInfo(name = "note") val note: String = "",
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "legacy_channel") val legacyChannel: Boolean = false
)

@Entity(tableName = "batch")
data class Batch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "template_id") val templateId: Long? = null,
    @ColumnInfo(name = "unit") val unit: String = "mm",
    @ColumnInfo(name = "nominal_um") val nominalUm: Long? = null,
    @ColumnInfo(name = "lower_limit_um") val lowerLimitUm: Long? = null,
    @ColumnInfo(name = "upper_limit_um") val upperLimitUm: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "template")
data class Template(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    /** builtin / csv；xlsx 保留枚举值但 V1 不实现。 */
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "columns_json") val columnsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "device")
data class Device(
    @PrimaryKey @ColumnInfo(name = "mac") val mac: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "last_connected_at") val lastConnectedAt: Long = 0,
    @ColumnInfo(name = "is_last_used") val isLastUsed: Boolean = false,
    @ColumnInfo(name = "channel_no") val channelNo: Int? = null,
    // 自动发射时长 / 休眠 / 结束符按 MAC 登记
    @ColumnInfo(name = "custom_name") val customName: String? = null,
    @ColumnInfo(name = "auto_emit_seconds") val autoEmitSeconds: Int? = null,
    @ColumnInfo(name = "sleep_enabled") val sleepEnabled: Boolean = false,
    @ColumnInfo(name = "sleep_minutes") val sleepMinutes: Int? = null,
    @ColumnInfo(name = "terminator") val terminator: String = "CR"
)

@Entity(tableName = "app_meta")
data class AppMeta(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "app_version") val appVersion: String
)
