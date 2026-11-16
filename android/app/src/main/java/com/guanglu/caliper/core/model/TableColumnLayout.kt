package com.guanglu.caliper.core.model

/** 检测记录表格「时间」列宽度权重。 */
internal const val TABLE_TIME_WEIGHT = 0.26f

/** 检测记录表格「设备名称」列宽度权重。 */
internal const val TABLE_DEVICE_WEIGHT = 0.34f

/** 检测记录表格「数值」列宽度权重。 */
internal const val TABLE_VALUE_WEIGHT = 0.40f

/** 三列权重之和为 1，避免行内容溢出表格框。 */
internal fun tableColumnsFit(): Boolean =
    (TABLE_TIME_WEIGHT + TABLE_DEVICE_WEIGHT + TABLE_VALUE_WEIGHT - 1f) in -0.001f..0.001f
