package core

import (
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"glcaliper/protocol"
)

func TestAppFeedHIDPipeline(t *testing.T) {
	a := NewApp()
	var records int32
	a.OnChange(func(c Change) {
		if c.Kind == "record" {
			atomic.AddInt32(&records, 1)
		}
	})
	if !a.FeedHIDToken("-1.2485 in") {
		t.Fatal("valid HID token rejected")
	}
	recs := a.RecordsSnapshot()
	if len(recs) != 1 {
		t.Fatalf("records=%d want 1", len(recs))
	}
	if recs[0].ValueUM != -31712 || recs[0].Src != "HID" {
		t.Errorf("record = %+v", recs[0])
	}
	if recs[0].Ch != 1 || recs[0].Seq != 1 {
		t.Errorf("ch/seq = %d/%d", recs[0].Ch, recs[0].Seq)
	}
	if atomic.LoadInt32(&records) != 1 {
		t.Errorf("observer fired %d times", records)
	}
	if a.FeedHIDToken("garbage") {
		t.Error("garbage token should be rejected")
	}
}

func TestAppDebounce(t *testing.T) {
	a := NewApp()
	s := a.GetSettings()
	s.DebounceUM = 10
	s.MaxMS = 300
	a.SetSettings(s)
	a.FeedHIDToken("10.00")
	a.FeedHIDToken("10.004") // within 10um & 300ms -> dropped
	if a.Count() != 1 {
		t.Errorf("debounce failed: count=%d want 1", a.Count())
	}
}

func TestAppResetAndDelete(t *testing.T) {
	a := NewApp()
	a.FeedHIDToken("10.00")
	a.FeedHIDToken("11.00")
	a.DeleteLast()
	if a.Count() != 1 {
		t.Errorf("after delete last count=%d", a.Count())
	}
	a.Reset()
	if a.Count() != 0 {
		t.Errorf("after reset count=%d", a.Count())
	}
}

func TestAppExportImportRoundTrip(t *testing.T) {
	a := NewApp()
	s := a.GetSettings()
	s.Batch = "T1"
	a.SetSettings(s)
	a.FeedHIDToken("10.00")
	a.FeedHIDToken("12.50")
	a.SetChannel(2)
	a.FeedHIDToken("11.20")

	dir := t.TempDir()
	all := filepath.Join(dir, "all.csv")
	n, err := a.ExportCsv(all, "all")
	if err != nil || n != 3 {
		t.Fatalf("export n=%d err=%v", n, err)
	}
	ch1 := filepath.Join(dir, "ch1.csv")
	n1, _ := a.ExportCsv(ch1, "CH1")
	if n1 != 2 {
		t.Errorf("CH1 export = %d want 2", n1)
	}

	b := NewApp()
	added, bad, err := b.ImportCsv(all)
	if err != nil || bad != 0 || added != 3 {
		t.Fatalf("import added=%d bad=%d err=%v", added, bad, err)
	}
	if b.Count() != 3 {
		t.Errorf("imported count=%d", b.Count())
	}
}

func TestAppStats(t *testing.T) {
	a := NewApp()
	a.FeedHIDToken("10.00")
	a.FeedHIDToken("10.02")
	a.FeedHIDToken("11.00") // out of tolerance -> 超差
	st := a.Stats()
	if st.N != 3 || st.OK != 2 || st.OKRate != 67 {
		t.Errorf("stats = %+v", st)
	}
}

func TestDemoLifecycle(t *testing.T) {
	a := NewApp()
	a.StartDemo()
	if !a.IsDemo() {
		t.Error("demo not running")
	}
	time.Sleep(1300 * time.Millisecond)
	if a.Count() < 1 {
		t.Errorf("demo produced no records: %d", a.Count())
	}
	// toggle while running stops it and returns false
	if a.ToggleDemo() {
		t.Error("toggle should have stopped demo (return false)")
	}
	if a.IsDemo() {
		t.Error("demo still running after stop")
	}
}

func TestDefaultExportDir(t *testing.T) {
	d := DefaultExportDir()
	if d == "" || !strings.Contains(d, "exports") {
		t.Errorf("export dir = %q", d)
	}
	_ = os.ErrNotExist
}

func TestChannelBoundsAndNameFallback(t *testing.T) {
	s := DefaultSettings() // nominal 10, tol -0.05..0.05 => 9950..10050
	if lo, hi := s.ChannelBounds(1); lo != 9950 || hi != 10050 {
		t.Errorf("default ch1 bounds = %d/%d", lo, hi)
	}
	// enabled + valid
	s.Channels[0] = ChannelConfig{Name: "外径", UseTol: true, LowerMM: 9, UpperMM: 11}
	if lo, hi := s.ChannelBounds(1); lo != 9000 || hi != 11000 {
		t.Errorf("custom ch1 bounds = %d/%d", lo, hi)
	}
	if got := s.ChannelName(1); got != "外径" {
		t.Errorf("custom name = %q", got)
	}
	// enabled but invalid range (UpperMM <= LowerMM) -> global fallback
	s.Channels[0] = ChannelConfig{UseTol: true, LowerMM: 12, UpperMM: 8}
	if lo, hi := s.ChannelBounds(1); lo != 9950 || hi != 10050 {
		t.Errorf("invalid bounds should fall back, got %d/%d", lo, hi)
	}
	// out of range / unset -> global fallback + CHn name
	if lo, hi := s.ChannelBounds(0); lo != 9950 || hi != 10050 {
		t.Errorf("ch0 bounds = %d/%d", lo, hi)
	}
	if lo, hi := s.ChannelBounds(8); lo != 9950 || hi != 10050 {
		t.Errorf("ch8 bounds = %d/%d", lo, hi)
	}
	if got := s.ChannelName(5); got != "CH5" {
		t.Errorf("default name = %q", got)
	}
	if got := s.ChannelName(9); got != "CH9" {
		t.Errorf("out of range name = %q", got)
	}
}

func TestCommitStatsChartCrossConsistency(t *testing.T) {
	a := NewApp()
	s := a.GetSettings()
	s.Channels[0] = ChannelConfig{Name: "外径", UseTol: true, LowerMM: 9, UpperMM: 11}
	a.SetSettings(s)

	// 10.5mm on CH1 is inside [9,11] but OUTSIDE global [9.95,10.05].
	a.FeedHIDToken("10.50")
	recs := a.RecordsSnapshot()
	if len(recs) != 1 {
		t.Fatalf("records = %d", len(recs))
	}
	if recs[0].Verdict != "合格" {
		t.Errorf("commit verdict = %q, want 合格 (channel tolerance)", recs[0].Verdict)
	}
	st := a.Stats()
	if st.N != 1 || st.OK != 1 || st.OKRate != 100 {
		t.Errorf("stats = %+v, want OK=1", st)
	}
	data := BuildChartData(recs, a.GetSettings(), "")
	if len(data.Points) != 1 || data.Points[0].Out {
		t.Errorf("chart point = %+v, want Out=false", data.Points)
	}

	// Now switch the channel tolerance so the SAME stored record would be bad;
	// history is a snapshot, so stored verdict stays, but stats/chart follow
	// the current tolerance source (single source of truth).
	s2 := a.GetSettings()
	s2.Channels[0] = ChannelConfig{UseTol: true, LowerMM: 10.8, UpperMM: 10.9}
	a.SetSettings(s2)
	if a.RecordsSnapshot()[0].Verdict != "合格" {
		t.Error("history verdict must not be rewritten")
	}
	st2 := a.Stats()
	if st2.OK != 0 || st2.OKRate != 0 {
		t.Errorf("stats after tolerance change = %+v", st2)
	}
	data2 := BuildChartData(a.RecordsSnapshot(), a.GetSettings(), "")
	if !data2.Points[0].Out {
		t.Error("chart should mark out after tolerance change")
	}
}

func TestApp7ColCSVWithChannelNames(t *testing.T) {
	a := NewApp()
	s := a.GetSettings()
	s.Batch = "T7"
	s.Channels[0] = ChannelConfig{Name: "外径", UseTol: true, LowerMM: 9, UpperMM: 11}
	s.Channels[1] = ChannelConfig{Name: "内径", UseTol: true, LowerMM: 19, UpperMM: 21}
	a.SetSettings(s)
	a.FeedHIDToken("10.00")
	a.SetChannel(2)
	a.FeedHIDToken("20.00")

	dir := t.TempDir()
	path := filepath.Join(dir, "named.csv")
	n, err := a.ExportCsv(path, "all")
	if err != nil || n != 2 {
		t.Fatalf("export n=%d err=%v", n, err)
	}
	content, _ := os.ReadFile(path)
	text := string(content)
	if !strings.HasPrefix(text, "\ufeff") {
		t.Error("missing BOM")
	}
	firstLine := strings.Split(strings.TrimPrefix(text, "\ufeff"), "\r\n")[0]
	if firstLine != strings.Join(protocolCSVHeadersCN(), ",") {
		t.Errorf("header = %q", firstLine)
	}
	if !strings.Contains(text, "外径") || !strings.Contains(text, "内径") {
		t.Errorf("custom channel names missing: %s", text)
	}

	// Import into a fresh app: names reverse-map to CH1/CH2 and dedup keeps 2.
	b := NewApp()
	sB := b.GetSettings()
	sB.Channels[0] = s.Channels[0]
	sB.Channels[1] = s.Channels[1]
	b.SetSettings(sB)
	added, bad, err := b.ImportCsv(path)
	if err != nil || bad != 0 || added != 2 {
		t.Fatalf("import added=%d bad=%d err=%v", added, bad, err)
	}
	recs := b.RecordsSnapshot()
	chSet := map[int]bool{}
	for _, r := range recs {
		chSet[r.Ch] = true
	}
	if !chSet[1] || !chSet[2] {
		t.Errorf("reverse lookup failed: channels = %v", chSet)
	}
}

// protocolCSVHeadersCN keeps the test independent of a direct protocol import
// churn while still asserting the exact Chinese 7-column header.
func protocolCSVHeadersCN() []string {
	return []string{"日期", "时间", "设备名称", "测量值", "公差上限", "公差下限", "通道名称"}
}

func TestSettingsNormalizeOldAndInvalidValues(t *testing.T) {
	d := DefaultSettings()
	if d.IdleMinutes != 5 || d.NamePrefix != "GL-BLE#" || d.MaxCh != 20 {
		t.Errorf("defaults = %+v", d)
	}
	// simulate an old config with only legacy keys present.
	// Digits=0 / DebounceUM=0 are legal legacy values and are preserved by the
	// original sanity-merge semantics; only new fields receive defaults.
	s := Settings{Unit: "mm", Terminator: "ENTER", Batch: "BATCH"}
	s.Normalize()
	if s.MaxCh != d.MaxCh || s.MaxMS != d.MaxMS || s.Terminator != d.Terminator ||
		s.Unit != d.Unit || s.Batch != d.Batch || s.NamePrefix != d.NamePrefix ||
		s.IdleMinutes != d.IdleMinutes {
		t.Errorf("normalized legacy settings = %+v", s)
	}
	if s.AlertOnBad || s.AutoReconnect || s.IdleDisconnect {
		t.Error("new feature switches must default to off")
	}
	// invalid explicit values
	s2 := Settings{Digits: 99, MaxCh: 0, DebounceUM: -1, MaxMS: 0, Terminator: "X", Unit: "x", NamePrefix: "", IdleMinutes: -2}
	s2.Normalize()
	if s2.Digits != 2 || s2.MaxCh != 20 || s2.DebounceUM != 10 || s2.MaxMS != 300 ||
		s2.Terminator != "ENTER" || s2.Unit != "mm" || s2.NamePrefix != "GL-BLE#" || s2.IdleMinutes != 5 {
		t.Errorf("normalized invalid settings = %+v", s2)
	}
}

func TestDeviceLabelFallback(t *testing.T) {
	a := NewApp()
	a.SetDeviceLabel("AA:BB:CC:DD:EE:FF")
	// BLE path: injected address is written into DeviceMAC.
	a.FeedRaw("DAT0:m+10.00")
	a.FlushPending()
	// HID / DEMO keep their source tag (different channels avoid debounce).
	a.SetChannel(2)
	a.FeedHIDToken("15.00")
	a.FeedDemoValue(20.5, 3)

	recs := a.RecordsSnapshot()
	if len(recs) != 3 {
		t.Fatalf("records = %d, want 3", len(recs))
	}
	bySrc := map[string]string{}
	for _, r := range recs {
		bySrc[r.Src] = r.DeviceMAC
	}
	if bySrc["BLE"] != "AA:BB:CC:DD:EE:FF" {
		t.Errorf("BLE label = %q", bySrc["BLE"])
	}
	if bySrc["HID"] != "HID" {
		t.Errorf("HID label = %q", bySrc["HID"])
	}
	if bySrc["DEMO"] != "DEMO" {
		t.Errorf("DEMO label = %q", bySrc["DEMO"])
	}
}

func TestDeviceNameInjection(t *testing.T) {
	a := NewApp()
	a.SetDeviceLabel("AA:BB:CC:DD:EE:FF")
	a.SetDeviceName("  GL-BLE#1234  ") // trimmed on inject
	// BLE: DeviceName is the advertising name; DeviceMAC stays the address.
	a.FeedRaw("DAT0:m+10.00")
	a.FlushPending()
	a.SetChannel(2)
	a.FeedHIDToken("15.00")
	a.FeedDemoValue(20.5, 3)

	recs := a.RecordsSnapshot()
	if len(recs) != 3 {
		t.Fatalf("records = %d, want 3", len(recs))
	}
	bySrc := map[string]protocol.Record{}
	for _, r := range recs {
		bySrc[r.Src] = r
	}
	// CSV/MES field untouched.
	if bySrc["BLE"].DeviceMAC != "AA:BB:CC:DD:EE:FF" {
		t.Errorf("BLE DeviceMAC changed to %q", bySrc["BLE"].DeviceMAC)
	}
	if bySrc["BLE"].DeviceName != "GL-BLE#1234" {
		t.Errorf("BLE DeviceName = %q, want GL-BLE#1234", bySrc["BLE"].DeviceName)
	}
	// HID/DEMO keep a non-empty source tag so the column is never blank.
	if bySrc["HID"].DeviceName != "HID" {
		t.Errorf("HID DeviceName = %q", bySrc["HID"].DeviceName)
	}
	if bySrc["DEMO"].DeviceName != "DEMO" {
		t.Errorf("DEMO DeviceName = %q", bySrc["DEMO"].DeviceName)
	}
}

func TestChannelBleName(t *testing.T) {
	a := NewApp()
	s := a.GetSettings()
	s.Channels[0].BleName = "  卡尺-A  " // trimmed
	s.Channels[1].BleName = "   "      // blank -> empty
	app2 := a
	app2.SetSettings(s)

	s2 := app2.GetSettings()
	if got := s2.ChannelBleName(1); got != "卡尺-A" {
		t.Errorf("ChannelBleName(1) = %q, want 卡尺-A", got)
	}
	if got := s2.ChannelBleName(2); got != "" {
		t.Errorf("ChannelBleName(2) = %q, want empty (blank override)", got)
	}
	if got := s2.ChannelBleName(0); got != "" {
		t.Errorf("ChannelBleName(0) = %q, want empty (out of range)", got)
	}
	if got := s2.ChannelBleName(99); got != "" {
		t.Errorf("ChannelBleName(99) = %q, want empty (out of range)", got)
	}
}

func TestDeviceNameFallsBackToMAC(t *testing.T) {
	a := NewApp()
	a.SetDeviceLabel("AA:BB:CC:DD:EE:FF")
	// No name injected: BLE still shows a non-blank identifier.
	a.FeedRaw("DAT0:m+10.00")
	a.FlushPending()
	recs := a.RecordsSnapshot()
	if len(recs) != 1 || recs[0].DeviceName != "AA:BB:CC:DD:EE:FF" {
		t.Fatalf("BLE fallback DeviceName = %q", recs[0].DeviceName)
	}
}
