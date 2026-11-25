//go:build windows

package ui

import (
	"testing"

	"glcaliper/core"
)

func TestChartValueLabelUsesDataDigits(t *testing.T) {
	cases := []struct {
		value  float64
		digits int
		want   string
	}{
		{10, 2, "10.00"},
		{10.125, 3, "10.125"},
		{10, 0, "10"},
		{-0.5, 2, "-0.50"},
	}
	for _, c := range cases {
		if got := chartValueLabel(c.value, c.digits); got != c.want {
			t.Errorf("chartValueLabel(%v, %d) = %q, want %q", c.value, c.digits, got, c.want)
		}
	}
}

func TestValueLabelIndicesSparseAndDense(t *testing.T) {
	makePoints := func(n int, value float64) []core.ChartPoint {
		pts := make([]core.ChartPoint, n)
		for i := range pts {
			pts[i].ValueMM = value
		}
		return pts
	}

	sparse := valueLabelIndices(makePoints(4, 10), 2, 600)
	if len(sparse) != 4 || sparse[0] != 0 || sparse[3] != 3 {
		t.Fatalf("sparse indices = %v, want [0 1 2 3]", sparse)
	}

	// "10.00" is 5 raster cells = 30 px; spacing = 600/200 = 3 px, so
	// step = ceil((30+6)/3) = 12. Both ends must survive sampling.
	dense := valueLabelIndices(makePoints(200, 10), 2, 600)
	if len(dense) == 0 || dense[0] != 0 || dense[len(dense)-1] != 199 {
		t.Fatalf("dense indices = %v, want first=0 last=199", dense)
	}
	if len(dense) > 30 {
		t.Fatalf("dense labels not sampled: %d of 200 points", len(dense))
	}
	for i := 1; i < len(dense); i++ {
		if dense[i] <= dense[i-1] || dense[i]-dense[i-1] < 12 {
			t.Fatalf("dense indices = %v: gap at %d is < 12", dense, i)
		}
	}

	if got := valueLabelIndices(makePoints(1, 10), 2, 600); len(got) != 1 || got[0] != 0 {
		t.Fatalf("single-point indices = %v, want [0]", got)
	}
	if got := valueLabelIndices(nil, 2, 600); len(got) != 0 {
		t.Fatalf("empty indices = %v, want none", got)
	}
	if got := valueLabelIndices(makePoints(5, 10), 2, 0); len(got) != 0 {
		t.Fatalf("zero-width indices = %v, want none", got)
	}
}

func TestValueLabelBoxClampsToPlot(t *testing.T) {
	b := labelBounds{Left: 40, Right: 500, Top: 42, Bottom: 370}
	const w, h, gap = 30, 14, 4

	cases := []struct {
		name       string
		x, y       int
		wantLeft   int
		wantTopMin int
		wantTopMax int
	}{
		{"normal", 270, 200, 255, 42, 200 - gap - h},
		{"above top", 42, 42, 40, 42, 46},
		{"below bottom", 500, 370, 470, 42, 370 - h},
		{"left edge", 40, 200, 40, 42, 200 - gap - h},
		{"right edge", 500, 200, 470, 42, 200 - gap - h},
	}
	for _, c := range cases {
		left, top := valueLabelBox(c.x, c.y, b, w, h, gap)
		if left != c.wantLeft {
			t.Errorf("%s: left = %d, want %d", c.name, left, c.wantLeft)
		}
		if top < b.Top || top+h > b.Bottom {
			t.Errorf("%s: label row %d..%d escapes plot %d..%d", c.name, top, top+h, b.Top, b.Bottom)
		}
		if top < c.wantTopMin || top > c.wantTopMax {
			t.Errorf("%s: top = %d, want within [%d,%d]", c.name, top, c.wantTopMin, c.wantTopMax)
		}
	}
}

func TestChannelDisplayNamesFollowSettingsAndKeepIndexMapping(t *testing.T) {
	s := core.DefaultSettings()
	s.Channels[0].Name = "卡尺A"
	s.Channels[1].Name = " 卡尺B "
	s.Channels[2].Name = "CH3" // already canonical: must not become "CH3 CH3"

	names := channelDisplayNames(s)
	if len(names) != len(s.Channels) {
		t.Fatalf("channelDisplayNames length = %d, want %d", len(names), len(s.Channels))
	}
	want := []string{"CH1 卡尺A", "CH2 卡尺B", "CH3"}
	for i := range want {
		if names[i] != want[i] {
			t.Errorf("names[%d] = %q, want %q", i, names[i], want[i])
		}
	}

	scope := filterDisplayNames(s)
	if len(scope) != len(names)+1 || scope[0] != "全部通道" {
		t.Fatalf("filterDisplayNames = %v, want leading 全部通道 and %d items", scope, len(names)+1)
	}
	for i := 0; i < len(names); i++ {
		if scope[i+1] != names[i] {
			t.Errorf("scope[%d] = %q, want %q (index must still map to CH%d)", i+1, scope[i+1], names[i], i+1)
		}
	}

	empty := core.DefaultSettings()
	if got := channelDisplayName(empty, 5); got != "CH5" {
		t.Errorf("default channel name = %q, want CH5", got)
	}
}
