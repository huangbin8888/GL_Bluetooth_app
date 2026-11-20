//go:build windows

package ui

import (
	"fmt"

	"github.com/lxn/walk"
	"golang.org/x/sys/windows"
)

// Precision Instrument 设计语言：
// 浅色工作区保证 Win7/10 原生控件可读；深色「仪表盘」承载读数与数据；
// 单一琥珀强调色 + 等宽数字 + 判定色块，工业高对比。
var (
	// 浅色工作区
	colorText    = walk.RGB(30, 38, 48)
	colorSub     = walk.RGB(96, 110, 128)
	colorBorder  = walk.RGB(214, 222, 232)
	colorBg      = walk.RGB(244, 247, 250)
	colorWhite   = walk.RGB(255, 255, 255)

	// 琥珀强调（浅底用深琥珀，深底用亮琥珀）
	colorPrimary     = walk.RGB(198, 102, 8)
	colorPrimarySoft = walk.RGB(255, 248, 235)
	colorWarn        = walk.RGB(232, 158, 40)
	colorAmberHi     = walk.RGB(255, 183, 77)

	// 深色数据面板 / 标题栏（对应原型 --bg-0/2/3）
	colorDark     = walk.RGB(17, 23, 30)
	colorDarkText = walk.RGB(234, 240, 246)
	colorPanel    = walk.RGB(23, 30, 39)
	colorPanel2   = walk.RGB(30, 39, 50)

	// 判定色块（深底上的高对比）
	colorOK       = walk.RGB(62, 180, 125)
	colorBad      = walk.RGB(225, 70, 85)
	colorOKPanel  = walk.RGB(20, 58, 46)
	colorOKText   = walk.RGB(120, 235, 185)
	colorBadPanel = walk.RGB(68, 28, 34)
	colorBadText  = walk.RGB(255, 145, 155)

	// 来源分色
	colorSrcBLE  = walk.RGB(120, 200, 245)
	colorSrcHID  = walk.RGB(255, 183, 77)
	colorSrcDemo = walk.RGB(185, 155, 255)

	// 等宽字体（Precision Instrument 的数字语言）
	fontReading  *walk.Font
	fontCell     *walk.Font
	fontCellBold *walk.Font
	fontCellSmall *walk.Font // 时间/单位列的小号字体
)

// tableFontSizePt 是表格内容字号：按需求把原先 10pt 的单元格文字整体放大约 3 倍。
// 注意：表格行高由 TableView 控件字体决定（walk 的 applyFont 会向两个内部
// SysListView32 发 WM_SETFONT），因此控件字体与单元格字体必须一起放大，
// 否则行高不变会把放大后的文字截断。列宽在 pages_detect.go 同步放大。
const tableFontSizePt = 30

// initFonts 创建表格/读数使用的字体（只创建一次）。
func initFonts() {
	fontReading, _ = walk.NewFont("Consolas", 30, walk.FontBold)
	fontCell, _ = walk.NewFont("Consolas", tableFontSizePt, 0)
	fontCellBold, _ = walk.NewFont("Consolas", tableFontSizePt, walk.FontBold)
	// 时间/单位列比主字号小一档（用户要求"稍微小一点"）。
	fontCellSmall, _ = walk.NewFont("Microsoft YaHei", 18, 0)
}

// osVersion returns major.minor.build. Win7 = 6.1.xxxx.
func osVersion() (major, minor, build uint32) {
	v, err := windows.GetVersion()
	if err != nil {
		return 10, 0, 0
	}
	major = uint32(v & 0xFF)
	minor = uint32((v >> 8) & 0xFF)
	build = uint32((v >> 16) & 0xFFFF)
	return
}

func isWin7() bool {
	maj, min, _ := osVersion()
	return maj == 6 && min == 1
}

func osVersionText() string {
	maj, min, b := osVersion()
	return fmt.Sprintf("%d.%d.%d", maj, min, b)
}

// msgBox helpers
func info(parent walk.Form, title, msg string) {
	walk.MsgBox(parent, title, msg, walk.MsgBoxIconInformation|walk.MsgBoxOK)
}
func warn(parent walk.Form, title, msg string) {
	walk.MsgBox(parent, title, msg, walk.MsgBoxIconExclamation|walk.MsgBoxOK)
}
func errBox(parent walk.Form, title, msg string) {
	walk.MsgBox(parent, title, msg, walk.MsgBoxIconError|walk.MsgBoxOK)
}
func confirm(parent walk.Form, title, msg string) bool {
	return walk.MsgBox(parent, title, msg, walk.MsgBoxYesNo|walk.MsgBoxIconQuestion) == walk.DlgCmdYes
}
