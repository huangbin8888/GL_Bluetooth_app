//go:build windows

package ui

import (
	"testing"

	"glcaliper/core"
)

// TestFilterKeyFromIndex covers the pure index -> internal-key mapping that
// drives the channel filter. It is display-name independent and defines the
// idx<0 / idx==0 ("all channels") semantics in one place.
func TestFilterKeyFromIndex(t *testing.T) {
	cases := []struct {
		idx  int
		want string
	}{
		{-3, ""},
		{-1, ""},
		{0, ""}, // 全部通道
		{1, "CH1"},
		{2, "CH2"},
		{7, "CH7"},
		{8, "CH8"},
	}
	for _, c := range cases {
		if got := filterKeyFromIndex(c.idx); got != c.want {
			t.Errorf("filterKeyFromIndex(%d) = %q, want %q", c.idx, got, c.want)
		}
	}
}

// TestFilterIsStableDuringPaint guards the root cause of the "筛选不准"
// report: a walk owner-data TableView calls Filter()/RowCount()/Value() many
// times in one paint. Even if the live control index wobbles mid-paint, the
// cached filter MUST NOT change until an explicit SyncFilter sync point. This
// is what prevents a correct row count with mixed channel cells on screen.
func TestFilterIsStableDuringPaint(t *testing.T) {
	app, _ := seedMultiChannelRecords(t)
	m := NewRecordModel(app)
	comboIndex := 2
	m.SetFilterProvider(func() string { return filterKeyFromIndex(comboIndex) })
	m.SyncFilter() // confirmed selection -> snapshot into cache

	if got := m.Filter(); got != "CH2" {
		t.Fatalf("Filter() = %q, want CH2", got)
	}
	// Simulate a transient control wobble DURING paint (no SyncFilter call).
	comboIndex = -1
	comboIndex = 0
	for i := 0; i < 100; i++ {
		if got := m.Filter(); got != "CH2" {
			t.Fatalf("Filter() drifted to %q mid-paint (index now %d); the stable cache must not follow a transient wobble", got, comboIndex)
		}
		_ = m.RowCount()
	}
}

// TestRowsFollowFilterViaSyncNoEvent proves the no-event path: the only thing
// that changes is the control index; once the watcher calls SyncFilter the
// table follows, with no SetDisplay(filter) and no change event.
func TestRowsFollowFilterViaSyncNoEvent(t *testing.T) {
	app, want := seedMultiChannelRecords(t)
	m := NewRecordModel(app)
	comboIndex := 0
	m.SetFilterProvider(func() string { return filterKeyFromIndex(comboIndex) })
	m.SyncFilter()

	if got := m.RowCount(); got != 6 {
		t.Fatalf("index 0 RowCount = %d, want 6", got)
	}
	comboIndex = 2
	if !m.SyncFilter() {
		t.Fatal("SyncFilter should report a change when index 0 -> 2")
	}
	if got := m.RowCount(); got != len(want[2]) {
		t.Fatalf("after sync index=2 RowCount = %d, want %d", got, len(want[2]))
	}
	for row := 0; row < m.RowCount(); row++ {
		if rec := m.RecordAt(row); rec.Ch != 2 {
			t.Fatalf("row %d has Ch=%d, want 2", row, rec.Ch)
		}
	}
	comboIndex = 1
	m.SyncFilter()
	if got := m.RowCount(); got != len(want[1]) {
		t.Fatalf("after sync index=1 RowCount = %d, want %d", got, len(want[1]))
	}
	comboIndex = 0
	m.SyncFilter()
	if got := m.RowCount(); got != 6 {
		t.Fatalf("after sync index=0 RowCount = %d, want 6", got)
	}
}

// TestSyncFilterNoopWhenUnchanged ensures repeated watcher ticks with the same
// index do not churn the cache (so callers can skip redundant repaints).
func TestSyncFilterNoopWhenUnchanged(t *testing.T) {
	app, _ := seedMultiChannelRecords(t)
	m := NewRecordModel(app)
	comboIndex := 2
	m.SetFilterProvider(func() string { return filterKeyFromIndex(comboIndex) })
	if !m.SyncFilter() {
		t.Fatal("first sync must report change")
	}
	if m.SyncFilter() {
		t.Fatal("sync with unchanged index must report no change")
	}
}

// TestNilProviderFallsBackToCachedFilter preserves backwards compatibility:
// callers that use SetDisplay without a provider keep the cached behavior.
func TestNilProviderFallsBackToCachedFilter(t *testing.T) {
	app, _ := seedMultiChannelRecords(t)
	m := NewRecordModel(app)
	m.SetDisplay(2, "mm", "CH3", false) // no provider installed

	if got := m.Filter(); got != "CH3" {
		t.Fatalf("Filter() = %q, want cached CH3", got)
	}
	if got := m.RowCount(); got != 2 {
		t.Fatalf("RowCount = %d, want 2 (cached CH3)", got)
	}
}

// TestNewRecordsFollowCurrentFilter proves the filter keeps applying while
// demo data streams in: freshly appended records of other channels stay out.
func TestNewRecordsFollowCurrentFilter(t *testing.T) {
	app := core.NewApp()
	m := NewRecordModel(app)
	comboIndex := 2
	m.SetFilterProvider(func() string { return filterKeyFromIndex(comboIndex) })
	m.SyncFilter()

	app.FeedDemoValue(10.00, 1) // CH1 - hidden
	app.FeedDemoValue(11.00, 2) // CH2 - visible
	if got := m.RowCount(); got != 1 {
		t.Fatalf("initial CH2 RowCount = %d, want 1", got)
	}
	app.FeedDemoValue(12.00, 1)
	app.FeedDemoValue(12.50, 2)
	if got := m.RowCount(); got != 2 {
		t.Fatalf("after streaming RowCount = %d, want 2", got)
	}
	for row := 0; row < m.RowCount(); row++ {
		if rec := m.RecordAt(row); rec.Ch != 2 {
			t.Fatalf("streamed row %d has Ch=%d, want 2", row, rec.Ch)
		}
	}
}

// TestOriginalIndexWithFilter guards the delete-mapping under a stable filter.
func TestOriginalIndexWithFilter(t *testing.T) {
	app, want := seedMultiChannelRecords(t)
	m := NewRecordModel(app)
	comboIndex := 2
	m.SetFilterProvider(func() string { return filterKeyFromIndex(comboIndex) })
	m.SyncFilter()

	for row, fullIdx := range want[2] {
		if got := m.OriginalIndex(row); got != fullIdx {
			t.Errorf("CH2 OriginalIndex(%d) = %d, want %d", row, got, fullIdx)
		}
	}
	comboIndex = 3
	m.SyncFilter()
	for row, fullIdx := range want[3] {
		if got := m.OriginalIndex(row); got != fullIdx {
			t.Errorf("CH3 OriginalIndex(%d) = %d, want %d", row, got, fullIdx)
		}
	}
}

// TestFilterSurvivesRename keeps the "renaming a channel must not clear the
// filter" guarantee: the key comes from the numeric index, not the name.
func TestFilterSurvivesRename(t *testing.T) {
	app, _ := seedMultiChannelRecords(t)
	s := app.GetSettings()
	s.Channels[1].Name = "内径-改"
	app.SetSettings(s)

	comboIndex := 2
	m := NewRecordModel(app)
	m.SetFilterProvider(func() string { return filterKeyFromIndex(comboIndex) })
	m.SyncFilter()

	names := filterDisplayNames(s)
	if names[2] != "CH2 内径-改" {
		t.Fatalf("display name = %q, want CH2 内径-改", names[2])
	}
	if got := m.Filter(); got != "CH2" {
		t.Fatalf("Filter() after rename = %q, want CH2", got)
	}
	for row := 0; row < m.RowCount(); row++ {
		if rec := m.RecordAt(row); rec.Ch != 2 {
			t.Fatalf("after rename row %d has Ch=%d, want 2", row, rec.Ch)
		}
	}
}
