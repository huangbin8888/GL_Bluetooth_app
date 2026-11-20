//go:build windows

package ui

import (
	"syscall"
	"unsafe"

	"github.com/lxn/walk"
	"github.com/lxn/win"
)

// The live table is a walk TableView built on an owner-data SysListView32.
//
// Flicker root cause (paint layer, not data layer):
//   walk's TableView.Invalidate() calls InvalidateRect(hwnd, nil, true) on BOTH
//   internal ListViews AND the parent widget. bErase=true sends WM_ERASEBKGND,
//   which paints the whole client area with the flat button-face colour before
//   the owner-data callback refills every visible cell. With newest-first
//   ordering, every incoming record shifts all visible rows, so this
//   erase-then-refill gap is repainted on every sample and reads as flicker —
//   worse the more rows are visible ("数据多时闪烁"). Data-layer fixes (cache,
//   reverse order, no auto-scroll) cannot touch this paint intermediate.
//
// Fix: repaint only the visible item range with LVM_REDRAWITEMS — the exact
// mechanism walk itself uses in its RowsChanged handler (tableview.go:747).
// It redraws just those rows without an whole-client-area WM_ERASEBKGND, so
// cells are replaced in place. The ListViews already carry LVS_EX_DOUBLEBUFFER
// from walk's constructor; we re-assert it defensively.

// listViewHandles caches the two internal SysListView32 child windows of the
// TableView (walk creates a "frozen" and a "normal" one). Cached once at
// afterCreate because the TableView HWND tree is static for the window life.
var listViewHandles []win.HWND

var enumListViewProc = syscall.NewCallback(func(hwnd win.HWND, lParam uintptr) uintptr {
	buf := make([]uint16, 64)
	n, err := win.GetClassName(hwnd, &buf[0], len(buf))
	if err == nil && n > 0 && syscall.UTF16ToString(buf[:n]) == "SysListView32" {
		handles := (*[]win.HWND)(unsafe.Pointer(lParam))
		*handles = append(*handles, hwnd)
	}
	return 1 // continue enumeration
})

// initTablePainting caches the internal ListView handles and re-asserts the
// double-buffer style on each. Must run after the TableView is created.
func (u *UI) initTablePainting() {
	listViewHandles = listViewHandles[:0]
	if u.tableView == nil {
		return
	}
	win.EnumChildWindows(u.tableView.Handle(), enumListViewProc, uintptr(unsafe.Pointer(&listViewHandles)))
	for _, h := range listViewHandles {
		ex := win.SendMessage(h, win.LVM_GETEXTENDEDLISTVIEWSTYLE, 0, 0)
		ex |= win.LVS_EX_DOUBLEBUFFER
		win.SendMessage(h, win.LVM_SETEXTENDEDLISTVIEWSTYLE, 0, ex)
	}
}

// redrawRange returns the inclusive visible item range to repaint, clamped to
// [0, count). perPage+1 covers the partially visible row at the bottom.
// Kept pure so the range arithmetic can be regression-tested without a window.
func redrawRange(top, perPage, count int) (first, last int) {
	first = top
	if first < 0 {
		first = 0
	}
	last = top + perPage + 1
	if count <= 0 {
		return first, -1
	}
	if last >= count {
		last = count - 1
	}
	if first >= count {
		return count - 1, last
	}
	return first, last
}

// repaintTable redraws only the visible rows of the owner-data ListViews.
// This replaces the whole TableView Invalidate() so no WM_ERASEBKGND fires on
// the parent, eliminating the blank-then-filled intermediate that the user
// sees as flicker. Falls back to Invalidate only before handles are cached
// (should not happen in the running app).
func (u *UI) repaintTable() {
	if len(listViewHandles) == 0 {
		if u.tableView != nil {
			u.tableView.Invalidate()
		}
		return
	}
	count := 0
	if u.model != nil {
		count = u.model.RowCount()
	}
	for _, h := range listViewHandles {
		top := int(win.SendMessage(h, win.LVM_GETTOPINDEX, 0, 0))
		perPage := int(win.SendMessage(h, win.LVM_GETCOUNTPERPAGE, 0, 0))
		first, last := redrawRange(top, perPage, count)
		if first <= last {
			win.SendMessage(h, win.LVM_REDRAWITEMS, uintptr(first), uintptr(last))
		}
	}
}

var _ = walk.Key(0)
