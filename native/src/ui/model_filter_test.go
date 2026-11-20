//go:build windows

package ui

import (
	"testing"

	"glcaliper/core"
)

// seedMultiChannelRecords feeds a deterministic mix of CH1/CH2/CH3 records and
// returns the positions (in the full Records slice) occupied by each channel,
// in newest-first order to match the table's display order.
func seedMultiChannelRecords(t *testing.T) (*core.App, map[int][]int) {
	t.Helper()
	app := core.NewApp()
	// Values per channel differ by >= 1 mm so the 10 um debounce never drops
	// the second reading on the same channel.
	plan := []struct {
		ch int
		mm float64
	}{
		{1, 10.00}, // full index 0
		{2, 11.00}, // full index 1
		{3, 12.00}, // full index 2
		{2, 12.00}, // full index 3
		{1, 11.00}, // full index 4
		{3, 13.00}, // full index 5
	}
	want := make(map[int][]int)
	for i, p := range plan {
		app.FeedDemoValue(p.mm, p.ch)
		want[p.ch] = append(want[p.ch], i)
	}
	// The model displays newest-first; reverse each channel's full indices so
	// tests compare against display order (row 0 = most recent).
	for ch := range want {
		for i, j := 0, len(want[ch])-1; i < j; i, j = i+1, j-1 {
			want[ch][i], want[ch][j] = want[ch][j], want[ch][i]
		}
	}
	return app, want
}

func TestModelFilterSelectsOnlyRequestedChannel(t *testing.T) {
	app, want := seedMultiChannelRecords(t)
	m := NewRecordModel(app)

	m.SetDisplay(2, "mm", "", false)
	if got := m.RowCount(); got != 6 {
		t.Fatalf("unfiltered RowCount = %d, want 6", got)
	}
	if got := m.Filter(); got != "" {
		t.Fatalf("Filter() = %q, want empty", got)
	}

	for _, tc := range []struct {
		key string
		ch  int
	}{
		{"CH1", 1},
		{"CH2", 2},
		{"CH3", 3},
	} {
		m.SetDisplay(2, "mm", tc.key, false)
		wantN := len(want[tc.ch])
		if got := m.RowCount(); got != wantN {
			t.Fatalf("filter %s RowCount = %d, want %d", tc.key, got, wantN)
		}
		if got := m.Filter(); got != tc.key {
			t.Fatalf("Filter() = %q, want %q", got, tc.key)
		}
		for row := 0; row < m.RowCount(); row++ {
			if rec := m.RecordAt(row); rec.Ch != tc.ch {
				t.Fatalf("filter %s row %d has Ch=%d, want %d", tc.key, row, rec.Ch, tc.ch)
			}
		}
	}

	m.SetDisplay(2, "mm", "CH7", false)
	if got := m.RowCount(); got != 0 {
		t.Fatalf("filter CH7 RowCount = %d, want 0 (no CH7 records)", got)
	}
}

func TestOriginalIndexUnfilteredMatchesFullSlice(t *testing.T) {
	app, _ := seedMultiChannelRecords(t)
	m := NewRecordModel(app)
	m.SetDisplay(2, "mm", "", false)
	n := m.RowCount()
	for row := 0; row < n; row++ {
		// Newest-first: display row 0 is the last full-slice record.
		want := n - 1 - row
		if got := m.OriginalIndex(row); got != want {
			t.Errorf("OriginalIndex(%d) = %d, want %d (newest-first)", row, got, want)
		}
	}
	if got := m.OriginalIndex(-1); got != -1 {
		t.Errorf("OriginalIndex(-1) = %d, want -1", got)
	}
	if got := m.OriginalIndex(m.RowCount()); got != -1 {
		t.Errorf("OriginalIndex(out of range) = %d, want -1", got)
	}
}

func TestOriginalIndexUnderFilterMapsVisibleRowToFullRecord(t *testing.T) {
	app, want := seedMultiChannelRecords(t)
	m := NewRecordModel(app)

	// CH2 records live at full indices 1 and 3 (oldest-first). Newest-first
	// display shows full index 3 first, then 1.
	m.SetDisplay(2, "mm", "CH2", false)
	wantIdx := want[2]
	if got := m.RowCount(); got != len(wantIdx) {
		t.Fatalf("CH2 RowCount = %d, want %d", got, len(wantIdx))
	}
	for row, fullIdx := range wantIdx {
		if got := m.OriginalIndex(row); got != fullIdx {
			t.Errorf("OriginalIndex(%d) = %d, want %d (full CH2 position)", row, got, fullIdx)
		}
		rec := m.RecordAt(row)
		if rec.Ch != 2 {
			t.Errorf("RecordAt(%d).Ch = %d, want 2", row, rec.Ch)
		}
	}

	// CH3 is at full indices 2 and 5 (oldest-first) -> display [5, 2].
	m.SetDisplay(2, "mm", "CH3", false)
	for row, fullIdx := range want[3] {
		if got := m.OriginalIndex(row); got != fullIdx {
			t.Errorf("CH3 OriginalIndex(%d) = %d, want %d", row, got, fullIdx)
		}
	}
}

func TestOriginalIndexUnderSortMapsBackToFullRecord(t *testing.T) {
	app, _ := seedMultiChannelRecords(t)
	m := NewRecordModel(app)

	// Sorting reorders the view; the mapping must still point at the original
	// full-slice position so a delete removes the row the user selected.
	m.SetDisplay(2, "mm", "", true)
	full := app.RecordsSnapshot()
	for row := 0; row < m.RowCount(); row++ {
		idx := m.OriginalIndex(row)
		if idx < 0 || idx >= len(full) {
			t.Fatalf("sorted row %d maps to invalid full index %d", row, idx)
		}
		if got, wantRec := m.RecordAt(row), full[idx]; got.Seq != wantRec.Seq || got.Ch != wantRec.Ch {
			t.Errorf("sorted row %d: RecordAt Seq=%d Ch=%d, full[%d] Seq=%d Ch=%d",
				row, got.Seq, got.Ch, idx, wantRec.Seq, wantRec.Ch)
		}
	}
}

func TestOriginalIndexTracksDeletionUnderFilter(t *testing.T) {
	app, want := seedMultiChannelRecords(t)
	m := NewRecordModel(app)
	m.SetDisplay(2, "mm", "CH2", false)

	// Newest-first: first visible CH2 row is full index 3 (the newest CH2).
	first := m.OriginalIndex(0)
	if first != want[2][0] {
		t.Fatalf("first visible CH2 maps to %d, want %d", first, want[2][0])
	}
	app.DeleteIndex(first)

	if got := m.RowCount(); got != 1 {
		t.Fatalf("RowCount after delete = %d, want 1", got)
	}
	// The remaining CH2 record was full index 1; deleting index 3 leaves it at 1.
	if got := m.OriginalIndex(0); got != 1 {
		t.Fatalf("remaining CH2 row maps to %d, want 1", got)
	}
	if rec := m.RecordAt(0); rec.Ch != 2 {
		t.Fatalf("remaining row Ch=%d, want 2", rec.Ch)
	}
}

func TestFilterFollowsUserSelectionSequence(t *testing.T) {
	// Guards the transition ALL -> CHn -> ALL that onFilterChanged performs.
	app, _ := seedMultiChannelRecords(t)
	m := NewRecordModel(app)
	steps := []struct {
		filter string
		want   int
	}{
		{"", 6},
		{"CH1", 2},
		{"CH2", 2},
		{"CH3", 2},
		{"", 6},
	}
	for _, s := range steps {
		m.SetDisplay(2, "mm", s.filter, false)
		if got := m.RowCount(); got != s.want {
			t.Errorf("filter %q RowCount = %d, want %d", s.filter, got, s.want)
		}
		if got := m.Filter(); got != s.filter {
			t.Errorf("Filter() = %q, want %q", got, s.filter)
		}
	}
}
