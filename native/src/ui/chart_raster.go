//go:build windows

package ui

import (
	"image"
	"image/color"
	"strconv"
	"strings"

	"glcaliper/core"
)

// Pure-Go rasterisation fallback for PNG export. It uses only image.RGBA
// pixel writes and the pure chart math in package core — no GDI readback — so
// a PNG is always produced even if every GDI path fails in a locked-down GUI
// session. Labels are ASCII (a 5x7 bitmap font) because no font dependency
// may be added; Chinese channel names are rendered as their CHn token.

var rasterFont = map[rune][7]string{
	' ': {".....", ".....", ".....", ".....", ".....", ".....", "....."},
	'0': {".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."},
	'1': {"..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."},
	'2': {".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"},
	'3': {"####.", "....#", "....#", ".###.", "....#", "....#", "####."},
	'4': {"...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."},
	'5': {"#####", "#....", "####.", "....#", "....#", "#...#", ".###."},
	'6': {"..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."},
	'7': {"#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."},
	'8': {".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."},
	'9': {".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."},
	':': {".....", "..#..", "..#..", ".....", "..#..", "..#..", "....."},
	'.': {".....", ".....", ".....", ".....", ".....", ".##..", ".##.."},
	'-': {".....", ".....", ".....", "#####", ".....", ".....", "....."},
	'(': {"...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#."},
	')': {".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#..."},
	'/': {"....#", "....#", "...#.", "..#..", ".#...", "#....", "#...."},
	'?': {".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."},
	'A': {".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"},
	'B': {"####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."},
	'C': {".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."},
	'D': {"####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."},
	'E': {"#####", "#....", "#....", "####.", "#....", "#....", "#####"},
	'F': {"#####", "#....", "#....", "####.", "#....", "#....", "#...."},
	'G': {".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."},
	'H': {"#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"},
	'I': {".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."},
	'J': {"..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."},
	'K': {"#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"},
	'L': {"#....", "#....", "#....", "#....", "#....", "#....", "#####"},
	'M': {"#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"},
	'N': {"#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"},
	'O': {".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."},
	'P': {"####.", "#...#", "#...#", "####.", "#....", "#....", "#...."},
	'Q': {".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"},
	'R': {"####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"},
	'S': {".####", "#....", "#....", ".###.", "....#", "....#", "####."},
	'T': {"#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."},
	'U': {"#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."},
	'V': {"#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."},
	'W': {"#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"},
	'X': {"#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"},
	'Y': {"#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."},
	'Z': {"#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"},
}

var (
	rasterInk    = color.RGBA{R: chInkR, G: chInkG, B: chInkB, A: 255}
	rasterSub    = color.RGBA{R: chSubR, G: chSubG, B: chSubB, A: 255}
	rasterGrid   = color.RGBA{R: chGridR, G: chGridG, B: chGridB, A: 255}
	rasterAxis   = color.RGBA{R: chAxisR, G: chAxisG, B: chAxisB, A: 255}
	rasterStroke = color.RGBA{R: chLineR, G: chLineG, B: chLineB, A: 255}
	rasterTol    = color.RGBA{R: chTolR, G: chTolG, B: chTolB, A: 255}
	rasterMean   = color.RGBA{R: chMeanR, G: chMeanG, B: chMeanB, A: 255}
	rasterBad    = color.RGBA{R: chBadR, G: chBadG, B: chBadB, A: 255}
	rasterWhite  = color.RGBA{R: chWhiteR, G: chWhiteG, B: chWhiteB, A: 255}
	// New palette-derived colours used by the modernised renderer.
	rasterPlot   = color.RGBA{R: chPlotR, G: chPlotG, B: chPlotB, A: 255}
	rasterBand   = color.RGBA{R: chBandR, G: chBandG, B: chBandB, A: 255}
	rasterArea   = color.RGBA{R: chAreaR, G: chAreaG, B: chAreaB, A: 255}
	rasterBarTop = color.RGBA{R: chBarTopR, G: chBarTopG, B: chBarTopB, A: 255}
	rasterBadTop = color.RGBA{R: chBadTopR, G: chBadTopG, B: chBadTopB, A: 255}
)

func rasterFillRect(img *image.RGBA, x0, y0, x1, y1 int, c color.RGBA) {
	b := img.Bounds()
	for y := y0; y <= y1; y++ {
		if y < b.Min.Y || y >= b.Max.Y {
			continue
		}
		for x := x0; x <= x1; x++ {
			if x < b.Min.X || x >= b.Max.X {
				continue
			}
			img.SetRGBA(x, y, c)
		}
	}
}

func rasterLine(img *image.RGBA, x0, y0, x1, y1 int, c color.RGBA, dashed bool) {
	dx, dy := x1-x0, y1-y0
	if dx == 0 && dy == 0 {
		img.SetRGBA(x0, y0, c)
		return
	}
	steps := dx
	if dy > steps {
		steps = dy
	}
	if -dx > steps {
		steps = -dx
	}
	if -dy > steps {
		steps = -dy
	}
	for i := 0; i <= steps; i++ {
		if dashed && (i/6)%2 == 1 {
			continue
		}
		x := x0
		if dx != 0 {
			x = x0 + i*dx/steps
		}
		y := y0
		if dy != 0 {
			y = y0 + i*dy/steps
		}
		img.SetRGBA(x, y, c)
	}
}

func rasterDisc(img *image.RGBA, cx, cy, r int, c color.RGBA) {
	for y := cy - r; y <= cy+r; y++ {
		for x := cx - r; x <= cx+r; x++ {
			dx, dy := x-cx, y-cy
			if dx*dx+dy*dy <= r*r {
				img.SetRGBA(x, y, c)
			}
		}
	}
}

// rasterRing draws a hollow data point: a coloured outer ring with a white
// centre, matching the on-screen marker so dense series stay legible.
func rasterRing(img *image.RGBA, cx, cy, r int, ring, center color.RGBA) {
	rasterDisc(img, cx, cy, r, ring)
	rasterDisc(img, cx, cy, r-2, center)
}

// rasterVGradient fills a rectangle with a vertical two-colour gradient:
// top at y0, bottom at y1. Replaces GDI GradientFillRectangle in the PNG path.
func rasterVGradient(img *image.RGBA, x0, y0, x1, y1 int, top, bottom color.RGBA) {
	if y1 <= y0 {
		y1 = y0 + 1
	}
	span := float64(y1 - y0)
	for y := y0; y <= y1; y++ {
		t := float64(y-y0) / span
		c := color.RGBA{
			R: lerpByte(top.R, bottom.R, t),
			G: lerpByte(top.G, bottom.G, t),
			B: lerpByte(top.B, bottom.B, t),
			A: 255,
		}
		for x := x0; x <= x1; x++ {
			if img.Bounds().Min.X <= x && x < img.Bounds().Max.X &&
				img.Bounds().Min.Y <= y && y < img.Bounds().Max.Y {
				img.SetRGBA(x, y, c)
			}
		}
	}
}

func lerpByte(a, b uint8, t float64) uint8 {
	return uint8(int(a) + int(float64(int(b)-int(a))*t))
}

func rasterText(img *image.RGBA, x, y int, text string, c color.RGBA, scale int) {
	if scale < 1 {
		scale = 1
	}
	cx := x
	for _, r := range text {
		r = toASCII(r)
		rows, ok := rasterFont[r]
		if !ok {
			rows = rasterFont['?']
		}
		for ry, row := range rows {
			for rx := 0; rx < 5; rx++ {
				if row[rx] == '#' {
					rasterFillRect(img, cx+rx*scale, y+ry*scale,
						cx+rx*scale+scale-1, y+ry*scale+scale-1, c)
				}
			}
		}
		cx += 6 * scale
	}
}

// toASCII maps runes outside the tiny font to a safe ASCII fallback.
func toASCII(r rune) rune {
	if r >= 'a' && r <= 'z' {
		return r - 'a' + 'A'
	}
	if _, ok := rasterFont[r]; ok {
		return r
	}
	return '?'
}

// asciiChartTitle builds a deterministic ASCII caption. Chinese channel
// labels are replaced by an explicit ALL/CHn token; the embedded 5x7 font
// cannot render CJK and no font dependency may be added.
func asciiChartTitle(data core.ChartData) string {
	kind := "LINE"
	if data.Kind == core.ChartBar {
		kind = "BAR"
	}
	scope := "ALL"
	if !strings.Contains(data.ChLabel, "全部") {
		for _, r := range data.ChLabel {
			if r >= 0x20 && r <= 0x7E {
				scope = strings.TrimSpace(asciiOnly(data.ChLabel))
				break
			}
		}
	}
	if scope == "" {
		scope = "ALL"
	}
	return kind + " " + scope
}

// rasterDashedLine is the dashed-line primitive required by the tolerance
// and mean reference lines; it delegates to the shared Bresenham routine.
func rasterDashedLine(img *image.RGBA, x0, y0, x1, y1 int, c color.RGBA) {
	rasterLine(img, x0, y0, x1, y1, c, true)
}

// drawLegend paints small colour swatches with ASCII captions so every
// rendered series remains identifiable without Chinese glyphs.
func drawLegend(img *image.RGBA, x, y int) {
	items := []struct {
		c    color.RGBA
		text string
	}{
		{rasterStroke, "VALUE"},
		{rasterTol, "TOL"},
		{rasterMean, "MEAN"},
		{rasterBad, "BAD"},
	}
	for _, it := range items {
		rasterFillRect(img, x, y+2, x+11, y+10, it.c)
		rasterText(img, x+15, y+1, it.text, rasterSub, 1)
		x += 15 + len(it.text)*6 + 10
	}
}

// renderChartPureGo rasterises ChartData into a valid PNG-ready image without
// any GDI or font dependency. Scaling reuses the same core pure functions as
// the on-screen chart, keeping geometry consistent.
func renderChartPureGo(data core.ChartData, width, height int) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, width, height))
	rasterFillRect(img, 0, 0, width-1, height-1, rasterWhite)
	if width < 160 || height < 120 {
		rasterText(img, 8, 8, "CHART TOO SMALL", rasterSub, 1)
		return img
	}

	left, right, top, bottom := 48, 62, 42, 30
	pl, pr := left, width-right
	pt, pb := top, height-bottom
	rasterText(img, pl, 4, asciiChartTitle(data), rasterInk, 2)
	drawLegend(img, pl, 24)

	if len(data.Points) == 0 {
		rasterText(img, pl+10, pt+20, "NO DATA", rasterSub, 2)
		return img
	}

	// Plot-area card background (pale blue), matching the on-screen surface.
	rasterFillRect(img, pl, pt, pr, pb, rasterPlot)

	r := core.ComputeYRange(data.Points)
	for _, tick := range r.Ticks {
		y := core.FitY(tick, r, pt, pb)
		rasterLine(img, pl, y, pr, y, rasterGrid, false)
		label := strconv.FormatFloat(tick, 'f', data.Digits, 64)
		rasterText(img, pr+5, y-6, label, rasterSub, 1)
	}
	rasterText(img, pr+5, pt-16, "mm", rasterSub, 1)
	// axes
	rasterLine(img, pl, pt, pl, pb, rasterAxis, false)
	rasterLine(img, pl, pb, pr, pb, rasterAxis, false)
	rasterLine(img, pr, pt, pr, pb, rasterAxis, false)

	n := len(data.Points)

	// tolerance band fill + dashed edges
	for i := 0; i+1 < n; i++ {
		x0, x1 := core.FitX(i, n, pl, pr), core.FitX(i+1, n, pl, pr)
		u0 := core.FitY(data.Points[i].UpperMM, r, pt, pb)
		u1 := core.FitY(data.Points[i+1].UpperMM, r, pt, pb)
		l0 := core.FitY(data.Points[i].LowerMM, r, pt, pb)
		l1 := core.FitY(data.Points[i+1].LowerMM, r, pt, pb)
		// fill each column between the interpolated upper/lower bounds
		lo, hi := u0, l0
		if hi < lo {
			lo, hi = hi, lo
		}
		rasterFillRect(img, x0, lo, x1, hi, rasterBand)
		rasterDashedLine(img, x0, u0, x1, u1, rasterTol)
		rasterDashedLine(img, x0, l0, x1, l1, rasterTol)
	}
	// mean dashed line
	my := core.FitY(data.Points[0].MeanMM, r, pt, pb)
	rasterDashedLine(img, pl, my, pr, my, rasterMean)

	if data.Kind == core.ChartBar {
		slotW := float64(pr-pl) / float64(n)
		barW := int(slotW * 0.68)
		if barW < 2 {
			barW = 2
		}
		if barW > 22 {
			barW = 22
		}
		for i, p := range data.Points {
			x := core.FitX(i, n, pl, pr)
			y := core.FitY(p.ValueMM, r, pt, pb)
			topC, botC := rasterBarTop, rasterStroke
			if p.Out {
				topC, botC = rasterBadTop, rasterBad
			}
			rasterVGradient(img, x-barW/2, y, x+barW/2, pb, topC, botC)
		}
	} else {
		// pale area under the curve
		if n >= 2 {
			for x := pl; x <= pr; x++ {
				pos := float64(x-pl) / float64(pr-pl) * float64(n-1)
				i0 := int(pos)
				if i0 < 0 {
					i0 = 0
				}
				if i0 > n-2 {
					i0 = n - 2
				}
				t := pos - float64(i0)
				y0 := core.FitY(data.Points[i0].ValueMM, r, pt, pb)
				y1 := core.FitY(data.Points[i0+1].ValueMM, r, pt, pb)
				yy := y0 + int(float64(y1-y0)*t)
				if yy < pb {
					rasterFillRect(img, x, yy, x, pb, rasterArea)
				}
			}
		}
		// thicker series line: draw the polyline at two adjacent offsets
		for i := 0; i+1 < n; i++ {
			x0, x1 := core.FitX(i, n, pl, pr), core.FitX(i+1, n, pl, pr)
			y0 := core.FitY(data.Points[i].ValueMM, r, pt, pb)
			y1 := core.FitY(data.Points[i+1].ValueMM, r, pt, pb)
			rasterLine(img, x0, y0, x1, y1, rasterStroke, false)
			rasterLine(img, x0, y0+1, x1, y1+1, rasterStroke, false)
		}
		for i, p := range data.Points {
			x, y := core.FitX(i, n, pl, pr), core.FitY(p.ValueMM, r, pt, pb)
			if p.Out {
				rasterDisc(img, x, y, 4, rasterBad)
			} else {
				rasterRing(img, x, y, 4, rasterStroke, rasterWhite)
			}
		}
	}

	// value labels above each sampled point / bar top. The sampling and
	// placement are shared with paintChart so the PNG matches the screen.
	bounds := labelBounds{Left: pl, Right: pr, Top: pt, Bottom: pb}
	for _, i := range valueLabelIndices(data.Points, data.Digits, pr-pl) {
		p := data.Points[i]
		x, y := core.FitX(i, n, pl, pr), core.FitY(p.ValueMM, r, pt, pb)
		label := chartValueLabel(p.ValueMM, data.Digits)
		labelW := len(label) * rasterCharCellWidth
		left, top := valueLabelBox(x, y, bounds, labelW, rasterTextHeight, 3)
		c := rasterInk
		if p.Out {
			c = rasterBad
		}
		rasterText(img, left, top, label, c, 1)
	}

	// time ticks (at most 6)
	step := (n - 1) / 5
	if step < 1 {
		step = 1
	}
	for i := 0; i < n; i += step {
		x := core.FitX(i, n, pl, pr)
		rasterText(img, x-24, pb+5, asciiOnly(data.Points[i].TimeLabel), rasterSub, 1)
	}
	return img
}

func asciiOnly(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= 0x20 && r <= 0x7E {
			b.WriteRune(r)
		}
	}
	return b.String()
}
