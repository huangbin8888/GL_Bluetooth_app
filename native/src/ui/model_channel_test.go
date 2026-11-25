//go:build windows

package ui

import (
	"testing"

	"glcaliper/core"
)

// TestTableChannelColumnFollowsCustomName guards the real-time table's
// channel column: it must show the user-defined name (with CHn fallback),
// while the internal filter key stays "CHn" so filtering/export keep working.
func TestTableChannelColumnFollowsCustomName(t *testing.T) {
	app := core.NewApp()
	s := app.GetSettings()
	s.Channels[0].Name = "卡尺A" // CH1 renamed
	s.Channels[1].Name = "   " // CH2 blank -> must fall back to CH2
	app.SetSettings(s)

	app.FeedDemoValue(10.05, 1)
	app.FeedDemoValue(10.06, 2)

	m := NewRecordModel(app)
	// Newest-first: CH2 was recorded last, so it is row 0.
	// Channel column moved from col 1 to col 2 (设备名称 inserted at col 1).
	if got := m.Value(0, 2); got != "CH2" {
		t.Errorf("row 0 channel cell = %v, want CH2 (blank name fallback, newest)", got)
	}
	if got := m.Value(1, 2); got != "卡尺A" {
		t.Errorf("row 1 channel cell = %v, want 卡尺A", got)
	}
	// 设备名称 column (col 1): DEMO records carry their source tag.
	if got := m.Value(0, 1); got != "DEMO" {
		t.Errorf("row 0 device-name cell = %v, want DEMO", got)
	}

	// Internal filter key must remain CHn and still select the right rows.
	m.SetDisplay(2, "mm", "CH1", false)
	if got := m.RowCount(); got != 1 {
		t.Fatalf("filtered RowCount = %d, want 1 (CH1 only)", got)
	}
	if got := m.Value(0, 2); got != "卡尺A" {
		t.Errorf("filtered row 0 channel cell = %v, want 卡尺A", got)
	}
}

// TestTableDeviceNameColumnFollowsBleName guards the requested behaviour:
// editing a channel's Bluetooth name in Settings must be reflected live in the
// table's 设备名称 column, per channel, for already-visible rows too; clearing
// it falls back to the captured advertising name / source tag.
func TestTableDeviceNameColumnFollowsBleName(t *testing.T) {
	app := core.NewApp()
	app.FeedDemoValue(10.05, 1) // CH1
	app.FeedDemoValue(10.06, 2) // CH2 (newest -> row 0)

	m := NewRecordModel(app)
	// No override yet: DEMO records show their source tag.
	if got := m.Value(0, 1); got != "DEMO" {
		t.Fatalf("before override, device-name cell = %v, want DEMO", got)
	}

	// User sets CH2's Bluetooth name in Settings; only CH2 rows change.
	s := app.GetSettings()
	s.Channels[1].BleName = "数显尺-B"
	app.SetSettings(s)
	if got := m.Value(0, 1); got != "数显尺-B" {
		t.Errorf("CH2 device-name cell = %v, want 数显尺-B", got)
	}
	if got := m.Value(1, 1); got != "DEMO" {
		t.Errorf("CH1 device-name cell = %v, want DEMO (CH2 override must not leak)", got)
	}

	// Clearing the override falls back to the captured name again.
	s = app.GetSettings()
	s.Channels[1].BleName = ""
	app.SetSettings(s)
	if got := m.Value(0, 1); got != "DEMO" {
		t.Errorf("after clear, device-name cell = %v, want DEMO", got)
	}
}
