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
	headerSmallPt = 16 // 其余列的表头字号（小一些）
	headerBigPt   = 30 // 实测值表头：保持与控件字体一致的大字
	headerMeasCol = 2  // 实测值列索引（0时间 1通道 2实测值 3单位 4判定）
)

var (
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
}
