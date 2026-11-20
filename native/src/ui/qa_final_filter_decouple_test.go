//go:build windows

package ui

import (
	"testing"

	"glcaliper/core"
	"glcaliper/protocol"
)

// TestChartFilterKeyDecoupledFromChannelName guards regression D: renaming a
// channel (to a Chinese label or a pure-numeric Bug-2 label) must never change
// which records the CHn filter key selects. The core filter matches on the
// numeric record channel, so display names are irrelevant.
func TestChartFilterKeyDecoupledFromChannelName(t *testing.T) {
	recs := []protocol.Record{
		{T: "08:00:00", ValueUM: 10000, Ch: 1, Verdict: "合格", TS: 1},
		{T: "08:00:01", ValueUM: 11000, Ch: 2, Verdict: "合格", TS: 2},
		{T: "08:00:02", ValueUM: 12000, Ch: 3, Verdict: "合格", TS: 3},
		{T: "08:00:03", ValueUM: 11100, Ch: 2, Verdict: "超差", TS: 4},
		{T: "08:00:04", ValueUM: 10100, Ch: 1, Verdict: "合格", TS: 5},
	}

	for _, tc := range []struct {
		name    string
		ch2name string
	}{
		{"default", ""},
		{"chinese", "内径-改"},
		{"numeric_bug2", "3133333"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := core.DefaultSettings()
			s.Channels[0].Name = "外径"
			s.Channels[1].Name = tc.ch2name // CH2 - the filter target
			s.Channels[2].Name = "直径"

			data := core.BuildChartData(recs, s, "CH2")
			if got := len(data.Points); got != 2 {
				t.Fatalf("BuildChartData(CH2) points = %d, want 2", got)
			}
			for _, p := range data.Points {
				if p.Ch != 2 {
					t.Errorf("filtered point has Ch=%d, want 2", p.Ch)
				}
			}
			// CH2 points must carry the configured display name, but the
			// selection itself must stay exactly the same across renames.
			if tc.ch2name != "" {
				for _, p := range data.Points {
					if p.ChName != tc.ch2name {
						t.Errorf("point ChName = %q, want %q", p.ChName, tc.ch2name)
					}
				}
			}
		})
	}
}
