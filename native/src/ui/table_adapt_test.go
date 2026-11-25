//go:build windows

package ui

import "testing"

func TestTableScaleForWidth(t *testing.T) {
	cases := []struct {
		avail int
		want  float64
	}{
		{0, 1},                // invalid -> neutral
		{-5, 1},               // invalid -> neutral
		{1150, 1},             // baseline
		{575, 0.5},            // half width -> half scale
		{100, minTableScale},  // very small -> clamped low
		{1900, maxTableScale}, // very large -> clamped high
	}
	for _, c := range cases {
		got := tableScaleForWidth(c.avail)
		if absFloat(got-c.want) > 1e-9 {
			t.Errorf("tableScaleForWidth(%d) = %v, want %v", c.avail, got, c.want)
		}
	}
	// Monotonic in the linear region.
	if tableScaleForWidth(400) >= tableScaleForWidth(800) {
		t.Error("scale should increase with available width")
	}
}

func TestScaleInt(t *testing.T) {
	if got := scaleInt(30, 1); got != 30 {
		t.Errorf("scaleInt(30,1)=%d", got)
	}
	if got := scaleInt(30, 0.5); got != 15 {
		t.Errorf("scaleInt(30,0.5)=%d, want 15", got)
	}
	if got := scaleInt(30, 1.5); got != 45 {
		t.Errorf("scaleInt(30,1.5)=%d, want 45", got)
	}
}

func TestColumnWidthsScaleToFill(t *testing.T) {
	// At baseline scale the widths sum to the reference width.
	sum := 0
	for _, w := range baseColWidths {
		sum += w
	}
	if sum != refTableWidth {
		t.Fatalf("base column sum %d != ref %d", sum, refTableWidth)
	}
	// At any scale, scaled widths sum to about avail (within rounding).
	avail := 600
	scale := tableScaleForWidth(avail)
	got := 0
	for _, w := range baseColWidths {
		got += scaleInt(w, scale)
	}
	if absFloat(float64(got)-float64(avail)) > float64(len(baseColWidths)) {
		t.Errorf("scaled widths sum %d, want ~%d", got, avail)
	}
}
