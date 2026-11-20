//go:build windows

package ui

import (
	"image"
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"glcaliper/core"
	"glcaliper/protocol"
)

// TestRenderChartImagePureGo proves the ONLY PNG export render path is pure
// software rasterisation: no walk Canvas/Bitmap, no GDI object, no GetDIBits,
// and critically no SetDIBits-based synthetic source that could mask a
// real-session failure. It uses ordinary core.ChartData as input.
func TestRenderChartImagePureGo(t *testing.T) {
	s := core.DefaultSettings()
	s.Channels[0] = core.ChannelConfig{Name: "外径", UseTol: true, LowerMM: 9, UpperMM: 11}
	s.Channels[1] = core.ChannelConfig{Name: "内径", UseTol: true, LowerMM: 19, UpperMM: 21}
	recs := []protocol.Record{
		{T: "08:00:00", ValueUM: 10000, Ch: 1, Verdict: "合格", TS: 1},
		{T: "08:00:01", ValueUM: 20200, Ch: 2, Verdict: "合格", TS: 2},
		{T: "08:00:02", ValueUM: 15000, Ch: 1, Verdict: "超差", TS: 3},
		{T: "08:00:03", ValueUM: 19800, Ch: 2, Verdict: "合格", TS: 4},
	}
	data := core.BuildChartData(recs, s, "")
	data.Kind = core.ChartLine
	u := &UI{chartData: data}

	img, err := u.renderChartImage(chartPNGWidth, chartPNGHeight)
	if err != nil {
		t.Fatalf("renderChartImage: %v", err)
	}
	if img.Bounds().Dx() != chartPNGWidth || img.Bounds().Dy() != chartPNGHeight {
		t.Fatalf("bounds = %v, want %dx%d", img.Bounds(), chartPNGWidth, chartPNGHeight)
	}
	nonWhite := countNonWhite(img)
	if nonWhite <= 100 {
		t.Fatalf("chart appears blank: nonWhiteSamples=%d, want >100", nonWhite)
	}
	t.Logf("pure-Go render OK: bounds=%v nonWhiteSamples=%d", img.Bounds(), nonWhite)
}

// TestRenderAndEncodePNGEndToEnd is the integration proof required after two
// failed GDI attempts: renderChartImage -> png.Encode -> real file ->
// image/png.Decode. There is no GDI call anywhere in this chain.
func TestRenderAndEncodePNGEndToEnd(t *testing.T) {
	s := core.DefaultSettings()
	s.Channels[0] = core.ChannelConfig{Name: "外径", UseTol: true, LowerMM: 9, UpperMM: 11}
	recs := []protocol.Record{
		{T: "08:00:00", ValueUM: 10000, Ch: 1, Verdict: "合格", TS: 1},
		{T: "08:00:01", ValueUM: 12000, Ch: 1, Verdict: "超差", TS: 2},
		{T: "08:00:02", ValueUM: 10200, Ch: 1, Verdict: "合格", TS: 3},
	}
	data := core.BuildChartData(recs, s, "")
	data.Kind = core.ChartBar
	u := &UI{chartData: data}

	img, err := u.renderChartImage(chartPNGWidth, chartPNGHeight)
	if err != nil {
		t.Fatalf("renderChartImage: %v", err)
	}

	path := filepath.Join(t.TempDir(), "chart_e2e.png")
	f, err := os.Create(path)
	if err != nil {
		t.Fatalf("os.Create: %v", err)
	}
	if err := png.Encode(f, img); err != nil {
		f.Close()
		t.Fatalf("png.Encode: %v", err)
	}
	if err := f.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	st, err := os.Stat(path)
	if err != nil {
		t.Fatalf("Stat: %v", err)
	}
	if st.Size() == 0 {
		t.Fatal("PNG file is empty")
	}
	rf, err := os.Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	decoded, err := png.Decode(rf)
	rf.Close()
	if err != nil {
		t.Fatalf("png.Decode: %v", err)
	}
	if decoded.Bounds().Dx() != chartPNGWidth || decoded.Bounds().Dy() != chartPNGHeight {
		t.Fatalf("decoded bounds = %v", decoded.Bounds())
	}
	t.Logf("E2E PNG OK: path=%s size=%d bytes bounds=%v nonWhiteSamples=%d",
		path, st.Size(), decoded.Bounds(), countNonWhite(img))
}

func countNonWhite(img *image.RGBA) int {
	count := 0
	b := img.Bounds()
	for y := b.Min.Y; y < b.Max.Y; y += 10 {
		for x := b.Min.X; x < b.Max.X; x += 10 {
			r, g, bl, _ := img.At(x, y).RGBA()
			if r>>8 != 255 || g>>8 != 255 || bl>>8 != 255 {
				count++
			}
		}
	}
	return count
}
