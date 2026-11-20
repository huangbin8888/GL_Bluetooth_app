//go:build windows

package ui

import (
	"errors"
	"image"
	"image/png"
	"os"
	"path/filepath"

	"github.com/lxn/walk"
	"glcaliper/core"
	"glcaliper/protocol"
)

const (
	chartPNGWidth  = 1200
	chartPNGHeight = 700
	chartPNGDPI    = 96 // documentation only: software rasterisation is DPI-independent
)

// exportChartPNG writes the current chart as a PNG. The FileDialog is the only
// walk control involved; image generation is pure software rasterisation with
// no Canvas/Bitmap/GetDIBits, so it works in every GUI session.
func (u *UI) exportChartPNG() error {
	u.refreshChart()

	dlg := walk.FileDialog{
		Title:    "导出图表 PNG",
		Filter:   "PNG 图片 (*.png)|*.png",
		FilePath: filepath.Join(coreExportDir(), "chart_"+protocol.NowStamp()+".png"),
	}
	if u.main != nil {
		ok, err := dlg.ShowSave(u.main)
		if err != nil || !ok {
			return nil // user cancelled is not an error
		}
	} else if dlg.FilePath == "" {
		return nil
	}

	img, err := u.renderChartImage(chartPNGWidth, chartPNGHeight)
	if err != nil {
		return err
	}
	f, err := os.Create(dlg.FilePath)
	if err != nil {
		return err
	}
	defer f.Close()
	if err := png.Encode(f, img); err != nil {
		return err
	}
	u.setStatus("已导出 PNG 图表：" + dlg.FilePath)
	return nil
}

// renderChartImage is the single export path: pure software rasterisation
// into image.RGBA. ZERO GDI objects, ZERO GDI pixel readbacks, ZERO new
// dependencies. Geometry and tolerance come from the same pure core functions
// used by the on-screen chart (ComputeYRange / FitY / FitX). Labels are ASCII
// because no font dependency may be added; this is the documented trade-off.
func (u *UI) renderChartImage(width, height int) (*image.RGBA, error) {
	if width <= 0 || height <= 0 {
		return nil, errors.New("invalid chart size")
	}
	return renderChartPureGo(u.chartData, width, height), nil
}

// coreExportDir returns the exports directory next to the executable.
func coreExportDir() string {
	dir := core.DefaultExportDir()
	if dir == "" {
		return "exports"
	}
	return dir
}
