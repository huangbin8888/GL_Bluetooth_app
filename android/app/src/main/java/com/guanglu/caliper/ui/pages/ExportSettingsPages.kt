package com.guanglu.caliper.ui.pages

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.guanglu.caliper.AppContainer
import com.guanglu.caliper.core.model.ChannelPalette
import com.guanglu.caliper.core.model.ExportAction
import com.guanglu.caliper.core.model.Units
import com.guanglu.caliper.core.model.exportAction
import com.guanglu.caliper.core.model.exportButtonState
import com.guanglu.caliper.core.model.parseDecimals
import com.guanglu.caliper.ui.*

/**
 * 导出页与设置页：勾选状态真实驱动导出动作，设置保存与恢复默认均落到实处。
 *
 * @author AI-Generated
 */
internal fun exportPage(context: Context, c: AppContainer, actions: UiActions): View = context.run {
    val page = screen(context)
    val col = page.col()
    val contentCard = webCard()
    contentCard.addView(webCardTitle("导出内容"))
    var csvChecked = true
    var pngChecked = false
    var refreshExport: () -> Unit = {}
    contentCard.addView(webCheck("图表 PNG", "勾选后提示到数据分析页导出图片", pngChecked) { on ->
        pngChecked = on
        refreshExport()
    })
    contentCard.addView(webCheck("数据 CSV", "取消勾选后不会导出 CSV", csvChecked) { on ->
        csvChecked = on
        refreshExport()
    })
    col.addView(contentCard)

    val rangeCard = webCard()
    rangeCard.addView(webCardTitle("多通道导出范围（新增）"))
    var split = false
    var setMerge: (Boolean) -> Unit = {}
    var setSplit: (Boolean) -> Unit = {}
    val (mergeRow, mergeSetter) = webCheckPair(
        this, "合并为一个 CSV", "含 CHANNEL 通道列，7 路按时间混排", true
    ) { on ->
        if (on) {
            split = false
            setSplit(false)
        }
        refreshExport()
    }
    setMerge = mergeSetter
    val (splitRow, splitSetter) = webCheckPair(
        this, "按通道分文件", "每个在线通道一个 CSV，文件名带 _CHn", false
    ) { on ->
        split = on
        if (on) setMerge(false)
        refreshExport()
    }
    setSplit = splitSetter
    rangeCard.addView(mergeRow)
    rangeCard.addView(splitRow)
    col.addView(rangeCard)

    col.addView(webSection("文件名预览"))
    col.addView(webFileLine("B20261112-01_ALL.csv"))
    col.addView(webFileLine("B20261112-01_ALL.png"))
    col.addView(webBanner(
        "保存到手机「下载 / Documents」，走系统文件选择器 SAF；不申请宽泛存储权限。\n" +
            "CSV 为 UTF-8 BOM 中文 7 列，可重新导入；同名文件不覆盖。\n" +
            "CSV 是纯文本、不含颜色；超差数值标红请点「导出彩色表格」，用表格软件打开 xlsx。",
        WebTone.BLUE
    ))
    val hintLine = webTiny("")
    col.addView(hintLine)
    val saveButton = webButton("导出 CSV", primary = true) {
        when (exportAction(csvChecked, pngChecked, split)) {
            ExportAction.EXPORT_ALL_CSV -> actions.exportCsv(null)
            ExportAction.CSV_AND_PNG_GUIDE -> {
                actions.exportCsv(null)
                actions.toast("图表 PNG 请到「数据分析」页点『导出这张图』")
            }
            ExportAction.PNG_GUIDE ->
                actions.toast("未勾选「数据 CSV」；图表 PNG 请到「数据分析」页点『导出这张图』")
            ExportAction.CHANNEL_FILES -> actions.toast("请在下方按通道分别导出 CSV")
            ExportAction.NO_CONTENT -> actions.toast("未勾选任何导出内容，请至少勾选「数据 CSV」")
        }
    }
    col.addView(saveButton)
    col.addView(webBtnRow(webButton("导出彩色表格（超差标红）", small = true) {
        actions.exportColoredSheet()
    }))
    refreshExport = {
        val state = exportButtonState(csvChecked, pngChecked, split)
        saveButton.text = state.label
        hintLine.text = state.hint
    }
    refreshExport()
    for (i in 1..ChannelPalette.MAX_CHANNELS step 2) {
        val first = webButton("只导 CH$i CSV", small = true) { actions.exportCsv(i) }
        if (i + 1 <= ChannelPalette.MAX_CHANNELS) {
            col.addView(webBtnRow(
                first,
                webButton("只导 CH${i + 1} CSV", small = true) { actions.exportCsv(i + 1) }
            ))
        } else {
            col.addView(webBtnRow(first))
        }
    }
    col.addView(webBtnRow(
        webButton("导入 CSV") { actions.importCsv() },
        webButton("完成") { actions.setPage("P2") }
    ))
    page
}

private fun settingsMeasureCard(
    context: Context,
    c: AppContainer,
    actions: UiActions,
    registerSave: ((Boolean) -> Boolean) -> Unit
): LinearLayout = context.run {
    val card = webCard()
    card.addView(webCardTitle("测量与判定"))
    val decimals = context.webEdit(c.settings.decimals.toString())
    val lower = context.webEdit(c.settings.lowerLimitUm10?.let { Units.formatMm(it) } ?: "")
    val upper = context.webEdit(c.settings.upperLimitUm10?.let { Units.formatMm(it) } ?: "")
    card.addView(webField("小数位数（0–4）", "显示与导出统一，默认 4 位不四舍五入", decimals))
    card.addView(webField("公差下限 (mm)", "设置上下公差后才会判定超差；超差数值变红并三连蜂鸣 + 振动，未设为「未判定」", lower))
    card.addView(webField("公差上限 (mm)", "", upper))
    val save: (Boolean) -> Boolean = { notify ->
        val parsed = parseDecimals(decimals.text.toString())
        if (parsed == null) {
            decimals.setText(c.settings.decimals.toString())
            if (notify) actions.toast("小数位数须为 0–4 的整数，测量设置未保存")
            false
        } else {
            c.settings.decimals = parsed
            c.settings.lowerLimitUm10 = Units.toUm10(lower.text.toString(), 'm')
            c.settings.upperLimitUm10 = Units.toUm10(upper.text.toString(), 'm')
            if (notify) actions.toast("测量设置已保存")
            true
        }
    }
    registerSave(save)
    card.addView(webButton("保存测量设置", small = true) { save(true) })
    card
}

internal fun settingsPage(context: Context, c: AppContainer, actions: UiActions): View = context.run {
    val page = screen(context)
    val col = page.col()
    val saveForms = mutableListOf<(Boolean) -> Boolean>()
    col.addView(settingsMeasureCard(context, c, actions) { save -> saveForms.add(save) })

    val multi = webCard()
    multi.addView(webCardTitle("多通道（新增）"))
    multi.addView(webField("最大并发通道数", "上限 7 台，第 8 台拒绝连接", webBold("7（只读）", 12.5f)))
    multi.addView(webField("通道色标", "CH1–CH7 固定配色，全 App 一致", webChip("固定 7 色", WebTone.BLUE)))
    multi.addView(webField("通道命名 / 改色", "V2", webChip("V2")))
    col.addView(multi)

    col.addView(settingsBluetoothCard(context, c, actions))

    val behavior = webCard()
    behavior.addView(webCardTitle("采集行为"))
    behavior.addView(webField(
        "采集页屏幕常亮",
        "App 全局保持屏幕常亮，避免息屏断连丢数；不可单独关闭",
        webChip("全局常亮（已开启）", WebTone.OK)
    ))
    val autoSwitch = webSwitch(c.settings.autoConnect)
    autoSwitch.setOnCheckedChangeListener { _, checked ->
        c.settings.autoConnect = checked
        actions.toast(if (checked) "已开启自动重连：启动或蓝牙恢复时自动补连" else "已关闭自动重连")
    }
    behavior.addView(webField("自动重连上次设备", "启动、蓝牙恢复或重新广播时自动连接已登记的卡尺", autoSwitch))
    behavior.addView(overLimitBeepField(context, c, actions))
    val autoDisconnect = webSwitch(c.settings.autoDisconnectEnabled)
    val idleMinutes = context.webEdit(c.settings.idleDisconnectMinutes.toString())
    idleMinutes.isEnabled = c.settings.autoDisconnectEnabled
    autoDisconnect.setOnCheckedChangeListener { _, checked ->
        c.settings.autoDisconnectEnabled = checked
        idleMinutes.isEnabled = checked
        actions.toast(
            if (checked) "已开启空闲自动断连：按下方分钟数断开，分钟数为 0 仍不会断开"
            else "已关闭空闲自动断连（默认），App 不会主动断开卡尺"
        )
    }
    behavior.addView(webField(
        "蓝牙空闲自动断连",
        "默认关闭；开启后无数据超过设定分钟数才由 App 断开，不下发卡尺参数",
        autoDisconnect
    ))
    behavior.addView(webField(
        "空闲自动断连（分钟）", "0 = 不断开；超时无数据由 App 断开通道，不下发设备参数", idleMinutes
    ))
    val saveIdle: (Boolean) -> Boolean = { notify ->
        val minutes = idleMinutes.text.toString().toIntOrNull()?.takeIf { it >= 0 }
        if (minutes == null) {
            idleMinutes.setText(c.settings.idleDisconnectMinutes.toString())
            if (notify) actions.toast("空闲分钟数须为 ≥0 的整数，断连设置未保存")
            false
        } else {
            c.settings.idleDisconnectMinutes = minutes
            if (notify) {
                actions.toast(
                    when {
                        !c.settings.autoDisconnectEnabled -> "空闲自动断连保持关闭，默认不自动断开"
                        minutes == 0 -> "开关已开启但分钟数为 0，实际不会断开"
                        else -> "无数据 $minutes 分钟后自动断开通道"
                    }
                )
            }
            true
        }
    }
    saveForms.add(saveIdle)
    behavior.addView(webButton("保存断连设置", small = true) { saveIdle(true) })
    col.addView(behavior)

    val maintenance = webCard(true)
    maintenance.addView(webCardTitle("维护入口"))
    maintenance.addView(webBtnRow(
        webButton("导出诊断日志", small = true) { actions.exportDiagnostics() },
        webButton("权限引导页", small = true) { actions.openSystemSettings() }
    ))
    col.addView(maintenance)

    col.addView(webBtnRow(
        webButton("保存", primary = true) {
            val failed = saveForms.count { !it(false) }
            actions.toast(
                if (failed == 0) "已保存本页全部设置"
                else "有 $failed 项输入不合法，已还原为原值，请修改后重试"
            )
        },
        webButton("恢复默认") {
            c.settings.decimals = 4
            c.settings.lowerLimitUm10 = null
            c.settings.upperLimitUm10 = null
            c.settings.terminator = "CR"
            c.settings.autoConnect = true
            c.settings.autoDisconnectEnabled = false
            c.settings.idleDisconnectMinutes = 0
            c.settings.overLimitBeep = true
            actions.toast("已恢复默认设置")
            actions.setPage("P7")
        }
    ))
    col.addView(webTiny("v0.3.0 · 原生 App · 数值以 0.1 微米存储 · 升级前请先导出 CSV 备份").apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, 0)
    })
    page
}
