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
	colorText   = walk.RGB(30, 38, 48)
	colorSub    = walk.RGB(96, 110, 128)
	colorBorder = walk.RGB(214, 222, 232)
	colorBg     = walk.RGB(244, 247, 250)
	colorWhite  = walk.RGB(255, 255, 255)

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
	fontReading   *walk.Font
	fontCell      *walk.Font
	fontCellBold  *walk.Font
	fontCellSmall *walk.Font // 时间/单位列的小号字体
)

// tableFontSizePt 是表格内容字号的基准值（窗口为基准宽度时）。按需求把原先
// 10pt 的单元格文字整体放大约 3 倍。表格现在会随窗口宽度响应式缩放字号，
// 该常量是缩放基准（scale=1 时的磅值），不是固定值。
const tableFontSizePt = 30

// initFonts 创建读数等不随表格缩放的字体。表格字体由 rebuildTableFonts 按
// 当前窗口尺寸创建，故这里只初始化读数字体。
func initFonts() {
	fontReading, _ = walk.NewFont("Consolas", 30, walk.FontBold)
}

// rebuildTableFonts 按缩放系数重建表格相关字体（控件行高字体、数值粗体、
// 小号字体）。walk 的 TableView 行高由控件字体决定，所以三者必须用同一个
// scale 一起重建，否则放大后的文字会被未变高的行截断。
func rebuildTableFonts(scale float64) {
	ptBig := scaleInt(tableFontSizePt, scale) // 30pt 基准
	ptSmall := scaleInt(18, scale)            // 时间/单位列基准 18pt
	if ptBig < 8 {
		ptBig = 8
	}
	if ptSmall < 7 {
		ptSmall = 7
	}
	cell, _ := walk.NewFont("Consolas", ptBig, 0)
	bold, _ := walk.NewFont("Consolas", ptBig, walk.FontBold)
	small, _ := walk.NewFont("Microsoft YaHei", ptSmall, 0)

	oldCell, oldBold, oldSmall := fontCell, fontCellBold, fontCellSmall
	fontCell, fontCellBold, fontCellSmall = cell, bold, small
	if oldCell != nil {
		oldCell.Dispose()
	}
	if oldBold != nil {
		oldBold.Dispose()
	}
	if oldSmall != nil {
		oldSmall.Dispose()
	}
}

func scaleInt(base int, scale float64) int {
	v := float64(base) * scale
	if v < 0 {
		return 0
	}
	return int(v + 0.5)
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
