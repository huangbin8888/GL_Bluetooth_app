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
	s.Channels[1].Name = "   "  // CH2 blank -> must fall back to CH2
	app.SetSettings(s)

	app.FeedDemoValue(10.05, 1)
	app.FeedDemoValue(10.06, 2)

	m := NewRecordModel(app)
	// Newest-first: CH2 was recorded last, so it is row 0.
	if got := m.Value(0, 1); got != "CH2" {
		t.Errorf("row 0 channel cell = %v, want CH2 (blank name fallback, newest)", got)
	}
	if got := m.Value(1, 1); got != "卡尺A" {
		t.Errorf("row 1 channel cell = %v, want 卡尺A", got)
	}

	// Internal filter key must remain CHn and still select the right rows.
	m.SetDisplay(2, "mm", "CH1", false)
	if got := m.RowCount(); got != 1 {
		t.Fatalf("filtered RowCount = %d, want 1 (CH1 only)", got)
	}
	if got := m.Value(0, 1); got != "卡尺A" {
		t.Errorf("filtered row 0 channel cell = %v, want 卡尺A", got)
	}
}
