package com.guanglu.caliper.core.model

import kotlin.math.roundToLong

/** 空闲断连轮询间隔（毫秒）；分钟级判定无需毫秒级主线程轮询。 */
internal const val IDLE_POLL_INTERVAL_MS = 1_000L

/** 统计卡片空数据时的占位符。 */
internal const val STAT_PLACEHOLDER = "—"

/**
 * 文件选择器返回后的动作：给出提示文案，null 表示结果有效可继续处理。
 *
 * @author AI-Generated
 */
internal fun safResultMessage(resultOk: Boolean, hasUri: Boolean, label: String): String? =
    when {
        !resultOk -> "已取消$label"
        !hasUri -> "$label 失败：未取得文件位置，请重试"
        else -> null
    }

/** 批量连接的结果提示，区分全部拒绝、部分受理与全部受理。 */
internal fun batchConnectMessage(total: Int, accepted: Int): String =
    when {
        total <= 0 -> "请先点『扫描设备』并等待列表出现卡尺"
        accepted <= 0 -> "0/$total 台被受理：通道已满或已在连接队列，未新增连接"
        accepted < total -> "已受理 $accepted/$total 台，其余通道已满或已在连接队列"
        else -> "正在连接 $accepted 台卡尺"
    }

/**
 * 命令下发后的即时反馈文案；null 表示该分支已有专用提示，避免重复弹窗。
 *
 * @author AI-Generated
 */
internal fun commandFeedback(
    channelNo: Int,
    label: String,
    sent: Boolean,
    notifySent: Boolean = true
): String? = when {
    channelNo == 0 -> "当前无在线通道，请先到「连接」页连接卡尺"
    !sent -> "CH$channelNo 未连接卡尺，$label 未下发（只影响该通道）"
    !notifySent -> null
    else -> "$label 已下发到 CH$channelNo，等待卡尺回数"
}

/** 分段控件的选择；超过已实现上限时保持原段，避免视觉切换但功能未切换。 */
internal fun acceptedSegmentIndex(current: Int, picked: Int, maxImplemented: Int = 0): Int =
    if (picked in 0..maxImplemented) picked else current

/** 解析小数位数，非数字或超出 0–4 返回 null。 */
internal fun parseDecimals(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..4 }

/**
 * 导出页按勾选内容应执行的动作。
 *
 * @author AI-Generated
 */
internal enum class ExportAction {
    EXPORT_ALL_CSV,
    CHANNEL_FILES,
    NO_CONTENT,
    PNG_GUIDE,
    CSV_AND_PNG_GUIDE
}

/** 勾选「按通道分文件」优先；未选任何内容必须明确提示而不是静默导出。 */
internal fun exportAction(csv: Boolean, png: Boolean, split: Boolean): ExportAction =
    when {
        split -> ExportAction.CHANNEL_FILES
        !csv && !png -> ExportAction.NO_CONTENT
        csv && png -> ExportAction.CSV_AND_PNG_GUIDE
        csv -> ExportAction.EXPORT_ALL_CSV
        else -> ExportAction.PNG_GUIDE
    }

/**
 * 导出按钮的文案与状态说明；按钮名称随勾选状态与实际动作保持一致。
 *
 * @author AI-Generated
 */
internal data class ExportButtonState(val label: String, val hint: String)

/** 不产出文件的分支不得叫「保存」，避免点击后只弹说明却没生成文件。 */
internal fun exportButtonState(csv: Boolean, png: Boolean, split: Boolean): ExportButtonState =
    when (exportAction(csv, png, split)) {
        ExportAction.EXPORT_ALL_CSV -> ExportButtonState(
            "导出 CSV", "将打开系统文件选择器，保存全部通道的测量 CSV"
        )
        ExportAction.CSV_AND_PNG_GUIDE -> ExportButtonState(
            "导出 CSV", "先保存 CSV；图表 PNG 请到「数据分析」页点『导出这张图』"
        )
        ExportAction.PNG_GUIDE -> ExportButtonState(
            "查看 PNG 导出说明", "只勾选了 PNG；V1 在本页不导出图片，点击可看到去向"
        )
        ExportAction.CHANNEL_FILES -> ExportButtonState(
            "查看分通道导出说明", "已选「按通道分文件」，请用下方「只导 CHx CSV」分别保存"
        )
        ExportAction.NO_CONTENT -> ExportButtonState(
            "请选择导出内容", "当前未勾选任何内容；至少勾选「数据 CSV」才会生成文件"
        )
    }

/** 系统缺少处理该意图的应用时的提示，避免文件选择器缺失时点击无反应。 */
internal fun activityLaunchFailureMessage(label: String): String =
    "无法打开「$label」：此设备缺少对应的系统应用（文件选择器或设置页），操作未完成"

/**
 * 统计卡片文本：空数据时三格必须复位，避免清除数据后残留旧值。
 *
 * @author AI-Generated
 */
internal data class StatTexts(val average: String, val minimum: String, val maximum: String)

/** 平均值按 0.1µm 粒度四舍五入，显示小数位数跟随设置。 */
internal fun statisticsTexts(values: List<Long>, decimals: Int): StatTexts =
    if (values.isEmpty()) {
        StatTexts(STAT_PLACEHOLDER, STAT_PLACEHOLDER, STAT_PLACEHOLDER)
    } else {
        StatTexts(
            Units.formatMm(values.average().roundToLong(), decimals),
            Units.formatMm(values.minOrNull() ?: 0L, decimals),
            Units.formatMm(values.maxOrNull() ?: 0L, decimals)
        )
    }
