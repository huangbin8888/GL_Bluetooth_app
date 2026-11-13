package com.guanglu.caliper.core.datastore

import android.content.Context

/**
 * SharedPreferences 设置项读写；数值类设置以 0.1 微米（10^-4 mm）为单位。
 *
 * @author AI-Generated
 */
class SettingsRepository(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("gl_caliper_settings", Context.MODE_PRIVATE)

    var decimals: Int
        get() = sp.getInt("decimals", 4)
        set(value) = sp.edit().putInt("decimals", value.coerceIn(0, 4)).apply()

    var activeBatchId: Long
        get() = sp.getLong("active_batch_id", 1L)
        set(value) = sp.edit().putLong("active_batch_id", value).apply()

    var lowerLimitUm10: Long?
        get() = sp.getLong("lower_limit_um10", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        set(value) = sp.edit().putLong("lower_limit_um10", value ?: Long.MIN_VALUE).apply()

    var upperLimitUm10: Long?
        get() = sp.getLong("upper_limit_um10", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        set(value) = sp.edit().putLong("upper_limit_um10", value ?: Long.MIN_VALUE).apply()

    var debounceDeltaUm10: Long
        get() = sp.getLong("debounce_delta_um10", 100L)
        set(value) = sp.edit().putLong("debounce_delta_um10", value).apply()

    var debounceWindowMs: Long
        get() = sp.getLong("debounce_window_ms", 300L)
        set(value) = sp.edit().putLong("debounce_window_ms", value).apply()

    /** 结束符登记值，用于 HID 键盘录入切分。 */
    var terminator: String
        get() = sp.getString("terminator", "CR") ?: "CR"
        set(value) = sp.edit().putString("terminator", if (value == "TAB") "TAB" else "CR").apply()

    var namePrefix: String
        get() = sp.getString("name_prefix", "GL-BLE#") ?: "GL-BLE#"
        set(value) = sp.edit().putString("name_prefix", value).apply()

    var lastMac: String?
        get() = sp.getString("last_mac", null)
        set(value) = sp.edit().putString("last_mac", value).apply()

    /** 启动时自动连接上次连接过的卡尺。 */
    var autoConnect: Boolean
        get() = sp.getBoolean("auto_connect", true)
        set(value) = sp.edit().putBoolean("auto_connect", value).apply()

    /** 新采集数据判定超差时播放蜂鸣。 */
    var overLimitBeep: Boolean
        get() = sp.getBoolean("over_limit_beep", true)
        set(value) = sp.edit().putBoolean("over_limit_beep", value).apply()

    /** 空闲自动断连分钟数；0 表示不断开。 */
    var idleDisconnectMinutes: Int
        get() = sp.getInt("idle_disconnect_minutes", 0)
        set(value) = sp.edit().putInt("idle_disconnect_minutes", value.coerceAtLeast(0)).apply()

    /** 0=全部按时间混排；-1=只看历史通道；1..7=单通道。 */
    var channelFilter: Int
        get() = sp.getInt("channel_filter", 0)
        set(value) = sp.edit().putInt("channel_filter", value).apply()
}
