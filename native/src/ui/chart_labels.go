// Package ui: pure helpers shared by the on-screen chart and the pure-Go PNG
// raster exporter. Keeping label formatting, sampling and placement here (with
// no GDI/walk dependency) makes the two render paths WYSIWYG and unit-testable.
package ui

import (
	"strconv"

	"glcaliper/core"
)

const (
	// rasterCharCellWidth is the horizontal advance of rasterText at scale 1:
	// five glyph pixels plus one pixel of spacing.
	rasterCharCellWidth = 6
	// rasterTextHeight is the pixel height of the built-in 5x7 bitmap font.
	rasterTextHeight = 7
)

// Chart palette — the single source of truth for both the on-screen GDI
// painter and the pure-Go PNG rasteriser. Keeping raw RGB triplets here (no
// walk / image dependency) lets both render paths derive the exact same
// colours, so the exported PNG matches what the user sees.
const (
	chInkR, chInkG, chInkB          = 30, 41, 59    // primary text
	chSubR, chSubG, chSubB          = 100, 116, 139 // secondary text / ticks
	chPlotR, chPlotG, chPlotB       = 250, 252, 255 // plot area card background
	chGridR, chGridG, chGridB       = 229, 235, 243 // horizontal grid lines
	chAxisR, chAxisG, chAxisB       = 203, 213, 225 // axis frame
	chBandR, chBandG, chBandB       = 255, 251, 242 // tolerance band fill (whisper-light warm)
	chTolR, chTolG, chTolB          = 232, 158, 40  // tolerance edge lines (amber)
	chMeanR, chMeanG, chMeanB       = 148, 163, 184 // mean reference line
	chLineR, chLineG, chLineB       = 37, 99, 235   // main series (modern blue)
	chAreaR, chAreaG, chAreaB       = 219, 232, 254 // line area fill (pale blue)
	chBarTopR, chBarTopG, chBarTopB = 96, 165, 250  // bar gradient top
	chBadR, chBadG, chBadB          = 225, 70, 85   // out-of-tolerance red
	chBadTopR, chBadTopG, chBadTopB = 248, 113, 121 // bad bar gradient top
	chWhiteR, chWhiteG, chWhiteB    = 255, 255, 255
)

// labelBounds is the plot rectangle that a value label must stay inside.
type labelBounds struct {
	Left, Right, Top, Bottom int
}

// chartValueLabel formats a point value with the same decimal precision as
// the Y-axis ticks, so the annotation always agrees with the axis it sits on.
func chartValueLabel(value float64, digits int) string {
	return strconv.FormatFloat(value, 'f', digits, 64)
}

// valueLabelIndices returns the indices of points that should carry a value
// label. Both the screen painter and the PNG rasteriser call it with the same
// plot width, so dense series are sampled identically in both outputs.
//
// Each point owns about plotWidth/n horizontal pixels. A label needs roughly
// len(label)*6 pixels. When the two do not fit (spacing < labelWidth+4), the
// step becomes ceil((labelWidth+6)/spacing). The first and the last point are
// always labelled; if the final sampled index would sit too close to the last
// point, it is replaced by the last point to avoid overlapping text.
func valueLabelIndices(points []core.ChartPoint, digits, plotWidth int) []int {
	n := len(points)
	if n == 0 || plotWidth <= 0 {
		return nil
	}

	maxChars := 0
	for _, p := range points {
		if chars := len(chartValueLabel(p.ValueMM, digits)); chars > maxChars {
			maxChars = chars
		}
	}
	if maxChars == 0 {
		return nil
	}

	labelWidth := maxChars * rasterCharCellWidth
	spacing := plotWidth / n
	if spacing < 1 {
		spacing = 1
	}
	step := 1
	if spacing < labelWidth+4 {
		step = (labelWidth + 6 + spacing - 1) / spacing
		if step < 1 {
			step = 1
		}
	}

	indices := make([]int, 0, n/step+2)
	for i := 0; i < n; i += step {
		indices = append(indices, i)
	}
	last := n - 1
	if indices[len(indices)-1] != last {
		if len(indices) > 1 && last-indices[len(indices)-1] < step {
			indices[len(indices)-1] = last
		} else {
			indices = append(indices, last)
		}
	}
	return indices
}

// valueLabelBox returns the top-left corner of a label centred on x and placed
// gap pixels above y. When there is no headroom inside the plot the label is
// flipped below the point; both axes are clamped so text never spills into the
// title or time-axis areas.
func valueLabelBox(x, y int, b labelBounds, labelWidth, labelHeight, gap int) (left, top int) {
	if labelWidth < 1 {
		labelWidth = 1
	}
	if labelHeight < 1 {
		labelHeight = 1
	}

	left = x - labelWidth/2
	if left < b.Left {
		left = b.Left
	}
	if left+labelWidth > b.Right {
		left = b.Right - labelWidth
	}
	if left < b.Left {
		left = b.Left
	}

	top = y - gap - labelHeight
	if top < b.Top {
		top = y + gap
	}
	if top < b.Top {
		top = b.Top
	}
	if top+labelHeight > b.Bottom {
		top = b.Bottom - labelHeight
	}
	if top < b.Top {
		top = b.Top
	}
	return left, top
}
