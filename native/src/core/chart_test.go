package core

import (
	"math"
	"testing"

	"glcaliper/protocol"
)

func chartTestSettings() Settings {
	s := DefaultSettings()
	s.Digits = 2
	s.Channels[0].Name = "外径"
	s.Channels[0].UseTol = true
	s.Channels[0].LowerMM = 9.0
	s.Channels[0].UpperMM = 11.0
	s.Channels[1].Name = "内径"
	s.Channels[1].UseTol = true
	s.Channels[1].LowerMM = 19.0
	s.Channels[1].UpperMM = 21.0
	return s
}

func chartTestRecords() []protocol.Record {
	return []protocol.Record{
		{T: "08:00:00", ValueUM: 10000, Ch: 1, Verdict: "合格", TS: 1000},
		{T: "08:00:01", ValueUM: 20000, Ch: 2, Verdict: "合格", TS: 2000},
		{T: "08:00:02", ValueUM: 12000, Ch: 1, Verdict: "合格", TS: 3000},
		{T: "08:00:03", ValueUM: 15000, Ch: 2, Verdict: "超差", TS: 4000},
	}
}

func TestBuildChartDataFilterAndStats(t *testing.T) {
	s := chartTestSettings()
	recs := chartTestRecords()

	all := BuildChartData(recs, s, "")
	if len(all.Points) != 4 {
		t.Fatalf("all points = %d, want 4", len(all.Points))
	}
	if all.Kind != ChartLine || all.UnitLabel != "mm" || all.Digits != 2 {
		t.Errorf("meta = %+v", all)
	}
	mean := (10.0 + 20.0 + 12.0 + 15.0) / 4
	if math.Abs(all.Points[0].MeanMM-mean) > 1e-9 {
		t.Errorf("mean = %f, want %f", all.Points[0].MeanMM, mean)
	}
	if all.Points[0].LowerMM != 9 || all.Points[0].UpperMM != 11 {
		t.Errorf("ch1 bounds = %f/%f", all.Points[0].LowerMM, all.Points[0].UpperMM)
	}
	if all.Points[1].LowerMM != 19 || all.Points[1].UpperMM != 21 {
		t.Errorf("ch2 bounds = %f/%f", all.Points[1].LowerMM, all.Points[1].UpperMM)
	}
	if all.Points[3].Out != true {
		t.Error("15mm on CH2 (19..21) must be 超差")
	}
	if all.Points[0].Out != false {
		t.Error("10mm on CH1 (9..11) must be 合格")
	}
	if all.Points[0].ChName != "外径" || all.Points[1].ChName != "内径" {
		t.Errorf("channel names = %q / %q", all.Points[0].ChName, all.Points[1].ChName)
	}

	only1 := BuildChartData(recs, s, "CH1")
	if len(only1.Points) != 2 {
		t.Fatalf("CH1 points = %d, want 2", len(only1.Points))
	}
	if only1.Points[0].Ch != 1 || only1.Points[1].Ch != 1 {
		t.Error("filter leaked other channels")
	}
	wantMean := (10.0 + 12.0) / 2
	if math.Abs(only1.Points[0].MeanMM-wantMean) > 1e-9 {
		t.Errorf("filtered mean = %f, want %f", only1.Points[0].MeanMM, wantMean)
	}

	empty := BuildChartData(recs, s, "CH7")
	if len(empty.Points) != 0 {
		t.Errorf("empty filter should yield 0 points, got %d", len(empty.Points))
	}
}

func TestBuildChartDataUsesChannelBoundsForOut(t *testing.T) {
	s := chartTestSettings()
	// 10mm is inside CH1 (9..11) but outside CH3 (global 9.95..10.05 is fine too)
	recs := []protocol.Record{{T: "09:00:00", ValueUM: 11000, Ch: 1}, {T: "09:00:01", ValueUM: 11000, Ch: 3}}
	d := BuildChartData(recs, s, "")
	if d.Points[0].Out {
		t.Error("11mm on CH1 upper edge should be 合格")
	}
	// CH3 falls back to global [9.95, 10.05] => 11mm is 超差
	if !d.Points[1].Out {
		t.Error("11mm on CH3 (global tol) should be 超差")
	}
	if d.Points[1].ChName != "CH3" {
		t.Errorf("unconfigured channel name = %q", d.Points[1].ChName)
	}
}

func TestComputeYRange(t *testing.T) {
	pts := []ChartPoint{
		{ValueMM: 10, LowerMM: 9, UpperMM: 11},
		{ValueMM: 20, LowerMM: 19, UpperMM: 21},
	}
	r := ComputeYRange(pts)
	if r.Min >= 9 || r.Max <= 21 {
		t.Errorf("range must cover tolerance lines with padding: %+v", r)
	}
	if len(r.Ticks) != 5 {
		t.Fatalf("ticks = %d, want 5", len(r.Ticks))
	}
	for i := 1; i < len(r.Ticks); i++ {
		if r.Ticks[i] <= r.Ticks[i-1] {
			t.Errorf("ticks not ascending: %v", r.Ticks)
		}
	}
	// degenerate (all equal) range must still be valid
	deg := ComputeYRange([]ChartPoint{{ValueMM: 5, LowerMM: 5, UpperMM: 5}})
	if deg.Max <= deg.Min {
		t.Errorf("degenerate range invalid: %+v", deg)
	}
	// empty range
	er := ComputeYRange(nil)
	if er.Min != 0 || er.Max != 1 || len(er.Ticks) != 5 {
		t.Errorf("empty range = %+v", er)
	}
}

func TestFitYAndFitX(t *testing.T) {
	r := AxisRange{Min: 0, Max: 100, Ticks: []float64{0, 25, 50, 75, 100}}
	if got := FitY(0, r, 10, 110); got != 110 {
		t.Errorf("FitY(min) = %d", got)
	}
	if got := FitY(100, r, 10, 110); got != 10 {
		t.Errorf("FitY(max) = %d", got)
	}
	if got := FitY(50, r, 10, 110); got != 60 {
		t.Errorf("FitY(mid) = %d", got)
	}
	if got := FitY(150, r, 10, 110); got != 10 {
		t.Errorf("FitY clamped high = %d", got)
	}
	if got := FitX(0, 3, 0, 100); got != 0 {
		t.Errorf("FitX(0) = %d", got)
	}
	if got := FitX(2, 3, 0, 100); got != 100 {
		t.Errorf("FitX(last) = %d", got)
	}
	if got := FitX(1, 3, 0, 100); got != 50 {
		t.Errorf("FitX(mid) = %d", got)
	}
	if got := FitX(0, 1, 0, 100); got != 50 {
		t.Errorf("FitX(single) = %d", got)
	}
}
