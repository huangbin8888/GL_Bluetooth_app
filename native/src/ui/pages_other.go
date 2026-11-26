//go:build windows

package ui

import (
	"fmt"

	"github.com/lxn/walk"
	. "github.com/lxn/walk/declarative"
	"glcaliper/protocol"
)

// pageConnect: BLE scan/connect + HID guidance + environment info.
func (u *UI) pageConnect() TabPage {
	return TabPage{
		Title:  "连接卡尺",
		Layout: VBox{Margins: Margins{12, 12, 12, 12}, Spacing: 10},
		Children: []Widget{
			Composite{
				Layout:     VBox{Margins: Margins{12, 10, 12, 10}, Spacing: 6},
				Background: SolidColorBrush{Color: colorPrimarySoft},
				Children: []Widget{
					Label{Text: "链路模式", Font: Font{PointSize: 10, Bold: true}, TextColor: colorSub},
					Label{AssignTo: &u.lblEnvDetail, Text: "检测中…", Font: Font{PointSize: 10}},
				},
			},
			GroupBox{
				Title:  "BLE 直连（Win10+，需蓝牙 LE）",
				Layout: VBox{Margins: Margins{10, 8, 10, 8}, Spacing: 8},
				Children: []Widget{
					Composite{
						Layout: HBox{Spacing: 8},
						Children: []Widget{
							PushButton{AssignTo: &u.btnScan, Text: "扫描设备", OnClicked: u.onScan, MinSize: Size{130, 32}},
							PushButton{AssignTo: &u.btnConnect, Text: "连接选中", OnClicked: u.onConnect, MinSize: Size{130, 32}},
							PushButton{AssignTo: &u.btnDisconnect, Text: "断开", OnClicked: u.onDisconnect},
						},
					},
					ListBox{AssignTo: &u.lstDevices, Model: u.deviceList,
						MinSize: Size{0, 150}},
					Label{AssignTo: &u.lblDevices, Text: "长按卡尺 data 键 3 秒至闪 OK，再扫描；选中后点“连接选中”。", TextColor: colorSub},
				},
			},
			GroupBox{
				Title:  "HID 键盘捕获（Win7/Win10/11 通用，推荐）",
				Layout: VBox{Margins: Margins{10, 8, 10, 8}, Spacing: 6},
				Children: []Widget{
					Label{Text: "把卡尺通过蓝牙配对为“键盘”设备，到“实时检测”页开启全局捕获，任意界面下读数都会自动录入——这是网页/Electron 做不到的原生能力。"},
					PushButton{Text: "前往实时检测页", OnClicked: func() {
						u.tabWidget.SetCurrentIndex(0)
					}},
				},
			},
		},
	}
}

func (u *UI) onScan() {
	if u.bridge == nil {
		return
	}
	if !u.bridge.BLEAvailable() {
		u.lblDevices.SetText("此系统不支持 BLE（Win7 无 WinRT 蓝牙 LE）。请改用 HID 键盘捕获模式——功能完全一致且可实测。")
		u.setStatus("BLE 不可用，请改用 HID 模式")
		return
	}
	u.btnScan.SetEnabled(false)
	u.setStatus("正在扫描 GL-BLE# 设备…")
	go func() {
		devs, err := u.bridge.BLEScan()
		u.main.Synchronize(func() {
			u.btnScan.SetEnabled(true)
			if err != nil {
				u.lblDevices.SetText("扫描失败：" + err.Error())
				u.setStatus("扫描失败")
				return
			}
			u.deviceList = make([]string, len(devs))
			u.deviceAddrs = make([]string, len(devs))
			u.deviceNames = make([]string, len(devs))
			for i, d := range devs {
				u.deviceList[i] = fmt.Sprintf("%s  (%s)  %d dBm", d.Name, shortAddr(d.Address), d.RSSI)
				u.deviceAddrs[i] = d.Address
				u.deviceNames[i] = d.Name // GL-BLE#XXX, injected into records on connect
			}
			u.lstDevices.SetModel(u.deviceList)
			if len(devs) == 0 {
				u.lblDevices.SetText("未发现 GL-BLE# 设备。请确认卡尺在广播（长按 data 键）。")
				u.setStatus("未发现设备")
			} else {
				u.lblDevices.SetText(fmt.Sprintf("发现 %d 台设备，双击或点“连接选中”", len(devs)))
				u.setStatus(fmt.Sprintf("发现 %d 台设备", len(devs)))
			}
		})
	}()
}

func (u *UI) onConnect() {
	if u.bridge == nil || u.lstDevices == nil {
		return
	}
	idx := u.lstDevices.CurrentIndex()
	if idx < 0 || idx >= len(u.deviceAddrs) {
		info(u.main, "连接", "请先扫描并选中一台设备")
		return
	}
	addr := u.deviceAddrs[idx]
	name := ""
	if idx < len(u.deviceNames) {
		name = u.deviceNames[idx] // GL-BLE#XXX advertising name
	}
	u.setStatus("正在连接 " + addr + " …")
	go func() {
		err := u.bridge.BLEConnect(addr)
		u.main.Synchronize(func() {
			if err != nil {
				errBox(u.main, "连接失败", err.Error())
				u.setStatus("连接失败")
				return
			}
			// Stamp the Bluetooth name for the live table's "设备名称" column.
			// core also receives the address via the bridge's NoteConnected.
			u.app.SetDeviceName(name)
			u.setStatus("已连接 " + addr)
		})
	}()
}

func (u *UI) onDisconnect() {
	if u.bridge == nil {
		return
	}
	u.bridge.BLEDisconnect()
	u.setStatus("已断开 BLE")
}

// pageAnalysis: statistics (7 items kept in place) + self-drawn chart.
func (u *UI) pageAnalysis() TabPage {
	return TabPage{
		Title:  "数据分析",
		Layout: VBox{Margins: Margins{14, 14, 14, 14}, Spacing: 10},
		Children: []Widget{
			Label{Text: "实时统计（基于当前公差）", Font: Font{PointSize: 12, Bold: true}},
			Composite{
				Layout:     Grid{Margins: Margins{14, 12, 14, 12}, Spacing: 10, Columns: 2},
				Background: SolidColorBrush{Color: colorWhite},
				Children: []Widget{
					Label{Text: "总数", TextColor: colorSub}, Label{AssignTo: &u.anTotal, Text: "0", Font: Font{PointSize: 11, Bold: true}},
					Label{Text: "合格数", TextColor: colorSub}, Label{AssignTo: &u.anOK, Text: "0", Font: Font{PointSize: 11, Bold: true}},
					Label{Text: "合格率", TextColor: colorSub}, Label{AssignTo: &u.anRate, Text: "0%", Font: Font{PointSize: 11, Bold: true}},
					Label{Text: "均值", TextColor: colorSub}, Label{AssignTo: &u.anMean, Text: "—", Font: Font{PointSize: 11, Bold: true}},
					Label{Text: "最大值", TextColor: colorSub}, Label{AssignTo: &u.anMax, Text: "—", Font: Font{PointSize: 11, Bold: true}},
					Label{Text: "最小值", TextColor: colorSub}, Label{AssignTo: &u.anMin, Text: "—", Font: Font{PointSize: 11, Bold: true}},
					Label{Text: "标准差", TextColor: colorSub}, Label{AssignTo: &u.anSD, Text: "—", Font: Font{PointSize: 11, Bold: true}},
				},
			},
			// ---- chart toolbar (new controls only, nothing removed) ----
			Composite{
				Layout: HBox{Spacing: 8},
				Children: []Widget{
					Label{Text: "趋势图", Font: Font{PointSize: 11, Bold: true}},
					PushButton{AssignTo: &u.btnChartKind, Text: "切换为柱状图", OnClicked: u.onChartKindToggle, MinSize: Size{120, 28}},
					Label{Text: "通道", TextColor: colorSub},
					ComboBox{AssignTo: &u.cmbChartFilter, Editable: false, Value: int(0), Model: filterDisplayNames(u.app.GetSettings()),
						OnCurrentIndexChanged: u.onChartFilterChanged},
					HSpacer{},
					PushButton{Text: "导出 PNG…", OnClicked: u.onExportChartPNG, MinSize: Size{110, 28}},
				},
			},
			Composite{
				Layout:        VBox{MarginsZero: true},
				Background:    SolidColorBrush{Color: colorWhite},
				MinSize:       Size{0, 240},
				StretchFactor: 1,
				Children: []Widget{
					CustomWidget{
						AssignTo:            &u.chartWidget,
						PaintMode:           PaintBuffered,
						InvalidatesOnResize: true,
						MinSize:             Size{0, 240},
						StretchFactor:       1,
						// walk's PaintFunc receives updateBounds in 96-DPI units,
						// relative to this widget's client area. It is the correct
						// drawing extent. Never use canvas.Bounds() here: in
						// PaintBuffered mode the canvas is an in-memory bitmap and
						// canvas.Bounds() reports the full device resolution
						// (e.g. 1920x1080), so the chart would be laid out far
						// larger than the widget and its bottom (time axis, bar
						// bases) would be clipped by the widget edge.
						Paint: func(canvas *walk.Canvas, updateBounds walk.Rectangle) error {
							return u.paintChart(canvas, updateBounds)
						},
					},
				},
			},
		},
	}
}

func (u *UI) onExportChartPNG() {
	if err := u.exportChartPNG(); err != nil {
		errBox(u.main, "图表导出失败", err.Error())
	}
}

// pageExport: CSV export with scope selection.
func (u *UI) pageExport() TabPage {
	return TabPage{
		Title:  "导出到本地",
		Layout: VBox{Margins: Margins{14, 14, 14, 14}, Spacing: 12},
		Children: []Widget{
			Label{Text: "导出当前记录", Font: Font{PointSize: 12, Bold: true}},
			Label{Text: "中文 8 列：日期 / 时间 / 设备名称 / 测量值 / 公差上限 / 公差下限 / 通道名称 / 是否合格", TextColor: colorSub},
			Composite{
				Layout: HBox{Spacing: 10},
				Children: []Widget{
					Label{Text: "范围", TextColor: colorSub},
					ComboBox{AssignTo: &u.exportScope, Editable: false, Value: int(0), Model: filterDisplayNames(u.app.GetSettings())},
				},
			},
			Composite{
				Layout: HBox{Spacing: 10},
				Children: []Widget{
					PushButton{Text: "导出 CSV…", OnClicked: u.onExportCsv, MinSize: Size{150, 32}},
					PushButton{Text: "导出彩色 Excel (.xls)…", OnClicked: u.onExportXls, MinSize: Size{190, 32}},
				},
			},
			Label{Text: "CSV 为纯文本（无颜色）；彩色 Excel 中：合格绿色、超差红色，列顺序与 CSV 完全一致。", TextColor: colorSub},
			Label{AssignTo: &u.exportResult, Text: "", TextColor: colorSub},
			VSpacer{},
		},
	}
}

// exportScopeValue returns "all" or "CHn" from the current combo selection.
func (u *UI) exportScopeValue() string {
	if u.exportScope != nil && u.exportScope.CurrentIndex() > 0 {
		return fmt.Sprintf("CH%d", u.exportScope.CurrentIndex())
	}
	return "all"
}

func (u *UI) onExportCsv() {
	scope := u.exportScopeValue()
	dlg := walk.FileDialog{
		Title:    "导出 CSV",
		Filter:   "CSV 文件 (*.csv)|*.csv",
		FilePath: protocolFileName("csv"),
	}
	if ok, err := dlg.ShowSave(u.main); err != nil || !ok {
		return
	}
	n, err := u.app.ExportCsv(dlg.FilePath, scope)
	if err != nil {
		errBox(u.main, "导出失败", err.Error())
		return
	}
	u.exportResult.SetText(fmt.Sprintf("已导出 %d 条到：\n%s", n, dlg.FilePath))
	u.setStatus(fmt.Sprintf("已导出 %d 条 CSV", n))
}

func (u *UI) onExportXls() {
	scope := u.exportScopeValue()
	dlg := walk.FileDialog{
		Title:    "导出彩色 Excel",
		Filter:   "Excel 文件 (*.xls)|*.xls",
		FilePath: protocolFileName("xls"),
	}
	if ok, err := dlg.ShowSave(u.main); err != nil || !ok {
		return
	}
	n, err := u.app.ExportXls(dlg.FilePath, scope)
	if err != nil {
		errBox(u.main, "导出失败", err.Error())
		return
	}
	u.exportResult.SetText(fmt.Sprintf("已导出 %d 条到：\n%s", n, dlg.FilePath))
	u.setStatus(fmt.Sprintf("已导出 %d 条彩色 Excel", n))
}

// channelSettingWidgets builds the 7-row channel name/tolerance grid.
func (u *UI) channelSettingWidgets() []Widget {
	widgets := []Widget{
		Label{Text: "通道", Font: Font{Bold: true}},
		Label{Text: "自定义名称（不可重名）", Font: Font{Bold: true}},
		Label{Text: "蓝牙名称（仅表格显示）", Font: Font{Bold: true}},
		Label{Text: "启用独立公差", Font: Font{Bold: true}},
		Label{Text: "下限 (mm)", Font: Font{Bold: true}},
		Label{Text: "上限 (mm)", Font: Font{Bold: true}},
		Label{Text: "HID 设备（已绑定指纹）", Font: Font{Bold: true}},
	}
	for i := 0; i < protocol.MaxChannels; i++ {
		widgets = append(widgets,
			Label{Text: fmt.Sprintf("CH%d", i+1)},
			LineEdit{AssignTo: &u.edChName[i], MaxSize: Size{100, 0}},
			LineEdit{AssignTo: &u.edChBleName[i], MaxSize: Size{120, 0}},
			CheckBox{AssignTo: &u.ckChUseTol[i], Text: ""},
			LineEdit{AssignTo: &u.edChLower[i], MaxSize: Size{72, 0}},
			LineEdit{AssignTo: &u.edChUpper[i], MaxSize: Size{72, 0}},
			Label{AssignTo: &u.lblChHidFp[i], Text: "—", TextColor: colorSub},
		)
	}
	return widgets
}

// pageSettings: tolerance / debounce / channel / unit + new option groups.
func (u *UI) pageSettings() TabPage {
	return TabPage{
		Title:  "设置",
		Layout: VBox{MarginsZero: true, SpacingZero: true},
		Children: []Widget{
			// The settings form is taller than the tab viewport on small
			// windows; wrap it in a ScrollView so every group (incl. MES and
			// the save buttons) stays reachable via scrollbar / mouse wheel.
			ScrollView{
				StretchFactor: 1,
				Layout:        VBox{Margins: Margins{14, 14, 14, 14}, Spacing: 10},
				Children: []Widget{
					GroupBox{
						Title:  "判定公差（全局，未启用独立公差时使用）",
						Layout: Grid{Margins: Margins{12, 10, 12, 10}, Spacing: 8, Columns: 2},
						Children: []Widget{
							Label{Text: "公称值 (mm)"}, LineEdit{AssignTo: &u.edNominal},
							Label{Text: "下偏差 (mm)"}, LineEdit{AssignTo: &u.edLower},
							Label{Text: "上偏差 (mm)"}, LineEdit{AssignTo: &u.edUpper},
						},
					},
					GroupBox{
						Title:    "通道名称与独立公差（绝对 mm）· 共 20 通道，HID 设备绑定后自动归档",
						Layout:   Grid{Margins: Margins{12, 10, 12, 10}, Spacing: 6, Columns: 7},
						Children: u.channelSettingWidgets(),
					},
					GroupBox{
						Title:  "采集与显示",
						Layout: Grid{Margins: Margins{12, 10, 12, 10}, Spacing: 8, Columns: 2},
						Children: []Widget{
							Label{Text: "显示单位"}, ComboBox{AssignTo: &u.cmbUnit, Editable: false, Value: int(0), Model: []string{"mm", "inch"}},
							Label{Text: "小数位（inch 固定 4 位）"}, LineEdit{AssignTo: &u.edDigits},
							Label{Text: "去抖阈值 (µm，0=关闭去抖)"}, LineEdit{AssignTo: &u.edDebounce},
							Label{Text: "HID 终止符"}, ComboBox{AssignTo: &u.cmbTerm, Editable: false, Value: int(0), Model: []string{"ENTER（回车）", "TAB（制表）"}},
							Label{Text: "批次号（留空保留原值）"}, LineEdit{AssignTo: &u.edBatch},
						},
					},
					GroupBox{
						Title:  "提醒与链路",
						Layout: Grid{Margins: Margins{12, 10, 12, 10}, Spacing: 8, Columns: 2},
						Children: []Widget{
							Label{Text: "超差蜂鸣与界面提醒（演示模式跳过）"}, CheckBox{AssignTo: &u.ckAlertOnBad, Text: ""},
							Label{Text: "启动时自动重连上次设备（仅 BLE）"}, CheckBox{AssignTo: &u.ckAutoReconnect, Text: ""},
							Label{Text: "空闲自动断开 BLE"}, CheckBox{AssignTo: &u.ckIdleDisconnect, Text: ""},
							Label{Text: "空闲分钟数（≤0 回退默认 5）"}, LineEdit{AssignTo: &u.edIdleMinutes},
						},
					},
					GroupBox{
						Title:  "设备登记",
						Layout: Grid{Margins: Margins{12, 10, 12, 10}, Spacing: 8, Columns: 2},
						Children: []Widget{
							Label{Text: "广播名前缀（用于 BLE 扫描过滤）"}, LineEdit{AssignTo: &u.edNamePrefix},
						},
					},
					GroupBox{
						Title:  "MES 上传（每条记录异步 POST JSON，默认关闭；仅对保存后的新记录生效）",
						Layout: Grid{Margins: Margins{12, 10, 12, 10}, Spacing: 8, Columns: 2},
						Children: []Widget{
							Label{Text: "启用 MES 上传（URL 不能为空）"}, CheckBox{AssignTo: &u.ckMESEnable, Text: ""},
							Label{Text: "接口 URL"}, LineEdit{AssignTo: &u.edMESURL},
							Label{Text: "Bearer Token（可留空）"}, LineEdit{AssignTo: &u.edMESToken, PasswordMode: true},
							Label{Text: "字段前缀（可留空）"}, LineEdit{AssignTo: &u.edMESPrefix},
							Label{Text: "超时 (ms)"}, LineEdit{AssignTo: &u.edMESTimeout},
							Label{Text: "失败重试次数 (0-5)"}, LineEdit{AssignTo: &u.edMESRetry},
							Label{Text: "上传范围"}, ComboBox{AssignTo: &u.cmbMESScope, Editable: false, Value: int(0), Model: []string{"全部记录", "仅合格", "仅超差"}},
						},
					},
					Composite{
						Layout: HBox{Spacing: 10},
						Children: []Widget{
							PushButton{Text: "保存设置", OnClicked: u.onSaveSettings, MinSize: Size{130, 32}},
							PushButton{Text: "恢复默认", OnClicked: u.onResetSettings},
						},
					},
					GroupBox{
						Title:  "HID 设备自动归档 · 学习绑定",
						Layout: VBox{Margins: Margins{12, 8, 12, 8}, Spacing: 6},
						Children: []Widget{
							Label{Text: "在“实时检测”页选中通道 CHn，用一把卡尺测一次即绑定到该通道；此后该卡尺自动归入 CHn，不受下拉框影响。", TextColor: colorSub},
							Composite{
								Layout: HBox{Spacing: 10},
								Children: []Widget{
									PushButton{Text: "列出 HID 设备（自检）", OnClicked: u.onListHIDDevices, MinSize: Size{150, 30}},
									PushButton{Text: "清除所有绑定", OnClicked: u.onClearHIDBindings, MinSize: Size{120, 30}},
								},
							},
						},
					},
					Label{AssignTo: &u.lblMESStatus, Text: "MES：未启用", TextColor: colorSub},
					Label{Text: "设置保存在本地配置文件，重启后保留。", TextColor: colorSub},
					VSpacer{},
				},
			},
		},
	}
}

func shortAddr(addr string) string {
	if len(addr) > 17 {
		return addr[:8] + "…" + addr[len(addr)-6:]
	}
	return addr
}

func protocolFileName(ext string) string {
	return protocol.FileName("BATCH", protocol.NowStamp(), "ALL", ext)
}
