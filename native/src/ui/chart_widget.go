//go:build windows

package ui

import (
	"strconv"

	"github.com/lxn/walk"
	"glcaliper/core"
)

// chartLayout holds the pixel geometry used by both screen painting and the
// off-screen PNG export, keeping the two outputs identical.
type chartLayout struct {
	bounds                     walk.Rectangle
	left, right, top, bottom   int
	plotL, plotR, plotT, plotB int
}

func newChartLayout(b walk.Rectangle) chartLayout {
	l := chartLayout{bounds: b, left: 48, right: 62, top: 42, bottom: 30}
	l.plotL = b.X + l.left
	l.plotR = b.X + b.Width - l.right
	l.plotT = b.Y + l.top
	l.plotB = b.Y + b.Height - l.bottom
	return l
}

func (l chartLayout) valid() bool { return l.plotR > l.plotL && l.plotB > l.plotT }

// paintChart renders the current ChartData snapshot onto any walk Canvas.
// It is deliberately shared by the on-screen CustomWidget and the off-screen
// PNG export so the exported image is exactly what the user sees.
func (u *UI) paintChart(canvas *walk.Canvas, bounds walk.Rectangle) error {
	if canvas == nil || bounds.Width < 80 || bounds.Height < 80 {
		return nil
	}
	l := newChartLayout(bounds)
	if !l.valid() {
		return nil
	}

	// ---- GDI resources (all disposed before returning) ----
	bgBrush, err := walk.NewSolidColorBrush(walk.RGB(chWhiteR, chWhiteG, chWhiteB))
	if err != nil {
		return err
	}
	defer bgBrush.Dispose()
	plotBrush, err := walk.NewSolidColorBrush(walk.RGB(chPlotR, chPlotG, chPlotB))
	if err != nil {
		return err
	}
	defer plotBrush.Dispose()
	gridPen, err := walk.NewCosmeticPen(walk.PenSolid, walk.RGB(chGridR, chGridG, chGridB))
	if err != nil {
		return err
	}
	defer gridPen.Dispose()
	axisPen, err := walk.NewCosmeticPen(walk.PenSolid, walk.RGB(chAxisR, chAxisG, chAxisB))
	if err != nil {
		return err
	}
	defer axisPen.Dispose()
	linePen, err := walk.NewCosmeticPen(walk.PenSolid, walk.RGB(chLineR, chLineG, chLineB))
	if err != nil {
		return err
	}
	defer linePen.Dispose()
	// A second solid pen offset by one pixel builds a crisp 2px line using
	// only cosmetic pens (geometric pens are unreliable on some Win GDI builds).
	linePen2, err := walk.NewCosmeticPen(walk.PenSolid, walk.RGB(chLineR, chLineG, chLineB))
	if err != nil {
		return err
	}
	defer linePen2.Dispose()
	badPen, err := walk.NewCosmeticPen(walk.PenSolid, walk.RGB(chBadR, chBadG, chBadB))
	if err != nil {
		return err
	}
	defer badPen.Dispose()
	areaBrush, err := walk.NewSolidColorBrush(walk.RGB(chAreaR, chAreaG, chAreaB))
	if err != nil {
		return err
	}
	defer areaBrush.Dispose()
	bandBrush, err := walk.NewSolidColorBrush(walk.RGB(chBandR, chBandG, chBandB))
	if err != nil {
		return err
	}
	defer bandBrush.Dispose()
	tolPen, err := walk.NewCosmeticPen(walk.PenDash, walk.RGB(chTolR, chTolG, chTolB)) // amber dashed
	if err != nil {
		return err
	}
	defer tolPen.Dispose()
	meanPen, err := walk.NewCosmeticPen(walk.PenDash, walk.RGB(chMeanR, chMeanG, chMeanB))
	if err != nil {
		return err
	}
	defer meanPen.Dispose()
	goodBrush, err := walk.NewSolidColorBrush(walk.RGB(chLineR, chLineG, chLineB))
	if err != nil {
		return err
	}
	defer goodBrush.Dispose()
	barTopBrush, err := walk.NewSolidColorBrush(walk.RGB(chBarTopR, chBarTopG, chBarTopB))
	if err != nil {
		return err
	}
	defer barTopBrush.Dispose()
	badTopBrush, err := walk.NewSolidColorBrush(walk.RGB(chBadTopR, chBadTopG, chBadTopB))
	if err != nil {
		return err
	}
	defer badTopBrush.Dispose()
	whiteDotBrush, err := walk.NewSolidColorBrush(walk.RGB(chWhiteR, chWhiteG, chWhiteB))
	if err != nil {
		return err
	}
	defer whiteDotBrush.Dispose()
	badBrush, err := walk.NewSolidColorBrush(walk.RGB(chBadR, chBadG, chBadB)) // 超差点红色
	if err != nil {
		return err
	}
	defer badBrush.Dispose()
	fontTick, err := walk.NewFont("Consolas", 8, 0)
	if err != nil {
		return err
	}
	defer fontTick.Dispose()
	fontText, err := walk.NewFont("Microsoft YaHei", 9, 0)
	if err != nil {
		return err
	}
	defer fontText.Dispose()
	fontBold, err := walk.NewFont("Microsoft YaHei", 10, walk.FontBold)
	if err != nil {
		return err
	}
	defer fontBold.Dispose()

	if err := canvas.FillRectangle(bgBrush, bounds); err != nil {
		return err
	}

	data := u.chartData

	// Plot-area card: a very pale blue panel with a subtle frame makes the
	// data region read as a single surface instead of floating on white.
	plotRect := walk.Rectangle{X: l.plotL, Y: l.plotT, Width: l.plotR - l.plotL, Height: l.plotB - l.plotT}
	_ = canvas.FillRoundedRectangle(plotBrush, plotRect, walk.Size{Width: 6, Height: 6})

	// ---- title & legend ----
	_ = canvas.DrawText(data.ChLabel+" · "+chartKindName(data.Kind), fontBold,
		walk.RGB(chInkR, chInkG, chInkB),
		walk.Rectangle{X: l.plotL, Y: bounds.Y + 4, Width: l.plotR - l.plotL, Height: 18},
		walk.TextSingleLine)
	legendY := bounds.Y + 24
	lx := l.plotL
	legend := []struct {
		brush *walk.SolidColorBrush
		pen   walk.Pen
		text  string
	}{
		{goodBrush, linePen, "实测"},
		{goodBrush, tolPen, "公差上下限"},
		{goodBrush, meanPen, "均值"},
		{badBrush, nil, "超差"},
	}
	for _, item := range legend {
		if item.pen != nil {
			_ = canvas.DrawLine(item.pen, walk.Point{X: lx, Y: legendY + 6}, walk.Point{X: lx + 16, Y: legendY + 6})
		} else {
			_ = canvas.FillRoundedRectangle(item.brush, walk.Rectangle{X: lx, Y: legendY + 2, Width: 12, Height: 9}, walk.Size{Width: 3, Height: 3})
		}
		_ = canvas.DrawText(item.text, fontText, walk.RGB(chSubR, chSubG, chSubB),
			walk.Rectangle{X: lx + 20, Y: legendY, Width: 76, Height: 14}, walk.TextSingleLine|walk.TextVCenter)
		lx += 90
	}

	if len(data.Points) == 0 {
		_ = canvas.DrawText("暂无数据：开始采集或导入记录后显示图表", fontText, colorSub,
			walk.Rectangle{X: l.plotL, Y: l.plotT, Width: l.plotR - l.plotL, Height: l.plotB - l.plotT},
			walk.TextCenter|walk.TextVCenter|walk.TextSingleLine)
		return nil
	}

	r := core.ComputeYRange(data.Points)
	// ---- horizontal grid + right-side mm ticks ----
	for _, tick := range r.Ticks {
		y := core.FitY(tick, r, l.plotT, l.plotB)
		_ = canvas.DrawLine(gridPen, walk.Point{X: l.plotL, Y: y}, walk.Point{X: l.plotR, Y: y})
		label := strconv.FormatFloat(tick, 'f', data.Digits, 64)
		_ = canvas.DrawText(label, fontTick, walk.RGB(chSubR, chSubG, chSubB),
			walk.Rectangle{X: l.plotR + 5, Y: y - 7, Width: l.right - 9, Height: 14},
			walk.TextRight|walk.TextSingleLine|walk.TextVCenter)
	}
	_ = canvas.DrawText("mm", fontTick, walk.RGB(chSubR, chSubG, chSubB),
		walk.Rectangle{X: l.plotR + 5, Y: l.plotT - 18, Width: l.right - 9, Height: 14},
		walk.TextRight|walk.TextSingleLine)
	_ = canvas.DrawRectangle(axisPen, plotRect)

	n := len(data.Points)

	// ---- tolerance band: soft amber fill between the per-point bounds ----
	if n == 1 {
		p := data.Points[0]
		x := core.FitX(0, n, l.plotL, l.plotR)
		yU := core.FitY(p.UpperMM, r, l.plotT, l.plotB)
		yL := core.FitY(p.LowerMM, r, l.plotT, l.plotB)
		_ = canvas.FillRectangle(bandBrush, walk.Rectangle{X: x - 14, Y: yU, Width: 28, Height: yL - yU})
	} else {
		upper := make([]walk.Point, 0, n)
		lower := make([]walk.Point, 0, n)
		for i, p := range data.Points {
			x := core.FitX(i, n, l.plotL, l.plotR)
			upper = append(upper, walk.Point{X: x, Y: core.FitY(p.UpperMM, r, l.plotT, l.plotB)})
			lower = append(lower, walk.Point{X: x, Y: core.FitY(p.LowerMM, r, l.plotT, l.plotB)})
		}
		// Fill the band column by column (GDI has no polygon fill via walk).
		for i := 0; i < len(upper)-1; i++ {
			x0, x1 := upper[i].X, upper[i+1].X
			if x1 <= x0 {
				x1 = x0 + 1
			}
			top := upper[i].Y
			bot := lower[i].Y
			if bot < top {
				top, bot = bot, top
			}
			_ = canvas.FillRectangle(bandBrush, walk.Rectangle{X: x0, Y: top, Width: x1 - x0 + 1, Height: bot - top})
		}
		_ = canvas.DrawPolyline(tolPen, upper)
		_ = canvas.DrawPolyline(tolPen, lower)
	}

	// ---- mean dashed line ----
	meanY := core.FitY(data.Points[0].MeanMM, r, l.plotT, l.plotB)
	_ = canvas.DrawLine(meanPen, walk.Point{X: l.plotL, Y: meanY}, walk.Point{X: l.plotR, Y: meanY})

	// ---- data series ----
	switch data.Kind {
	case core.ChartBar:
		slotW := float64(l.plotR-l.plotL) / float64(n)
		barW := int(slotW * 0.68) // leave breathing room between bars
		if barW < 2 {
			barW = 2
		}
		if barW > 22 {
			barW = 22
		}
		base := l.plotB
		for i, p := range data.Points {
			x := core.FitX(i, n, l.plotL, l.plotR)
			y := core.FitY(p.ValueMM, r, l.plotT, l.plotB)
			h := base - y
			if h < 1 {
				h = 1
			}
			// Vertical gradient simulated by two stacked solid bands:
			// a lighter top third over a saturated base. FillRectangle is
			// reliable on all target GDI builds; GradientFillRectangle is not.
			topBr := barTopBrush
			baseBr := goodBrush
			if p.Out {
				topBr = badTopBrush
				baseBr = badBrush
			}
			third := h / 3
			if third < 1 {
				third = 1
			}
			_ = canvas.FillRectangle(baseBr, walk.Rectangle{X: x - barW/2, Y: y + third, Width: barW, Height: h - third})
			_ = canvas.FillRectangle(topBr, walk.Rectangle{X: x - barW/2, Y: y, Width: barW, Height: third})
			// Rounded-looking cap: an ellipse whose colour matches the top band.
			capH := barW / 2
			if capH > 6 {
				capH = 6
			}
			_ = canvas.FillEllipse(topBr, walk.Rectangle{X: x - barW/2, Y: y - capH/2, Width: barW, Height: capH})
		}
	default: // line
		// Pale blue area under the curve, built from vertical strips.
		if n >= 2 {
			for x := l.plotL; x <= l.plotR; x++ {
				// linear interpolation of the polyline at pixel column x
				pos := float64(x-l.plotL) / float64(l.plotR-l.plotL) * float64(n-1)
				i0 := int(pos)
				if i0 < 0 {
					i0 = 0
				}
				if i0 > n-2 {
					i0 = n - 2
				}
				t := pos - float64(i0)
				y0 := core.FitY(data.Points[i0].ValueMM, r, l.plotT, l.plotB)
				y1 := core.FitY(data.Points[i0+1].ValueMM, r, l.plotT, l.plotB)
				yy := y0 + int(float64(y1-y0)*t)
				if yy > l.plotB {
					yy = l.plotB
				}
				if yy < l.plotB {
					_ = canvas.FillRectangle(areaBrush, walk.Rectangle{X: x, Y: yy, Width: 1, Height: l.plotB - yy})
				}
			}
		}
		if n >= 2 {
			pts := make([]walk.Point, 0, n)
			pts2 := make([]walk.Point, 0, n)
			for i, p := range data.Points {
				px := core.FitX(i, n, l.plotL, l.plotR)
				py := core.FitY(p.ValueMM, r, l.plotT, l.plotB)
				pts = append(pts, walk.Point{X: px, Y: py})
				pts2 = append(pts2, walk.Point{X: px, Y: py + 1})
			}
			// Two overlapping 1px polylines give a 2px stroke without a
			// geometric pen, which is unreliable on some GDI builds.
			_ = canvas.DrawPolyline(linePen, pts)
			_ = canvas.DrawPolyline(linePen2, pts2)
		}
		// Hollow data points: white centre with a coloured ring, so dense
		// points stay legible. Outliers are solid red for emphasis.
		for i, p := range data.Points {
			x := core.FitX(i, n, l.plotL, l.plotR)
			y := core.FitY(p.ValueMM, r, l.plotT, l.plotB)
			dot := walk.Rectangle{X: x - 4, Y: y - 4, Width: 8, Height: 8}
			if p.Out {
				_ = canvas.FillEllipse(badBrush, walk.Rectangle{X: x - 4, Y: y - 4, Width: 9, Height: 9})
				_ = canvas.DrawEllipse(badPen, dot)
			} else {
				_ = canvas.FillEllipse(whiteDotBrush, dot)
				_ = canvas.DrawEllipse(linePen, dot)
			}
		}
	}

	// ---- value labels above each sampled point / bar top ----
	// Same sampling and placement helpers as the PNG exporter: what the user
	// sees on screen is what gets exported.
	labelIdx := valueLabelIndices(data.Points, data.Digits, l.plotR-l.plotL)
	labelRectH := 14
	labelBox := labelBounds{Left: l.plotL, Right: l.plotR, Top: l.plotT, Bottom: l.plotB}
	for _, i := range labelIdx {
		p := data.Points[i]
		x := core.FitX(i, n, l.plotL, l.plotR)
		y := core.FitY(p.ValueMM, r, l.plotT, l.plotB)
		label := chartValueLabel(p.ValueMM, data.Digits)
		// Consolas 8 needs a slightly wider hit rectangle than the 6px
		// raster cell used by the sampling calculation; sampling stays shared.
		labelW := len(label) * (rasterCharCellWidth + 1)
		left, top := valueLabelBox(x, y, labelBox, labelW, labelRectH, 4)
		color := walk.RGB(chInkR, chInkG, chInkB)
		if p.Out {
			color = walk.RGB(chBadR, chBadG, chBadB)
		}
		_ = canvas.DrawText(label, fontTick, color,
			walk.Rectangle{X: left, Y: top, Width: labelW, Height: labelRectH},
			walk.TextCenter|walk.TextVCenter|walk.TextSingleLine)
	}

	// ---- bottom time ticks (sample at most 6 labels) ----
	step := (n - 1) / 5
	if step < 1 {
		step = 1
	}
	for i := 0; i < n; i += step {
		x := core.FitX(i, n, l.plotL, l.plotR)
		_ = canvas.DrawText(data.Points[i].TimeLabel, fontTick, walk.RGB(chSubR, chSubG, chSubB),
			walk.Rectangle{X: x - 30, Y: l.plotB + 4, Width: 60, Height: 14},
			walk.TextCenter|walk.TextSingleLine)
	}
	return nil
}

func chartKindName(k core.ChartKind) string {
	if k == core.ChartBar {
		return "柱状图"
	}
	return "折线图"
}

// refreshChart rebuilds the chart snapshot from the current state and asks
// the widget to repaint. Must be called on the UI thread.
func (u *UI) refreshChart() {
	if u.chartWidget == nil {
		return
	}
	recs := u.app.RecordsSnapshot()
	data := core.BuildChartData(recs, u.app.GetSettings(), u.chartFilter)
	data.Kind = u.chartKind
	u.chartData = data
	_ = u.chartWidget.Invalidate()
	if u.btnChartKind != nil {
		u.btnChartKind.SetText("切换为" + altChartKindName(u.chartKind))
	}
}

func altChartKindName(current core.ChartKind) string {
	if current == core.ChartBar {
		return "折线图"
	}
	return "柱状图"
}

// onChartKindToggle flips line <-> bar. Bound to the analysis-page button.
func (u *UI) onChartKindToggle() {
	if u.chartKind == core.ChartBar {
		u.chartKind = core.ChartLine
	} else {
		u.chartKind = core.ChartBar
	}
	u.refreshChart()
}

// onChartFilterChanged reads the analysis-page channel combo and rebuilds.
func (u *UI) onChartFilterChanged() {
	if u.cmbChartFilter == nil {
		return
	}
	idx := u.cmbChartFilter.CurrentIndex()
	u.chartFilter = ""
	if idx > 0 {
		u.chartFilter = "CH" + strconv.Itoa(idx)
	}
	u.refreshChart()
}
