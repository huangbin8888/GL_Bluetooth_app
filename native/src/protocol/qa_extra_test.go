package protocol

import (
	"strings"
	"testing"
)

// TestQA_LegacyEnglish16ColImport verifies the 7-col export change did not
// break importing an OLD-format 16-column English CSV (a key compatibility
// boundary the engineer's roundtrip tests never exercised with a real legacy
// header row).
func TestQA_LegacyEnglish16ColImport(t *testing.T) {
	legacy := "\ufeff" + strings.Join(CSVHeaders, ",") + "\r\n" +
		"2025-05-01,09:30:00,7,B1,ITEM,10.000,-0.050,0.050," +
		"10.5000,10.5000,m,合格,DEMO,DEV-X,,CH2\r\n"
	parsed := CsvParse(legacy)
	if !parsed.BOMRemoved {
		t.Error("BOM not detected on legacy file")
	}
	if len(parsed.Skipped) != 0 {
		t.Errorf("legacy headers skipped: %v", parsed.Skipped)
	}
	if len(parsed.Rows) != 1 {
		t.Fatalf("legacy rows = %d, want 1", len(parsed.Rows))
	}
	recs, bad := CsvToRecords(parsed, CSVDefaults{LowerUM: 9000, UpperUM: 11000})
	if bad != 0 || len(recs) != 1 {
		t.Fatalf("legacy import bad=%d n=%d", bad, len(recs))
	}
	r := recs[0]
	if r.Ch != 2 || r.ValueUM != 10500 || r.DeviceMAC != "DEV-X" {
		t.Errorf("legacy record = %+v", r)
	}
}

// TestQA_LegacyEnglish16ColLegacyChannel keeps a CH8 row from a real English
// header row and asserts it is retained, not clamped or dropped. CH8 was
// legacy when the palette held 7 channels; with the 20-channel expansion it
// is a normal channel, so Legacy must now be false while the row survives.
func TestQA_LegacyEnglish16ColLegacyChannel(t *testing.T) {
	legacy := "\ufeff" + strings.Join(CSVHeaders, ",") + "\r\n" +
		"2025-05-02,10:00:00,8,B1,ITEM,10.000,-0.050,0.050," +
		"11.0000,11.0000,m,超差,HID,DEV-Y,,CH8\r\n"
	parsed := CsvParse(legacy)
	if len(parsed.Skipped) != 0 {
		t.Errorf("legacy headers skipped: %v", parsed.Skipped)
	}
	recs, bad := CsvToRecords(parsed, CSVDefaults{LowerUM: 9000, UpperUM: 11000})
	if bad != 0 || len(recs) != 1 {
		t.Fatalf("legacy import bad=%d n=%d", bad, len(recs))
	}
	if recs[0].Ch != 8 || recs[0].Legacy {
		t.Errorf("channel = %d legacy=%v, want 8/false (CH8 is normal at 20 channels)", recs[0].Ch, recs[0].Legacy)
	}
}

// TestQA_ChineseCSVSpecialCharChannelNameRoundtrip uses a custom channel name
// containing a comma and a quote (forces csvEscape quoting) to verify the
// export -> parse -> reverse-lookup chain preserves the channel assignment.
func TestQA_ChineseCSVSpecialCharChannelNameRoundtrip(t *testing.T) {
	names := make([]string, MaxChannels)
	names[6] = `外"径,mm` // CH7 with special characters
	recs := []Record{
		{Date: "2026-11-19", T: "08:00:01", ValueUM: 25000, RawUnit: "m", Ch: 7, Src: "BLE", DeviceMAC: "AA:AA"},
	}
	csv := RecordsToCsv(recs, CSVMeta{ChannelNames: names})
	if !strings.HasPrefix(csv, "\ufeff") {
		t.Fatal("missing BOM")
	}
	if !strings.HasSuffix(csv, "\r\n") {
		t.Error("CSV must end with CRLF")
	}
	parsed := CsvParse(csv)
	if len(parsed.Skipped) != 0 {
		t.Errorf("skipped = %v", parsed.Skipped)
	}
	back, bad := CsvToRecords(parsed, CSVDefaults{ChannelNames: names})
	if bad != 0 || len(back) != 1 {
		t.Fatalf("roundtrip bad=%d n=%d\nCSV:\n%s", bad, len(back), csv)
	}
	if back[0].Ch != 7 {
		t.Errorf("special-char channel name lost: ch=%d, want 7\nCSV:\n%s", back[0].Ch, csv)
	}
	if back[0].DeviceMAC != "AA:AA" || back[0].ValueUM != 25000 {
		t.Errorf("roundtrip row = %+v", back[0])
	}
}
