// Package core chart data model. All tolerance / scaling judgment lives here
// as pure functions so it can be unit tested without any UI / GDI dependency.
package core

import (
	"math"
	"time"

	"glcaliper/protocol"
)

// ChartKind selects the visualisation style.
type ChartKind string

const (
	ChartLine ChartKind = "line"
	ChartBar  ChartKind = "bar"
)

// ChartPoint is one rendered measurement with its resolved tolerance context.
type ChartPoint struct {
	Index     int
	TS        int64
	TimeLabel string
	ValueMM   float64
	LowerMM   float64
	UpperMM   float64
	MeanMM    float64
	Out       bool
	Ch        int
	ChName    string
}

// ChartData is the immutable snapshot consumed by paintChart.
type ChartData struct {
	Kind      ChartKind
	Points    []ChartPoint
	Digits    int
	UnitLabel string
	ChLabel   string
}

// AxisRange describes the right-hand millimetre axis.
type AxisRange struct {
	Min, Max float64
	Ticks    []float64
}

// emptyChartData is returned when there is nothing to draw.
func emptyChartData(kind ChartKind, s Settings, filter string) ChartData {
	label := "全部通道"
	if filter != "" {
		label = filter
	}
	return ChartData{
		Kind: kind, Points: make([]ChartPoint, 0), Digits: s.Digits,
		UnitLabel: "mm", ChLabel: label,
	}
}

// BuildChartData filters records, resolves each record's channel tolerance,
// and computes the mean of the current selection. Values stay in millimetres;
// the right axis is always a millimetre axis regardless of display unit.
func BuildChartData(recs []protocol.Record, s Settings, filter string) ChartData {
	data := emptyChartData(dataKindDefault(), s, filter)
	selected := make([]protocol.Record, 0, len(recs))
	for _, r := range recs {
		if filter == "" || "CH"+itoa(r.Ch) == filter {
			selected = append(selected, r)
		}
	}
	if len(selected) == 0 {
		return data
	}

	sum := float64(0)
	for _, r := range selected {
		sum += protocol.ToMM(r.ValueUM)
	}
	mean := sum / float64(len(selected))

	points := make([]ChartPoint, 0, len(selected))
	for i, r := range selected {
		loUM, hiUM := s.ChannelBounds(r.Ch)
		loMM, hiMM := protocol.ToMM(loUM), protocol.ToMM(hiUM)
		label := r.T
		if label == "" {
			label = timeLabelFromTS(r.TS)
		}
		points = append(points, ChartPoint{
			Index: i, TS: r.TS, TimeLabel: label,
			ValueMM: protocol.ToMM(r.ValueUM),
			LowerMM: loMM, UpperMM: hiMM, MeanMM: mean,
			Out: protocol.Verdict(r.ValueUM, loUM, hiUM) == "超差",
			Ch:  r.Ch, ChName: s.ChannelName(r.Ch),
		})
	}
	data.Points = points
	data.ChLabel = chartSelectionLabel(s, filter, selected)
	return data
}

// dataKindDefault returns the default kind used when the UI has not chosen.
// Kept here (rather than in UI) so BuildChartData is fully deterministic.
func dataKindDefault() ChartKind { return ChartLine }

// itoa is a tiny non-strconv helper to keep this file dependency-light.
func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

// timeLabelFromTS formats unix milliseconds as local HH:MM:SS.
func timeLabelFromTS(ms int64) string {
	if ms <= 0 {
		return "00:00:00"
	}
	t := time.UnixMilli(ms)
	pad := func(n int) string {
		if n < 10 {
			return "0" + itoa(n)
		}
		return itoa(n)
	}
	return pad(t.Hour()) + ":" + pad(t.Minute()) + ":" + pad(t.Second())
}

// chartSelectionLabel builds the caption shown above the chart.
func chartSelectionLabel(s Settings, filter string, selected []protocol.Record) string {
	if filter == "" {
		return "全部通道（" + itoa(len(selected)) + " 条）"
	}
	name := s.ChannelName(1)
	if len(selected) > 0 {
		name = s.ChannelName(selected[0].Ch)
	}
	return filter + " " + name + "（" + itoa(len(selected)) + " 条）"
}

// ComputeYRange covers values, tolerance lines and adds 10% padding, then
// generates exactly 5 evenly spaced tick values.
func ComputeYRange(pts []ChartPoint) AxisRange {
	if len(pts) == 0 {
		return AxisRange{Min: 0, Max: 1, Ticks: []float64{0, 0.25, 0.5, 0.75, 1}}
	}
	minV, maxV := pts[0].ValueMM, pts[0].ValueMM
	include := func(v float64) {
		if math.IsNaN(v) || math.IsInf(v, 0) {
			return
		}
		if v < minV {
			minV = v
		}
		if v > maxV {
			maxV = v
		}
	}
	for _, p := range pts {
		include(p.ValueMM)
		include(p.LowerMM)
		include(p.UpperMM)
	}
	if maxV <= minV {
		center := minV
		if center == 0 {
			center = 1
		}
		minV, maxV = center-math.Abs(center)*0.1, center+math.Abs(center)*0.1
	}
	pad := (maxV - minV) * 0.10
	minV -= pad
	maxV += pad
	ticks := make([]float64, 5)
	for i := range ticks {
		ticks[i] = minV + (maxV-minV)*float64(i)/float64(len(ticks)-1)
	}
	return AxisRange{Min: minV, Max: maxV, Ticks: ticks}
}

// FitY maps a millimetre value to a pixel row (top = Max, bottom = Min).
func FitY(v float64, r AxisRange, topPx, bottomPx int) int {
	if r.Max <= r.Min || math.IsNaN(v) {
		return (topPx + bottomPx) / 2
	}
	t := (v - r.Min) / (r.Max - r.Min)
	y := bottomPx - int(math.Round(t*float64(bottomPx-topPx)))
	if y < topPx {
		return topPx
	}
	if y > bottomPx {
		return bottomPx
	}
	return y
}

// FitX maps point index i (of n) to a pixel column inside [leftPx, rightPx].
func FitX(i, n, leftPx, rightPx int) int {
	if n <= 1 {
		return (leftPx + rightPx) / 2
	}
	if i < 0 {
		i = 0
	}
	if i >= n {
		i = n - 1
	}
	return leftPx + int(math.Round(float64(i)/float64(n-1)*float64(rightPx-leftPx)))
}
