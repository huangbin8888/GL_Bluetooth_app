//go:build windows

package ui

import (
	"syscall"
	"unicode/utf16"
	"unsafe"

	"github.com/lxn/win"
)

// SysHeader32 draws every column header with one control font, so walk has no
// per-column header font API. To make most headers small while keeping the
// "实测值" header large, we subclass each internal ListView WndProc and handle
// the header's NM_CUSTOMDRAW: at CDDS_ITEMPREPAINT we SelectObject a font per
// column index and return CDRF_NEWFONT. All other messages are forwarded to
// walk's original WndProc, so sorting / tooltip / clicks stay untouched.
//
// This is the standard Win32 header custom-draw mechanism. Kept isolated in
// this file so it can be backed out without touching data logic.

const (
	headerSmallBasePt = 16 // 其余列的表头字号基准（小一些）
	headerBigBasePt   = 30 // 实测值表头基准：与控件字体一致的大字
	headerMeasCol     = 3  // 实测值列索引（0时间 1设备名称 2通道 3实测值 4单位 5判定）
)

var (
	// 表头字号随窗口缩放，故用变量而非常量。
	headerSmallPt = headerSmallBasePt
	headerBigPt   = headerBigBasePt

	headerFontSmall win.HFONT
	headerFontBig   win.HFONT
	headerHwnds     = make(map[win.HWND]bool)
	lvOrigProc      = make(map[win.HWND]uintptr)
	headerSubProc   uintptr
)

// makeHeaderFont creates a standalone HFONT for header custom draw.
func makeHeaderFont(face string, pointSize int, weight int32) win.HFONT {
	hdc := win.GetDC(0)
	dpi := win.GetDeviceCaps(hdc, win.LOGPIXELSY)
	win.ReleaseDC(0, hdc)
	lf := win.LOGFONT{
		LfHeight:  -win.MulDiv(int32(pointSize), dpi, 72),
		LfWeight:  weight,
		LfCharSet: 1, // DEFAULT_CHARSET
		LfQuality: 2, // PROOF_QUALITY
	}
	copy(lf.LfFaceName[:], utf16.Encode([]rune(face)))
	return win.CreateFontIndirect(&lf)
}

var headerSubclassWndProc = syscall.NewCallback(func(hwnd win.HWND, msg uint32, wp, lp uintptr) uintptr {
	// The ListViews are owner-data: every visible cell is fully repainted from
	// the model on each draw, so the default WM_ERASEBKGND (which floods the
	// client with the flat button-face colour) is both redundant and the source
	// of the erase-then-refill flash. Suppress it unconditionally. With
	// LVS_EX_DOUBLEBUFFER already set, the row repaint stays tear-free.
	// Return TRUE = "background already handled".
	if msg == win.WM_ERASEBKGND {
		return 1
	}
	if msg == win.WM_NOTIFY && lp != 0 {
		nmh := (*win.NMHDR)(unsafe.Pointer(lp))
		if nmh.HwndFrom != 0 && headerHwnds[nmh.HwndFrom] && nmh.Code == win.NM_CUSTOMDRAW {
			nmcd := (*win.NMCUSTOMDRAW)(unsafe.Pointer(lp))
			switch nmcd.DwDrawStage {
			case win.CDDS_PREPAINT:
				return win.CDRF_NOTIFYITEMDRAW
			case win.CDDS_ITEMPREPAINT:
				f := headerFontSmall
				if int(nmcd.DwItemSpec) == headerMeasCol {
					f = headerFontBig
				}
				if f != 0 {
					win.SelectObject(nmcd.Hdc, win.HGDIOBJ(f))
				}
				return win.CDRF_NEWFONT
			}
		}
	}
	if orig, ok := lvOrigProc[hwnd]; ok {
		if msg == win.WM_NCDESTROY {
			win.SetWindowLongPtr(hwnd, win.GWLP_WNDPROC, orig)
		}
		return win.CallWindowProc(orig, hwnd, msg, wp, lp)
	}
	return 0
})

// rebuildHeaderFonts recreates the per-column header fonts at the current
// scale and invalidates the header controls so they repaint with the new size.
func rebuildHeaderFonts(scale float64) {
	headerSmallPt = scaleInt(headerSmallBasePt, scale)
	headerBigPt = scaleInt(headerBigBasePt, scale)
	if headerSmallPt < 8 {
		headerSmallPt = 8
	}
	if headerBigPt < 8 {
		headerBigPt = 8
	}
	newSmall := makeHeaderFont("Microsoft YaHei", headerSmallPt, win.FW_NORMAL)
	newBig := makeHeaderFont("Microsoft YaHei", headerBigPt, win.FW_NORMAL)
	if newSmall == 0 || newBig == 0 {
		return
	}
	if headerFontSmall != 0 {
		win.DeleteObject(win.HGDIOBJ(headerFontSmall))
	}
	if headerFontBig != 0 {
		win.DeleteObject(win.HGDIOBJ(headerFontBig))
	}
	headerFontSmall, headerFontBig = newSmall, newBig

	// Force the header controls to repaint with the new fonts.
	// bErase=false: the header's NM_CUSTOMDRAW repaints the entire item row,
	// so a background erase is unnecessary and — on a maximized window where
	// resize reflow is frequent — the WM_ERASEBKGND flash is visible.
	for h := range headerHwnds {
		win.InvalidateRect(h, nil, false)
	}
}

// initHeaderPainting sets up per-column header fonts. Must run after
// initTablePainting has cached the internal ListView handles.
func (u *UI) initHeaderPainting() {
	if len(listViewHandles) == 0 {
		return
	}
	if headerFontSmall == 0 {
		headerFontSmall = makeHeaderFont("Microsoft YaHei", headerSmallPt, win.FW_NORMAL)
		headerFontBig = makeHeaderFont("Microsoft YaHei", headerBigPt, win.FW_NORMAL)
		headerSubProc = headerSubclassWndProc
	}
	headerHwnds = make(map[win.HWND]bool)
	lvOrigProc = make(map[win.HWND]uintptr)
	for _, lv := range listViewHandles {
		hdr := win.HWND(win.SendMessage(lv, win.LVM_GETHEADER, 0, 0))
		if hdr != 0 {
			headerHwnds[hdr] = true
		}
		if _, done := lvOrigProc[lv]; !done {
			orig := win.SetWindowLongPtr(lv, win.GWLP_WNDPROC, headerSubProc)
			if orig != 0 {
				lvOrigProc[lv] = orig
			}
		}
	}
	// walk's TableView.Invalidate() invalidates BOTH internal ListViews AND
	// the parent widget (WidgetBase.Invalidate) with bErase=true. The parent's
	// WM_ERASEBKGND floods the whole table area with COLOR_BTNFACE before the
	// owner-data rows refill it — a visible grey flash on a maximized window.
	// Subclass the parent too so the same WM_ERASEBKGND guard applies. The
	// shared WndProc forwards every other message to the real procedure, and
	// the header NM_CUSTOMDRAW branch is keyed on header HWNDs, so the parent
	// is unaffected by it.
	if u.tableView != nil {
		parent := u.tableView.Handle()
		if parent != 0 {
			if _, done := lvOrigProc[parent]; !done {
				orig := win.SetWindowLongPtr(parent, win.GWLP_WNDPROC, headerSubProc)
				if orig != 0 {
					lvOrigProc[parent] = orig
				}
			}
		}
	}
}
