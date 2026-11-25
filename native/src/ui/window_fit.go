//go:build windows

package ui

import (
	"syscall"
	"unsafe"

	"github.com/lxn/win"
)

// Window sizing helpers. Two problems are solved here without touching any
// page layout:
//
//   - walk computes the form's minimum size from all tab pages (the settings
//     page is tall), producing a min-track height around 1281px. On smaller
//     displays that pushes the title bar (and its minimize/maximize/close
//     buttons) off the top of the screen, so the window appears to have no
//     buttons and to "overflow" the screen. We subclass the main window and
//     clamp WM_GETMINMAXINFO to the monitor work area.
//   - The initial window is centered and clamped into the work area on show.

const (
	spiGetWorkArea  = 0x0030
	fitMarginPixels = 16
)

var (
	mainOrigWndProc uintptr
	mainSubProc     = syscall.NewCallback(fitWndProc)
)

// workAreaPixels returns the primary monitor work area in physical pixels
// (excludes the taskbar). Raw Win32 is used so walk's logical-pixel/DPI
// conversion cannot skew the numbers.
func workAreaPixels() (left, top, width, height int32, ok bool) {
	var work win.RECT
	if !win.SystemParametersInfo(spiGetWorkArea, 0, unsafe.Pointer(&work), 0) {
		return 0, 0, 0, 0, false
	}
	return work.Left, work.Top, work.Right - work.Left, work.Bottom - work.Top, true
}

// fitWndProc caps the min/max tracking size to the work area so the title bar
// can never be pushed off-screen. It delegates everything else to walk.
// Responsive table resizing is triggered from the ListView subclass instead,
// where the client width is authoritative.
func fitWndProc(hwnd win.HWND, msg uint32, wp, lp uintptr) uintptr {
	if msg == win.WM_GETMINMAXINFO && lp != 0 && mainOrigWndProc != 0 {
		// Let walk fill in its own min/max values first.
		win.CallWindowProc(mainOrigWndProc, hwnd, msg, wp, lp)
		mmi := (*win.MINMAXINFO)(unsafe.Pointer(lp))
		_, _, aw, ah, ok := workAreaPixels()
		if ok && aw > 0 && ah > 0 {
			maxW := aw - 2*fitMarginPixels
			maxH := ah - 2*fitMarginPixels
			if maxW < 480 {
				maxW = aw
			}
			if maxH < 320 {
				maxH = ah
			}
			// A window bigger than the screen is never useful: cap both the
			// max track size and the min track size to the work area. Capping
			// the min is what lets a tall form shrink into a small display.
			if mmi.PtMaxTrackSize.X > maxW {
				mmi.PtMaxTrackSize.X = maxW
			}
			if mmi.PtMaxTrackSize.Y > maxH {
				mmi.PtMaxTrackSize.Y = maxH
			}
			if mmi.PtMinTrackSize.X > maxW {
				mmi.PtMinTrackSize.X = maxW
			}
			if mmi.PtMinTrackSize.Y > maxH {
				mmi.PtMinTrackSize.Y = maxH
			}
		}
		return 0
	}
	if mainOrigWndProc != 0 {
		if msg == win.WM_NCDESTROY {
			win.SetWindowLongPtr(hwnd, win.GWLP_WNDPROC, mainOrigWndProc)
		}
		return win.CallWindowProc(mainOrigWndProc, hwnd, msg, wp, lp)
	}
	return 0
}

// installWindowFit subclasses the main window. Call once after creation.
func installWindowFit(hwnd win.HWND) {
	if hwnd == 0 || mainOrigWndProc != 0 {
		return
	}
	orig := win.SetWindowLongPtr(hwnd, win.GWLP_WNDPROC, mainSubProc)
	if orig != 0 {
		mainOrigWndProc = orig
	}
}

// applyNoFlickerStyles sets WS_CLIPCHILDREN on the live table's HWNDs only:
// the TableView and its two internal owner-data ListViews.
//
// Scope is deliberately narrow. A previous version applied the style to the
// ENTIRE window tree, which caused "花屏" (stale/smearing pixels) on both the
// settings page and the connection page. Root cause (walk window.go:2219
// backgroundEffective + 2249 prepareDCForBackground): every walk widget paints
// its own background by inheriting the nearest ancestor brush and aligning it
// with SetBrushOrgEx. Containers that move or hide their child windows
// (ScrollView scrolls via SetYPixels; TabWidget hides inactive TabPages on
// tab switches) rely on the parent repainting the vacated rectangle.
// WS_CLIPCHILDREN suppresses that repaint, leaving stale pixels behind. The
// TabWidget is a shared ancestor of every page, so the damage spread from the
// settings page to the connection page.
//
// The table's HWNDs have a static child set that never moves, so clipping
// their parent paint is safe and keeps the maximize/resize flicker fix local.
func applyNoFlickerStyles(hwnd win.HWND) {
	if hwnd == 0 {
		return
	}
	setNoFlickerStyle(hwnd)
	// The two internal ListViews are contained in listViewHandles (populated by
	// initTablePainting, which runs before this call in afterCreate).
	for _, lv := range listViewHandles {
		setNoFlickerStyle(lv)
	}
}

func setNoFlickerStyle(hwnd win.HWND) {
	const want = int32(win.WS_CLIPCHILDREN | win.WS_CLIPSIBLINGS)
	st := win.GetWindowLong(hwnd, win.GWL_STYLE)
	if st&want != want {
		win.SetWindowLong(hwnd, win.GWL_STYLE, st|want)
	}
}

// fitWindowToWorkArea centers the window and clamps its size into the visible
// work area (physical pixels). The resulting WM_SIZE lets layout re-flow.
func fitWindowToWorkArea(hwnd win.HWND) {
	if hwnd == 0 {
		return
	}
	wl, wt, aw, ah, ok := workAreaPixels()
	if !ok || aw <= 0 || ah <= 0 {
		return
	}
	var rc win.RECT
	if !win.GetWindowRect(hwnd, &rc) {
		return
	}

	w := rc.Right - rc.Left
	h := rc.Bottom - rc.Top
	maxW := aw - 2*fitMarginPixels
	maxH := ah - 2*fitMarginPixels
	if maxW < 480 {
		maxW = aw
	}
	if maxH < 320 {
		maxH = ah
	}
	if w > maxW {
		w = maxW
	}
	if h > maxH {
		h = maxH
	}

	x := wl + (aw-w)/2
	y := wt + (ah-h)/2
	if x < wl {
		x = wl
	}
	if y < wt {
		y = wt
	}
	win.SetWindowPos(hwnd, 0, x, y, w, h, win.SWP_NOZORDER|win.SWP_NOACTIVATE)
}
