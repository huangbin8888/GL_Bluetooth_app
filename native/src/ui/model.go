//go:build windows

package ui

import (
	"fmt"

	"github.com/lxn/walk"
	"glcaliper/core"
	"glcaliper/protocol"
)

// RecordModel is a walk TableModel over the app's records.
type RecordModel struct {
	walk.TableModelBase
	app      *core.App
	digits   int
	unit     string
	sorted   bool
	filtered string // fallback filter ("" | "CH1".."CH7") used when no provider is installed

	// filterProvider is the authoritative, live source of the channel filter.
	// When non-nil, it is consulted on EVERY Filter()/rows()/RowCount()/Value()
	// call so the table always follows what the filter ComboBox currently
	// shows. An event-driven cached filter can diverge from the selector's
	// display state (the change may never reach the handler, or a model
	// rebuild may broadcast -1 and clear it); reading the live index removes
	// both failure modes instead of assuming either cannot happen.
	// It is intentionally a plain func (no walk dependency) so the whole
	// filter path stays unit-testable.
	filterProvider func() string

	// viewCache holds the visible rows in DISPLAY order (newest first) and the
	// parallel full-slice indices used for deletion. walk calls RowCount/Value/
	// StyleCell dozens of times per repaint; without a cache each call cloned
	// and filtered the entire record slice, which made large tables flicker
	// badly while streaming. The cache is rebuilt at most once per change and
	// is invalidated by a cheap signature check on every access.
	viewCache []protocol.Record
	viewOrig  []int
	viewKey   string
	viewValid bool
}

// viewSignature captures everything that can change the visible view. It uses
// a cheap O(1) record fingerprint (count + last Seq) so cache checks never
// clone the record slice — the old per-cell full clone was the flicker source.
func (m *RecordModel) viewSignature() string {
	count, lastSeq := m.app.RecordsStamp()
	return fmt.Sprintf("%s|%v|%d|%s|%d|%d", m.Filter(), m.sorted, count, m.unit, m.digits, lastSeq)
}

// rebuildView rebuilds the cached visible view. It produces the visible
// records in newest-first display order together with their full-slice indices
// in one pass, so the TableView row -> full index mapping stays unambiguous.
func (m *RecordModel) rebuildView() {
	full := m.app.RecordsSnapshot()
	bySeq := make(map[int]int, len(full))
	for i, r := range full {
		bySeq[r.Seq] = i
	}

	view := full
	if m.sorted {
		view = m.app.SortedByChannel()
	}

	filter := m.Filter()
	visible := make([]protocol.Record, 0, len(view))
	orig := make([]int, 0, len(view))
	for _, r := range view {
		if !matchesFilterKey(filter, r) {
			continue
		}
		visible = append(visible, r)
		if idx, ok := bySeq[r.Seq]; ok {
			orig = append(orig, idx)
		} else {
			orig = append(orig, -1)
		}
	}

	// Newest-first: reverse both slices in lockstep so row N still maps to
	// orig[N]. The underlying source slices are oldest-first, so a plain
	// reversal puts the most recent record at row 0 (top-left of the table).
	for i, j := 0, len(visible)-1; i < j; i, j = i+1, j-1 {
		visible[i], visible[j] = visible[j], visible[i]
		orig[i], orig[j] = orig[j], orig[i]
	}

	m.viewCache, m.viewOrig = visible, orig
	m.viewKey = m.viewSignature()
	m.viewValid = true
}

// ensureView rebuilds the cache only when its signature changed.
func (m *RecordModel) ensureView() {
	if !m.viewValid || m.viewKey != m.viewSignature() {
		m.rebuildView()
	}
}

func NewRecordModel(app *core.App) *RecordModel {
	return &RecordModel{app: app, digits: 2, unit: "mm"}
}

// SetFilterProvider installs (or clears, with nil) the live filter provider.
// The UI installs a provider that reads cmbFilter.CurrentIndex() (a real
// CB_GETCURSEL on the control HWND, always up to date). Tests and callers that
// do not need live coupling can leave it nil and keep using SetDisplay.
func (m *RecordModel) SetFilterProvider(provider func() string) {
	m.filterProvider = provider
}

func (m *RecordModel) SetDisplay(digits int, unit, filtered string, sorted bool) {
	m.digits, m.unit, m.filtered, m.sorted = digits, unit, filtered, sorted
}

// Filter returns the effective channel filter currently applied to the table
// ("" = all channels, "CH1".."CH7" = one channel). It is a STABLE cached value,
// not a live control read.
//
// Why stable: a walk owner-data TableView calls RowCount() and Value(row,col)
// MANY times during a single paint. If Filter() re-read CB_GETCURSEL each time
// (the old design), a transient index抖动 during a dropdown interaction could
// make RowCount() see "CH2" while Value() sees "" — producing the exact bug the
// user reported: row count correct but painted channel cells mixed across all
// channels. The UI updates this cache exactly once per confirmed selection in
// applyFilter(); every paint then reads one consistent value.
//
// The provider is still used ONLY at that explicit sync point (SyncFilter), so
// tests and the no-event path (index changed but event lost) keep working: the
// 100ms watcher calls SyncFilter + repaint whenever the real index changes.
func (m *RecordModel) Filter() string {
	return m.filtered
}

// SyncFilter snapshots the live provider (if any) into the stable cache.
// Returns true when the effective filter actually changed, so the caller can
// avoid redundant repaints. With no provider installed it is a no-op.
func (m *RecordModel) SyncFilter() bool {
	if m.filterProvider == nil {
		return false
	}
	next := m.filterProvider()
	if next == m.filtered {
		return false
	}
	m.filtered = next
	return true
}

// matchesFilterKey reports whether a record belongs to an internal filter
// key. The key is always the numeric "CHn" token, independent of any
// user-defined display name, so export/chart/statistics are unaffected.
// Pure on purpose: cheap to test and to call from hot model paths.
func matchesFilterKey(filter string, r protocol.Record) bool {
	return filter == "" || fmt.Sprintf("CH%d", r.Ch) == filter
}

func (m *RecordModel) rows() []protocol.Record {
	m.ensureView()
	return m.viewCache
}

// rowsWithIndex returns the visible records (newest first) together with the
// index each one occupies in the app's full Records slice. Both come from the
// same cached pass, so a TableView row maps to a delete target without any
// ambiguity from a concurrent filter/sort change.
func (m *RecordModel) rowsWithIndex() ([]protocol.Record, []int) {
	m.ensureView()
	return m.viewCache, m.viewOrig
}

// OriginalIndex maps a visible TableView row to the index of that record in
// the full Records slice (the index DeleteIndex expects). It returns -1 for
// an out-of-range row or a row whose record can no longer be located.
func (m *RecordModel) OriginalIndex(row int) int {
	_, idxs := m.rowsWithIndex()
	if row < 0 || row >= len(idxs) {
		return -1
	}
	return idxs[row]
}

// RecordAt returns the record shown at a visible TableView row. It returns a
// zero-value record for an out-of-range row.
func (m *RecordModel) RecordAt(row int) protocol.Record {
	visible, _ := m.rowsWithIndex()
	if row < 0 || row >= len(visible) {
		return protocol.Record{}
	}
	return visible[row]
}

func (m *RecordModel) RowCount() int { return len(m.rows()) }

func (m *RecordModel) Value(row, col int) interface{} {
	recs := m.rows()
	if row < 0 || row >= len(recs) {
		return ""
	}
	r := recs[row]
	switch col {
	case 0:
		return r.T
	case 1:
		// 设备名称：设置里该通道的自定义蓝牙名优先（改名后即时生效，含已显示
		// 的历史行）；未配置时回退到连接时读到的广播名 GL-BLE#XXX。
		// HID/DEMO 记录携带来源标签，保证此单元格永不为空。
		if n := m.app.GetSettings().ChannelBleName(r.Ch); n != "" {
			return n
		}
		return r.DeviceName
	case 2:
		// Channel column follows the user-defined name (fallback CHn).
		// The internal filter key stays CHn (see matchesFilterKey / rows()).
		return m.app.GetSettings().ChannelName(r.Ch)
	case 3:
		if m.unit == "inch" {
			return fmt.Sprintf("%.4f", protocol.ToInch(r.ValueUM))
		}
		return fmt.Sprintf("%.*f", m.digits, protocol.ToMM(r.ValueUM))
	case 4:
		// RawUnit is the protocol token ("m"|"i"); always show a friendly label.
		return protocol.FriendlyUnit(r.RawUnit)
	case 5:
		return r.Verdict
	}
	return ""
}

// StyleCell 实现 walk.CellStyler：
// 判定列着绿/红色块、来源列按 BLE/HID/DEMO 分色、数值列用等宽字体。
// 注意：StyleCell 会被高频调用，这里只做 O(1) 的取行与字符串比较。
func (m *RecordModel) StyleCell(style *walk.CellStyle) {
	row, col := style.Row(), style.Col()
	recs := m.rows()
	if row < 0 || row >= len(recs) {
		return
	}
	r := recs[row]
	// 列字体：时间/设备名称/通道/单位小一档（用户要求），实测值等宽粗体保持，行高由控件字体决定不受影响。
	switch col {
	case 0, 1, 2, 4: // 时间、设备名称、通道、单位
		if fontCellSmall != nil {
			style.Font = fontCellSmall
		}
	case 3: // 实测值（等宽粗体）
		if fontCellBold != nil {
			style.Font = fontCellBold
		}
	}
	switch col {
	case 5: // 判定（原 4 列，设备名称插入后后移一位）
		switch r.Verdict {
		case "合格", "OK", "PASS":
			style.BackgroundColor = colorOKPanel
			style.TextColor = colorOKText
		default:
			style.BackgroundColor = colorBadPanel
			style.TextColor = colorBadText
		}
	}
	// 注意：旧的 case 5（来源列分色）已删除。来源列从未在表格中显示，
	// 且插入设备名称列后索引 5 已是判定列，若保留会覆盖判定的绿/红色块。
}
