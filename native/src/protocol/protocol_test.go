package protocol

import (
	"strings"
	"testing"
	"time"
)

func TestParseFrame(t *testing.T) {
	cases := []struct {
		raw    string
		wantUM int64
		wantOK bool
	}{
		{"DAT0:m+12.345", 12345, true},
		{"DAT1:m-1.248", -1248, true},
		{"DAT0:i+0.5000", 12700, true}, // 0.5 inch = 12.7mm
		{"DAT0:i-1.2485", -31712, true},
		{"DAT0:m+0", 0, true},
		{"DAT0:m+12.34x", 0, false},
		{"MEAS:OK", 0, false},
		{"", 0, false},
	}
	for _, c := range cases {
		f := ParseFrame(c.raw)
		if c.wantOK {
			if f == nil || f.ValueUM != c.wantUM {
				t.Errorf("ParseFrame(%q) = %+v, want UM %d", c.raw, f, c.wantUM)
			}
		} else if f != nil {
			t.Errorf("ParseFrame(%q) = %+v, want nil", c.raw, f)
		}
	}
}

func TestFrameBufferGluedAndPartial(t *testing.T) {
	fb := NewFrameBuffer()
	// two glued frames + an ACK, then a truncated tail that completes next chunk
	out := fb.Push("DAT0:m+10.00MEAS:OKDAT1:m-2.00DAT0:m+3.")
	if len(out) != 3 {
		t.Fatalf("expected 3 items (1 ack + 2 data), got %d: %+v", len(out), out)
	}
	acks, data := 0, 0
	for _, it := range out {
		if it.Type == "ack" {
			acks++
		} else {
			data++
		}
	}
	if acks != 1 || data != 2 {
		t.Errorf("acks=%d data=%d", acks, data)
	}
	if got := fb.Pending(); got != "DAT0:m+3." {
		t.Errorf("pending = %q, want DAT0:m+3.", got)
	}
	out2 := fb.Push("25")
	if len(out2) != 0 {
		t.Errorf("held tail should stay pending until flush, got %+v", out2)
	}
	out3 := fb.Flush()
	if len(out3) != 1 || out3[0].Type != "data" || out3[0].Frame.ValueUM != 3250 {
		t.Errorf("flush continuation = %+v, want frame 3.25mm", out3)
	}
}

func TestFrameBufferFlush(t *testing.T) {
	fb := NewFrameBuffer()
	fb.Push("DAT0:m+7.50")
	out := fb.Flush()
	if len(out) != 1 || out[0].Frame.ValueUM != 7500 {
		t.Errorf("Flush = %+v, want 7.50mm", out)
	}
	if fb.Pending() != "" {
		t.Errorf("pending should be empty after flush, got %q", fb.Pending())
	}
}

func TestShouldDrop(t *testing.T) {
	base := time.Now().UnixMilli()
	prev := &DropKey{Ch: 1, ValueUM: 10000, TS: base}
	// same channel, small delta, short time -> drop
	if !ShouldDrop(prev, DropKey{1, 10005, base + 100}, 10, 300) {
		t.Error("expected drop for tiny same-channel delta")
	}
	// clock rollback: absolute value must still drop
	if !ShouldDrop(prev, DropKey{1, 10005, base - 100}, 10, 300) {
		t.Error("expected drop despite clock rollback")
	}
	// too large time gap -> keep
	if ShouldDrop(prev, DropKey{1, 10005, base + 400}, 10, 300) {
		t.Error("expected keep for >300ms")
	}
	// cross channel -> never drop
	if ShouldDrop(prev, DropKey{2, 10005, base + 100}, 10, 300) {
		t.Error("cross-channel must never drop")
	}
	// large delta -> keep
	if ShouldDrop(prev, DropKey{1, 10050, base + 100}, 10, 300) {
		t.Error("expected keep for >10um delta")
	}
}

func TestVerdictAndStats(t *testing.T) {
	recs := []Record{
		{ValueUM: 10000}, {ValueUM: 10020}, {ValueUM: 9980}, {ValueUM: 10100},
	}
	s := CalcStats(recs, 9950, 10050)
	if s.N != 4 || s.OK != 3 || s.OKRate != 75 {
		t.Errorf("stats = %+v, want N=4 OK=3 rate=75", s)
	}
	if s.MinUM != 9980 || s.MaxUM != 10100 {
		t.Errorf("min/max = %d/%d", s.MinUM, s.MaxUM)
	}
	if Verdict(10100, 9950, 10050) != "超差" {
		t.Error("10100 should be 超差")
	}
}

func TestHidSplitAndToFrame(t *testing.T) {
	toks := HidSplit("12.34\r-1.2 inch\r0.5", "ENTER")
	if len(toks) != 3 {
		t.Fatalf("tokens = %v", toks)
	}
	if f := HidToFrame("-1.2485 in"); f != "DAT0:i-1.2485" {
		t.Errorf("hidToFrame = %q", f)
	}
	// inner trim() strips trailing CR just like the JS implementation
	if f := HidToFrame("-1.2485 in\r"); f != "DAT0:i-1.2485" {
		t.Errorf("trimmed trailing CR = %q", f)
	}
	if f := HidToFrame("12.30"); f != "DAT0:m+12.30" {
		t.Errorf("mm default = %q", f)
	}
}

func TestValidateNam(t *testing.T) {
	if ok, _, _ := ValidateNam(NamePrefix + "Caliper01"); !ok {
		t.Error("valid NAM rejected")
	}
	if ok, _, r := ValidateNam("Caliper01"); ok || !strings.Contains(r, "前缀") {
		t.Errorf("missing prefix should mention 前缀: %s", r)
	}
	if ok, _, _ := ValidateNam(NamePrefix + "bad name"); ok {
		t.Error("space in suffix should be rejected")
	}
}

func TestCsvRoundTrip(t *testing.T) {
	recs := []Record{
		{Date: "2026-11-18", T: "10:00:00", Seq: 1, ValueUM: 10000, RawValue: "10.00", RawUnit: "m", Ch: 1, Src: "BLE", Verdict: "合格"},
		{Date: "2026-11-18", T: "10:00:05", Seq: 2, ValueUM: 12500, RawValue: "12.50", RawUnit: "m", Ch: 2, Src: "HID", Verdict: "合格"},
	}
	csv := RecordsToCsv(recs, CSVMeta{Batch: "B1", LowerUM: 9000, UpperUM: 11000})
	if !strings.HasPrefix(csv, "\ufeff") {
		t.Error("CSV should start with BOM")
	}
	parsed := CsvParse(csv)
	if parsed.BOMRemoved != true {
		t.Error("BOM should be detected")
	}
	if len(parsed.Skipped) != 0 {
		t.Errorf("unexpected skipped headers: %v", parsed.Skipped)
	}
	back, bad := CsvToRecords(parsed, CSVDefaults{LowerUM: 9000, UpperUM: 11000})
	if bad != 0 || len(back) != 2 {
		t.Errorf("roundtrip bad=%d n=%d", bad, len(back))
	}
	if back[1].ValueUM != 12500 || back[1].Ch != 2 {
		t.Errorf("roundtrip row2 = %+v", back[1])
	}
}

func TestFriendlyUnit(t *testing.T) {
	cases := map[string]string{
		"m": "mm", "M": "mm", "mm": "mm", " i ": "inch", "i": "inch",
		"in": "inch", "INCH": "inch", "": "mm", "garbage": "mm",
	}
	for in, want := range cases {
		if got := FriendlyUnit(in); got != want {
			t.Errorf("FriendlyUnit(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestCSVHeadersCN7Columns(t *testing.T) {
	if len(CSVHeadersCN) != 8 {
		t.Fatalf("CSVHeadersCN length = %d, want 8", len(CSVHeadersCN))
	}
	want := []string{"日期", "时间", "设备名称", "测量值", "公差上限", "公差下限", "通道名称", "是否合格"}
	for i, h := range want {
		if CSVHeadersCN[i] != h {
			t.Errorf("header[%d] = %q, want %q", i, CSVHeadersCN[i], h)
		}
	}
	// every Chinese header must have a canonical alias for roundtrip
	for _, h := range CSVHeadersCN {
		if _, ok := aliases[h]; !ok {
			t.Errorf("missing alias for %q", h)
		}
	}
	// legacy 16-col whitelist must remain intact
	if len(CSVHeaders) != 16 {
		t.Errorf("legacy CSVHeaders length = %d, want 16", len(CSVHeaders))
	}
}

func TestCSVChinese7ColRoundTripWithNames(t *testing.T) {
	names := make([]string, 7)
	names[0] = "外径"
	names[1] = "内径"
	recs := []Record{
		{Date: "2026-11-18", T: "10:00:00", ValueUM: 10000, RawUnit: "m", Ch: 1, Src: "BLE", DeviceMAC: "AA:BB:CC:DD:EE:FF", Verdict: "合格"},
		{Date: "2026-11-18", T: "10:00:05", ValueUM: 12500, RawUnit: "m", Ch: 2, Src: "HID", DeviceMAC: "HID", Verdict: "合格"},
	}
	resolve := func(ch int) (int64, int64) {
		if ch == 1 {
			return 9000, 11000
		}
		return 12000, 13000
	}
	csv := RecordsToCsv(recs, CSVMeta{ChannelNames: names, ResolveTol: resolve})
	if !strings.HasPrefix(csv, "\ufeff") {
		t.Fatal("missing BOM")
	}
	head := strings.Split(strings.TrimPrefix(csv, "\ufeff"), "\r\n")[0]
	if head != strings.Join(CSVHeadersCN, ",") {
		t.Errorf("export header = %q", head)
	}
	parsed := CsvParse(csv)
	if len(parsed.Skipped) != 0 {
		t.Errorf("skipped = %v", parsed.Skipped)
	}
	back, bad := CsvToRecords(parsed, CSVDefaults{ChannelNames: names, ResolveTol: resolve})
	if bad != 0 || len(back) != 2 {
		t.Fatalf("roundtrip bad=%d n=%d", bad, len(back))
	}
	if back[0].Ch != 1 || back[0].DeviceMAC != "AA:BB:CC:DD:EE:FF" {
		t.Errorf("row1 = %+v", back[0])
	}
	if back[1].Ch != 2 || back[1].ValueUM != 12500 {
		t.Errorf("row2 = %+v", back[1])
	}
	if back[0].Verdict != "合格" || back[1].Verdict != "合格" {
		t.Errorf("resolver verdicts: %q %q", back[0].Verdict, back[1].Verdict)
	}
}

func TestCSVChineseAliasesParse(t *testing.T) {
	text := "\ufeff日期,时间,设备名称,测量值,公差上限,公差下限,通道名称\r\n" +
		"2026-01-01,08:00:00,DEV1,10.5000,11.0000,10.0000,CH3\r\n"
	parsed := CsvParse(text)
	if len(parsed.Skipped) != 0 {
		t.Errorf("skipped = %v", parsed.Skipped)
	}
	if len(parsed.Rows) != 1 {
		t.Fatalf("rows = %d", len(parsed.Rows))
	}
	row := parsed.Rows[0]
	for k, want := range map[string]string{
		"DATE": "2026-01-01", "TIME": "08:00:00", "DEVICE_MAC": "DEV1",
		"VALUE_MM": "10.5000", "UPPER_TOL": "11.0000", "LOWER_TOL": "10.0000", "CHANNEL": "CH3",
	} {
		if row[k] != want {
			t.Errorf("%s = %q, want %q", k, row[k], want)
		}
	}
}

func TestNumericCustomChannelNameRoundTrip(t *testing.T) {
	// CH6 is given the purely numeric custom name "3". On import the exact
	// name match must win over strconv.Atoi, otherwise it lands on CH3.
	names := make([]string, 7)
	names[5] = "3"
	recs := []Record{
		{Date: "2026-11-19", T: "11:00:00", ValueUM: 10000, RawUnit: "m", Ch: 6, Src: "BLE", Verdict: "合格"},
	}
	csv := RecordsToCsv(recs, CSVMeta{ChannelNames: names})
	parsed := CsvParse(csv)
	if len(parsed.Skipped) != 0 {
		t.Fatalf("skipped = %v", parsed.Skipped)
	}
	back, bad := CsvToRecords(parsed, CSVDefaults{ChannelNames: names})
	if bad != 0 || len(back) != 1 {
		t.Fatalf("bad=%d n=%d", bad, len(back))
	}
	if back[0].Ch != 6 {
		t.Errorf("numeric custom name mapped to CH%d, want CH6", back[0].Ch)
	}
	// Canonical CHn still resolves when no custom name is configured.
	plain, plainBad := CsvToRecords(parsed, CSVDefaults{})
	if plainBad != 0 || len(plain) != 1 || plain[0].Ch != 3 {
		t.Errorf("canonical fallback bad=%d n=%d ch=%v", plainBad, len(plain), plain)
	}
}

func TestCalcStatsWithResolver(t *testing.T) {
	recs := []Record{
		{Ch: 1, ValueUM: 10000}, // OK on CH1 [9k,11k]
		{Ch: 1, ValueUM: 20000}, // OUT on CH1
		{Ch: 2, ValueUM: 20000}, // OK on CH2 [19k,21k]
		{Ch: 2, ValueUM: 20000}, // OK on CH2
	}
	resolve := func(ch int) (int64, int64) {
		if ch == 1 {
			return 9000, 11000
		}
		return 19000, 21000
	}
	st := CalcStatsWithResolver(recs, resolve)
	if st.N != 4 || st.OK != 3 || st.OKRate != 75 {
		t.Errorf("stats = %+v, want OK=3 rate=75", st)
	}
	if st.MeanUM != 17500 || st.MinUM != 10000 || st.MaxUM != 20000 {
		t.Errorf("stats values = %+v", st)
	}
	// old constant wrapper must be unchanged (only the 10mm row fits 9k..11k)
	old := CalcStats(recs, 9000, 11000)
	if old.OK != 1 {
		t.Errorf("legacy CalcStats OK = %d, want 1", old.OK)
	}
}

// TestCSVVerdictColumn guards the 8th "是否合格" column: each row carries the
// plain-text verdict (合格/超差) matching the record, and it roundtrips back in
// via the VERDICT alias without breaking the existing 7 columns.
func TestCSVVerdictColumn(t *testing.T) {
	recs := []Record{
		{Date: "2026-11-25", T: "09:00:00", ValueUM: 10000, Ch: 1, Src: "BLE", Verdict: "合格"},
		{Date: "2026-11-25", T: "09:00:01", ValueUM: 15000, Ch: 1, Src: "BLE", Verdict: "超差"},
		// Verdict left empty -> must be derived from tolerance.
		{Date: "2026-11-25", T: "09:00:02", ValueUM: 10000, Ch: 1, Src: "HID"},
	}
	csv := RecordsToCsv(recs, CSVMeta{LowerUM: 9000, UpperUM: 11000})
	lines := strings.Split(strings.TrimPrefix(csv, "\ufeff"), "\r\n")
	if len(lines) != 5 { // header + 3 rows + trailing CRLF
		t.Fatalf("lines = %d, want 5:\n%s", len(lines), csv)
	}
	// Verify header has 8 fields, last being 是否合格.
	head := strings.Split(lines[0], ",")
	if len(head) != 8 || head[7] != "是否合格" {
		t.Fatalf("header = %v, want 8 cols with last 是否合格", head)
	}
	wantVerdict := []string{"合格", "超差", "合格"}
	for i, w := range wantVerdict {
		cells := strings.Split(lines[i+1], ",")
		if len(cells) != 8 {
			t.Fatalf("row %d has %d cols, want 8: %v", i, len(cells), cells)
		}
		if cells[7] != w {
			t.Errorf("row %d verdict = %q, want %q", i, cells[7], w)
		}
	}
	// Roundtrip: verdict column must map back to VERDICT, no skipped cells.
	parsed := CsvParse(csv)
	if len(parsed.Skipped) != 0 {
		t.Errorf("skipped = %v", parsed.Skipped)
	}
	back, bad := CsvToRecords(parsed, CSVDefaults{LowerUM: 9000, UpperUM: 11000})
	if bad != 0 || len(back) != 3 {
		t.Fatalf("roundtrip bad=%d n=%d", bad, len(back))
	}
	for i, w := range wantVerdict {
		if back[i].Verdict != w {
			t.Errorf("roundtrip row %d verdict = %q, want %q", i, back[i].Verdict, w)
		}
	}
}
