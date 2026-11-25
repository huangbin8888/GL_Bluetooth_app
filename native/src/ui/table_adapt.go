//go:build windows

package ui

import (
	"time"

	"github.com/lxn/win"
)

// tableClientWidthPixels returns the owner-data ListView's client width in
// physical pixels, or 0 if handles are not cached yet / query fails.
func tableClientWidthPixels() int {
	// The TableView owns two internal ListViews: a frozen-column pane (width 0
	// when there are no frozen columns) and the normal pane. Use the widest.
	best := 0
	for _, h := range listViewHandles {
		var rc win.RECT
		if !win.GetClientRect(h, &rc) {
			continue
		}
		if w := int(rc.Right - rc.Left); w > best {
			best = w
		}
	}
	return best
}

// tableClientWidthLogical converts the physical client width to 96-DPI logical
// pixels (the unit walk's SetWidth uses).
func tableClientWidthLogical() int {
	physW := tableClientWidthPixels()
	if physW <= 0 {
		return 0
	}
	hdc := win.GetDC(0)
	dpi := int(win.GetDeviceCaps(hdc, win.LOGPIXELSY))
	win.ReleaseDC(0, hdc)
	if dpi <= 0 {
		dpi = 96
	}
	return physW * 96 / dpi
}

// Responsive table sizing. The data list adapts to the window: column widths
// and fonts (cells + headers) scale together with the table's available width,
// so a small window shows all five columns without a horizontal scrollbar and
// a maximized window fills the space instead of leaving empty columns.
//
// Design baseline (the previously hand-tuned large-font look) is scale=1:
//   column widths = 170/200/340/190/130/120 (sum 1150), fonts 30/18pt, headers 30/16pt.
// All widths and font sizes are multiplied by the same scale to preserve the
// relative hierarchy (e.g. the "实测值" header stays the largest).

const refTableWidth = 1150 // baseline sum of the 6 column widths (96-DPI px)

// baseline column widths, in order: 时间/设备名称/通道/实测值/单位/判定
var baseColWidths = [...]int{170, 200, 340, 190, 130, 120}

const (
	minTableScale = 0.45
	maxTableScale = 1.6
)

// rawTableScale maps available width to an unbounded scale factor (used for
// column widths so they always fill the table exactly).
func rawTableScale(avail int) float64 {
	if avail <= 0 {
		return 1
	}
	return float64(avail) / refTableWidth
}

// tableScaleForWidth maps an available table width to a bounded scale factor
// (used for fonts, so text stays readable and not absurdly large).
// Kept pure so it can be unit-tested without a GUI.
func tableScaleForWidth(avail int) float64 {
	s := rawTableScale(avail)
	if avail <= 0 {
		return 1
	}
	if s < minTableScale {
		s = minTableScale
	}
	if s > maxTableScale {
		s = maxTableScale
	}
	return s
}

// lastTableScale caches the applied scale to avoid rebuilding fonts (and the
// resulting repaint churn) on every WM_SIZE when nothing changed.
var (
	lastTableScale float64
	adapting       bool // reentrancy guard (SetFont/SetWidth may re-enter)
	lastAvailPx    int  // watcher trigger cache (physical px)
	lastColPx      int  // last width used to lay out columns (physical px)

	// lvRowFont is the HFONT applied directly to the internal ListViews to set
	// row height. walk's TableView.SetFont is deliberately bypassed: its
	// WidgetBase.applyFont calls RequestLayout(), which re-lays out the whole
	// parent and fires InvalidateRect(bErase=true) — on a maximized window the
	// resulting WM_ERASEBKGND flash is visible. Sending WM_SETFONT straight to
	// the ListViews updates row height without any parent layout invalidation.
	lvRowFont win.HFONT
)

// applyRowHeightFont sets the row-height font on both internal ListViews
// directly, without walk's SetFont/RequestLayout whole-table erase. lParam=0
// suppresses each ListView's own repaint; the caller follows with repaintTable()
// which redraws only the visible rows via LVM_REDRAWITEMS (flicker-free).
func applyRowHeightFont(scale float64) {
	pt := scaleInt(tableFontSizePt, scale)
	if pt < 8 {
		pt = 8
	}
	hf := makeHeaderFont("Consolas", pt, win.FW_NORMAL) // same face/size as fontCell
	if hf == 0 {
		return
	}
	for _, lv := range listViewHandles {
		win.SendMessage(lv, win.WM_SETFONT, uintptr(hf), 0)
	}
	if lvRowFont != 0 && lvRowFont != hf {
		win.DeleteObject(win.HGDIOBJ(lvRowFont))
	}
	lvRowFont = hf
}

// adaptTable recomputes column widths and fonts for the current table size.
// Safe to call before the table exists (no-op) and from WM_SIZE on the UI
// thread. It deliberately does not touch any data/model logic.
func (u *UI) adaptTable() {
	if u.tableView == nil || u.main == nil || adapting {
		return
	}
	// The TableView.Bounds() can be 0 before the form finishes layout, so read
	// the real client width from the internal owner-data ListView HWND that
	// initTablePainting cached, then convert physical px -> 96-DPI logical px.
	avail := tableClientWidthLogical()
	px := tableClientWidthPixels() // physical px, same unit the watcher caches
	if avail <= 0 || px <= 0 {
		return
	}
	scale := tableScaleForWidth(avail) // bounded, for fonts
	colScale := rawTableScale(avail)   // unbounded, so columns fill exactly
	fontChanged := lastTableScale == 0 || absFloat(scale-lastTableScale) >= 0.02
	colChanged := lastColPx == 0 || absInt(px-lastColPx) >= 8
	if !fontChanged && !colChanged {
		return
	}
	adapting = true
	defer func() { adapting = false }()

	// 1) Column widths: distribute proportionally across the full width so no
	//    empty trailing area and no horizontal scrollbar remain. LVM_SETCOLUMNWIDTH
	//    only reflows columns (no whole-client WM_ERASEBKGND), so this is safe.
	if colChanged {
		cols := u.tableView.Columns()
		if cols != nil && cols.Len() == len(baseColWidths) {
			for i, base := range baseColWidths {
				w := scaleInt(base, colScale)
				if w < 24 {
					w = 24
				}
				_ = cols.At(i).SetWidth(w)
			}
		}
		lastColPx = px
	}

	// 2) Cell + row-height fonts. Only rebuild when the bounded font scale
	//    actually changed.
	if fontChanged {
		rebuildTableFonts(scale)
		// Feed the row-height font straight to the internal ListViews instead
		// of u.tableView.SetFont(): the latter re-lays out the parent and
		// erases the whole client (WM_ERASEBKGND) — visible flicker on resize.
		applyRowHeightFont(scale)
		rebuildHeaderFonts(scale)
		lastTableScale = scale
	}

	// 4) Repaint rows with the new fonts (flicker-free visible-row redraw).
	u.repaintTable()
}

func absFloat(v float64) float64 {
	if v < 0 {
		return -v
	}
	return v
}

// startTableAdaptWatch polls the internal ListView client width and re-fits
// the table when it changes. A polling watcher is used instead of a WM_SIZE
// subclass because walk re-lays out controls asynchronously: by the time a
// WM_SIZE reaches a subclass the final client width may not be settled yet,
// whereas polling always reads the current, authoritative width. Work is
// marshaled to the UI thread via Synchronize, matching startFilterWatch.
func (u *UI) startTableAdaptWatch() {
	go func() {
		ticker := time.NewTicker(150 * time.Millisecond)
		defer ticker.Stop()
		for range ticker.C {
			if u.main == nil {
				return
			}
			px := tableClientWidthPixels()
			if px <= 0 {
				continue
			}
			// Ignore tiny changes (scrollbar show/hide) to avoid churn.
			if lastAvailPx > 0 && absInt(px-lastAvailPx) < 8 {
				continue
			}
			lastAvailPx = px
			u.main.Synchronize(func() { u.adaptTable() })
		}
	}()
}

func absInt(v int) int {
	if v < 0 {
		return -v
	}
	return v
}
