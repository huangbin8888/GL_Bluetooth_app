package com.guanglu.caliper.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 数值一律以 0.1 微米整数（1 单位 = 10^-4 mm）存储，4 位小数 mm 值无损不四舍五入（基线 A2 / 第 19 节）；
 * value_um 为历史列名，语义自 DB v3 起为 0.1µm。公差边界比较与统计不用浮点。
 * enum 以稳定大写字符串存储，R8 混淆后枚举名不变、旧数据可读。
 */
@Entity(
    tableName = "measurement",
    indices = [Index("batch_id"), Index("channel_no"), Index("captured_at")]
)
/**
 * Room 实体定义，数值以 0.1 微米整数存储。
 *
 * @author AI-Generated
 */
data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "batch_id") val batchId: Long,
    @ColumnInfo(name = "seq") val seq: Int,
    @ColumnInfo(name = "channel_no") val channelNo: Int,
    @ColumnInfo(name = "device_mac") val deviceMac: String,
    @ColumnInfo(name = "value_um") val valueUm10: Long,
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
    @ColumnInfo(name = "nominal_um") val nominalUm10: Long? = null,
    @ColumnInfo(name = "lower_limit_um") val lowerLimitUm10: Long? = null,
    @ColumnInfo(name = "upper_limit_um") val upperLimitUm10: Long? = null,
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
