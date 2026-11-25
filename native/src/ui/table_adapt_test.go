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

// TestVerdictColumnFitsContentInSmallWindow guards the "判定显示不全" fix:
// the verdict cell uses the large row font, so in a small window its column
// must not shrink below the width needed for two Chinese glyphs. The floor
// follows the (bounded) font scale and never exceeds content width by much.
func TestVerdictColumnFitsContentInSmallWindow(t *testing.T) {
	for _, avail := range []int{240, 300, 400, 575, 1150} {
		widths := columnWidths(avail)
		if len(widths) != len(baseColWidths) {
			t.Fatalf("avail=%d: got %d columns", avail, len(widths))
		}
		need := verdictMinWidth(tableScaleForWidth(avail))
		if got := widths[verdictCol]; got < need {
			t.Errorf("avail=%d: verdict width %d < content need %d (text would be clipped)", avail, got, need)
		}
	}
	// At the smallest font scale two glyphs still fit; the floor is content-sized,
	// not an arbitrary wide value (<= 3 glyph widths including padding).
	pt := scaleInt(verdictFontSizePt, minTableScale)
	need := verdictMinWidth(minTableScale)
	if need < pt*2 { // two glyphs at the small font size
		t.Errorf("verdict floor %d too small for two %dpt glyphs", need, pt)
	}
	if need > pt*8/3+32 {
		t.Errorf("verdict floor %d wider than content warrants", need)
	}
}

// TestVerdictColumnNotWiderAtBaseline ensures the floor never widens the
// verdict column when the proportional width is already sufficient.
func TestVerdictColumnNotWiderAtBaseline(t *testing.T) {
	widths := columnWidths(refTableWidth)
	if got, want := widths[verdictCol], baseColWidths[verdictCol]; got != want {
		t.Errorf("baseline verdict width = %d, want unchanged %d", got, want)
	}
}
