//go:build windows

package ui

import "testing"

// TestRedrawRangeClampsToVisibleRows is the regression guard for the flicker
// fix: repaintTable must repaint exactly the visible item range [top, top+perPage]
// clamped to [0,count). A bug here would either leave rows un-refreshed (stale
// cells) or invalidate beyond the client area (the old whole-table flicker).
func TestRedrawRangeClampsToVisibleRows(t *testing.T) {
	cases := []struct {
		name            string
		top, perPage, n int
		wantFirst, wantLast int
	}{
		{"empty table", 0, 20, 0, 0, -1},
		{"first page full", 0, 20, 100, 0, 21},
		{"scrolled down", 10, 20, 100, 10, 31},
		{"last page clipped", 90, 20, 100, 90, 99},
		{"negative top clamped", -3, 20, 50, 0, 18},
		{"count less than first", 80, 20, 50, 49, 49},
		{"single row", 0, 20, 1, 0, 0},
	}
	for _, c := range cases {
		f, l := redrawRange(c.top, c.perPage, c.n)
		if f != c.wantFirst || l != c.wantLast {
			t.Errorf("%s: redrawRange(%d,%d,%d) = (%d,%d), want (%d,%d)",
				c.name, c.top, c.perPage, c.n, f, l, c.wantFirst, c.wantLast)
		}
	}
}
