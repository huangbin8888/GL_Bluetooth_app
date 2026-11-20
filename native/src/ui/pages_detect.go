//go:build windows

package ui

import (
	"fmt"
	"strconv"

	"github.com/lxn/walk"
	. "github.com/lxn/walk/declarative"
)

// pageDetect: the core measurement screen with a large live table.
func (u *UI) pageDetect() TabPage {
	return TabPage{
		Title:  "实时检测 · 记录",
		Layout: HBox{Margins: Margins{10, 10, 10, 10}, Spacing: 10},
		Children: []Widget{
			// ---- left: reading + live table ----
			Composite{
				Layout: VBox{MarginsZero: true, Spacing: 8},
				Children: []Widget{
					// reading banner — 深色仪表盘：琥珀等宽大读数
					Composite{
						Layout:     HBox{Margins: Margins{16, 12, 16, 12}, Spacing: 16},
						Background: SolidColorBrush{Color: colorPanel},
						Children: []Widget{
							Label{Text: "当前读数", Font: Font{PointSize: 10, Bold: true}, TextColor: colorSub},
							Label{AssignTo: &u.lblReading, Text: "—", Font: Font{Family: "Consolas", PointSize: 30, Bold: true}, TextColor: colorAmberHi, MinSize: Size{240, 54}},
							HSpacer{},
							Label{Text: "通道", TextColor: colorDarkText},
							ComboBox{AssignTo: &u.cmbChannel, Editable: false, Value: int(0), Model: channelDisplayNames(u.app.GetSettings()),
								OnCurrentIndexChanged: func() {
									if u.cmbChannel != nil && u.cmbChannel.CurrentIndex() >= 0 {
										u.app.SetChannel(u.cmbChannel.CurrentIndex() + 1)
										u.setStatus(fmt.Sprintf("当前通道 CH%d", u.app.Channel()))
									}
								}},
							Label{Text: "筛选", TextColor: colorDarkText},
							ComboBox{AssignTo: &u.cmbFilter, Editable: false, Value: int(0), Model: filterDisplayNames(u.app.GetSettings()),
								OnCurrentIndexChanged: u.onFilterChanged},
						},
					},
					// HID live char strip
					Composite{
						Layout:     HBox{Margins: Margins{10, 4, 10, 4}, Spacing: 10},
						Background: SolidColorBrush{Color: colorPanel2},
						Children: []Widget{
							Label{Text: "HID 捕获", Font: Font{PointSize: 9, Bold: true}, TextColor: colorAmberHi},
							Label{AssignTo: &u.lblHidLive, Text: "（未开启）", Font: Font{Family: "Consolas", PointSize: 10}, TextColor: colorDarkText},
							HSpacer{},
							Label{AssignTo: &u.lblHidState, Text: "", TextColor: colorSub},
						},
					},
					// big live table — 内容字体放大约 3 倍（表格控件外形尺寸不变）。
					// 控件字体决定 owner-data 行高，必须与 fontCell 一起放大，
					// 否则放大后的单元格文字会被未变高的行截断。
					// 时间/单位列用小号字体；通道列保留至少 8 个汉字宽度；来源列不显示。
					TableView{
						AssignTo:         &u.tableView,
						Model:            u.model,
						AlternatingRowBG: true,
						Font:             Font{Family: "Microsoft YaHei", PointSize: tableFontSizePt},
						Columns: []TableViewColumn{
							{Title: "时间", Width: 170, Alignment: AlignCenter},
							{Title: "通道", Width: 340, Alignment: AlignCenter},
							{Title: "实测值", Width: 190, Alignment: AlignCenter},
							{Title: "单位", Width: 130, Alignment: AlignCenter},
							{Title: "判定", Width: 120, Alignment: AlignCenter},
						},
						OnKeyDown: u.onTableKeyDown,
					},
					// summary strip
					Composite{
						Layout: HBox{Margins: Margins{4, 2, 4, 2}, Spacing: 12},
						Children: []Widget{
							Label{AssignTo: &u.lblCount, Text: "共 0 条", Font: Font{PointSize: 10, Bold: true}},
							Label{AssignTo: &u.lblStats, Text: "", TextColor: colorSub},
						},
					},
				},
			},
			// ---- right: control dock — 按频率×危险程度分层 ----
			Composite{
				Layout:     VBox{Margins: Margins{10, 10, 10, 10}, Spacing: 10},
				MinSize:    Size{224, 0},
				Background: SolidColorBrush{Color: colorPrimarySoft},
				Children: []Widget{
					// 卡片 1：采集控制（高频）
					GroupBox{
						Title:  "采集控制",
						Layout: VBox{Margins: Margins{10, 8, 10, 8}, Spacing: 7},
						Children: []Widget{
							PushButton{AssignTo: &u.btnHidToggle, Text: "开启 HID 全局捕获", OnClicked: u.onHidToggle, MinSize: Size{190, 36}},
							PushButton{Text: "清空 HID 缓冲", OnClicked: func() {
								if u.bridge != nil {
									u.bridge.HIDClearBuffer()
								}
								if u.lblHidLive != nil {
									u.lblHidLive.SetText("（已清空）")
								}
							}},
							PushButton{AssignTo: &u.btnDemo, Text: "开始演示模式", OnClicked: u.onDemo, MinSize: Size{190, 36}},
						},
					},
					// 卡片 2：记录管理（破坏性操作隔离）
					GroupBox{
						Title:  "记录管理",
						Layout: VBox{Margins: Margins{10, 8, 10, 8}, Spacing: 7},
						Children: []Widget{
							Label{Text: "以下操作不可撤销", Font: Font{PointSize: 9, Bold: true}, TextColor: colorBad},
							PushButton{Text: "删除最后一条", OnClicked: u.onDeleteLastVisible},
							PushButton{Text: "清空全部记录", OnClicked: func() {
								if confirm(u.main, "清空记录", "确定清空全部记录？此操作不可撤销。") {
									u.app.Reset()
								}
							}},
						},
					},
					VSpacer{},
					Label{Text: "提示", Font: Font{PointSize: 10, Bold: true}, TextColor: colorSub},
					Label{Text: "HID：卡尺配对成键盘后开启捕获，", TextColor: colorSub},
					Label{Text: "任意界面读数自动录入。演示模式", TextColor: colorSub},
					Label{Text: "用于无硬件时预览。", TextColor: colorSub},
				},
			},
		},
	}
}

func channelNames(n int) []string {
	out := make([]string, n)
	for i := 0; i < n; i++ {
		out[i] = fmt.Sprintf("CH%d", i+1)
	}
	return out
}

func filterNames(n int) []string {
	out := []string{"全部通道"}
	for i := 0; i < n; i++ {
		out = append(out, fmt.Sprintf("CH%d", i+1))
	}
	return out
}

func (u *UI) onFilterChanged() {
	// applyFilter snapshots the live index into the model cache AND forces a
	// repaint (walk's RowsReset alone never redraws existing owner-data cells).
	u.applyFilter()
}

func (u *UI) onTableKeyDown(key walk.Key) {
	switch key {
	case walk.KeyDelete:
		row := u.tableView.CurrentIndex()
		if row < 0 {
			return
		}
		// The TableView row belongs to the filtered/sorted view; translate it
		// back to the full Records index before deleting, otherwise the wrong
		// record (often from another channel) is removed.
		if orig := u.model.OriginalIndex(row); orig >= 0 {
			u.app.DeleteIndex(orig)
		}
	}
}

// onDeleteLastVisible removes the most recently recorded row the user can
// actually see. The table shows newest-first, so the newest visible record is
// always row 0. Under a channel filter it is the newest record of the selected
// channel, not the global newest record.
func (u *UI) onDeleteLastVisible() {
	if u.model.RowCount() == 0 {
		return
	}
	// Newest-first display: row 0 is the latest visible record.
	if orig := u.model.OriginalIndex(0); orig >= 0 {
		u.app.DeleteIndex(orig)
	}
}

func (u *UI) onHidToggle() {
	if u.bridge == nil {
		return
	}
	if u.bridge.HIDEnabled() {
		u.bridge.HIDStop()
		u.btnHidToggle.SetText("开启 HID 全局捕获")
		u.lblHidState.SetText("")
		u.lblHidLive.SetText("（已关闭）")
		u.setStatus("HID 捕获已关闭")
	} else {
		if err := u.bridge.HIDStart(); err != nil {
			errBox(u.main, "HID 启动失败", err.Error())
			return
		}
		u.btnHidToggle.SetText("关闭 HID 全局捕获")
		u.lblHidLive.SetText("等待读数…")
		u.setStatus("HID 全局捕获已开启（任意界面录入）")
	}
}

func (u *UI) onDemo() {
	on := u.app.ToggleDemo()
	if on {
		u.btnDemo.SetText("停止演示模式")
		u.setStatus("演示模式运行中")
	} else {
		u.btnDemo.SetText("开始演示模式")
		u.setStatus("演示模式已停止")
	}
}

// called by bridge when a HID char arrives
func (u *UI) OnHidChar(ch string) {
	if u.lblHidLive == nil {
		return
	}
	u.main.Synchronize(func() {
		cur := u.lblHidLive.Text()
		if len([]rune(cur)) > 24 {
			cur = ""
		}
		u.lblHidLive.SetText(cur + ch)
	})
}

func (u *UI) refreshReading() {
	if u.lblReading == nil {
		return
	}
	recs := u.app.RecordsSnapshot()
	if len(recs) == 0 {
		u.lblReading.SetText("—")
		return
	}
	last := recs[len(recs)-1]
	u.lblReading.SetText(u.fmtMM(last.ValueUM))
}

func (u *UI) refreshCountStats() {
	if u.lblCount == nil {
		return
	}
	st := u.app.Stats()
	u.lblCount.SetText(fmt.Sprintf("共 %d 条", st.N))
	if st.N > 0 {
		s := u.app.GetSettings()
		u.lblStats.SetText(fmt.Sprintf("均值 %s  最大 %s  最小 %s  合格 %d%%",
			u.fmtMM(int64(st.MeanUM)), u.fmtMM(st.MaxUM), u.fmtMM(st.MinUM), st.OKRate))
		_ = s
	} else {
		u.lblStats.SetText("")
	}
	u.refreshAnalysis()
}

var _ = strconv.Itoa
