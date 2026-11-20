//go:build windows

package ui

import (
	"time"

	"golang.org/x/sys/windows"
	"glcaliper/protocol"
)

// messageBeep resolves user32!MessageBeep lazily. It exists on Windows 7 and
// needs no extra dependency; x/sys v0.15 does not export a typed wrapper.
var messageBeep = windows.NewLazySystemDLL("user32.dll").NewProc("MessageBeep")

// maybeAlertOnBad emits a best-effort beep and flashes the reading label red
// when an out-of-tolerance record arrives. DEMO is skipped on purpose: at
// 600 ms/record it would beep continuously.
func (u *UI) maybeAlertOnBad(rec protocol.Record) {
	if rec.Src == "DEMO" || rec.Verdict != "超差" {
		return
	}

	// Beep is intentionally best-effort: silent machines simply show the UI cue.
	messageBeep.Call(uintptr(0xFFFFFFFF))

	if u.lblStatus != nil {
		chName := u.app.GetSettings().ChannelName(rec.Ch)
		_ = u.lblStatus.SetText("⚠ 超差提醒：" + rec.T + " " + chName + " 实测 " +
			u.fmtMM(rec.ValueUM))
	}
	if u.main == nil || u.lblReading == nil {
		return
	}
	u.lblReading.SetTextColor(colorBadText)
	time.AfterFunc(1500*time.Millisecond, func() {
		u.main.Synchronize(func() {
			if u.lblReading == nil {
				return
			}
			u.lblReading.SetTextColor(colorAmberHi)
			u.refreshReading()
		})
	})
}
