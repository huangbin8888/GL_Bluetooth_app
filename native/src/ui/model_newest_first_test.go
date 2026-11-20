//go:build windows

package ui

import (
	"testing"

	"glcaliper/core"
)

// TestNewestFirstOrder verifies the live table shows the most recently
// recorded sample at row 0 (top-left), with older rows following downward.
// It also checks that the full-slice delete mapping is reversed in lockstep,
// so deleting the row on top removes the newest record, not the oldest.
func TestNewestFirstOrder(t *testing.T) {
	app := core.NewApp()
	m := NewRecordModel(app)
	m.SetDisplay(2, "mm", "", false)

	// No records yet.
	if got := m.RowCount(); got != 0 {
		t.Fatalf("empty RowCount = %d, want 0", got)
	}

	// Feed three records with distinct values; Seq 1..3, oldest-first.
	app.FeedDemoValue(10.00, 1)
	app.FeedDemoValue(11.00, 1)
	app.FeedDemoValue(12.00, 1)
	if got := m.RowCount(); got != 3 {
		t.Fatalf("RowCount = %d, want 3", got)
	}

	full := app.RecordsSnapshot()
	// Row 0 must be the newest record (last in the full slice).
	for row := 0; row < 3; row++ {
		want := full[len(full)-1-row]
		got := m.RecordAt(row)
		if got.Seq != want.Seq {
			t.Errorf("row %d Seq = %d, want %d (newest-first)", row, got.Seq, want.Seq)
		}
		wantIdx := len(full) - 1 - row
		if gotIdx := m.OriginalIndex(row); gotIdx != wantIdx {
			t.Errorf("OriginalIndex(%d) = %d, want %d", row, gotIdx, wantIdx)
		}
	}

	// A freshly streamed record must appear at row 0 and bump the old top to row 1.
	oldTop := m.RecordAt(0).Seq
	app.FeedDemoValue(13.00, 1)
	if got := m.RecordAt(0).Seq; got <= oldTop {
		t.Errorf("after streaming, row 0 Seq = %d, want > %d (newest on top)", got, oldTop)
	}
	if got := m.RecordAt(1).Seq; got != oldTop {
		t.Errorf("after streaming, row 1 Seq = %d, want previous top %d", got, oldTop)
	}
}

// TestNewestFirstCacheNoCopy verifies that repeated cell reads with unchanged
// data return the same cached view (the anti-flicker guarantee) and that the
// cache refreshes when records change.
func TestNewestFirstCacheStableAcrossReads(t *testing.T) {
	app := core.NewApp()
	m := NewRecordModel(app)
	m.SetDisplay(2, "mm", "", false)
	app.FeedDemoValue(10.00, 1)
	app.FeedDemoValue(11.00, 1)

	first := m.RecordAt(0).Seq
	for i := 0; i < 50; i++ {
		_ = m.RowCount()
		_ = m.Value(0, 2)
		_ = m.Value(1, 1)
		if got := m.RecordAt(0).Seq; got != first {
			t.Fatalf("cached top changed to %d without data change", got)
		}
	}

	app.FeedDemoValue(12.00, 1)
	if got := m.RecordAt(0).Seq; got <= first {
		t.Fatalf("cache did not refresh after new record: top %d, was %d", got, first)
	}
}
