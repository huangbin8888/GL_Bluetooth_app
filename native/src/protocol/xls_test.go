package protocol

import (
	"strings"
	"testing"
)

// TestXlsVerdictColours guards the coloured Excel export: the 8th "是否合格"
// cell must carry the green style for 合格 and the red style for 超差, while
// all other cells stay unstyled. Column order must match CSVHeadersCN exactly.
func TestXlsVerdictColours(t *testing.T) {
	recs := []Record{
		{Date: "2026-11-25", T: "09:00:00", ValueUM: 10000, Ch: 1, Src: "BLE", Verdict: "合格", DeviceMAC: "GL-BLE#001"},
		{Date: "2026-11-25", T: "09:00:01", ValueUM: 15000, Ch: 1, Src: "BLE", Verdict: "超差", DeviceMAC: "GL-BLE#002"},
		{Date: "2026-11-25", T: "09:00:02", ValueUM: 10000, Ch: 2, Src: "HID"}, // derived -> 合格
	}
	xls := RecordsToXls(recs, CSVMeta{LowerUM: 9000, UpperUM: 11000})

	// Must be a well-formed SpreadsheetML workbook that Excel opens natively.
	if !strings.Contains(xls, "<?mso-application progid=\"Excel.Sheet\"?>") {
		t.Fatal("missing Excel mso-application declaration")
	}
	if !strings.Contains(xls, "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"") {
		t.Fatal("missing SpreadsheetML namespace")
	}
	for _, style := range []string{"ss:ID=\"sPass\"", "ss:ID=\"sFail\"", "#C6EFCE", "#FFC7CE"} {
		if !strings.Contains(xls, style) {
			t.Errorf("missing style token %s", style)
		}
	}

	// Split into rows; header + 3 data rows.
	rows := strings.Split(xls, "<Row>")
	if len(rows) != 5 { // first chunk is prologue, then 4 rows
		t.Fatalf("row count = %d, want 4 rows (+prologue):\n%s", len(rows)-1, xls)
	}

	// Header cells must match CSVHeadersCN in order and carry no verdict style.
	header := rows[1]
	cells := strings.Split(header, "<Cell")
	if len(cells)-1 != len(CSVHeadersCN) {
		t.Fatalf("header cells = %d, want %d", len(cells)-1, len(CSVHeadersCN))
	}
	for i, h := range CSVHeadersCN {
		if !strings.Contains(cells[i+1], xmlEscape(h)) {
			t.Errorf("header cell %d missing %q", i, h)
		}
	}

	// Data rows: 8 cells each, last cell styled by verdict.
	want := []struct {
		verdict string
		style   string
	}{
		{"合格", "sPass"},
		{"超差", "sFail"},
		{"合格", "sPass"},
	}
	for ri, w := range want {
		row := rows[ri+2]
		dataCells := strings.Split(row, "<Cell")
		if len(dataCells)-1 != 8 {
			t.Fatalf("data row %d cells = %d, want 8", ri, len(dataCells)-1)
		}
		last := dataCells[8]
		if !strings.Contains(last, w.verdict) {
			t.Errorf("row %d last cell = %q, want verdict %q", ri, last, w.verdict)
		}
		if !strings.Contains(last, "ss:StyleID=\""+w.style+"\"") {
			t.Errorf("row %d (=%s) missing style %s", ri, w.verdict, w.style)
		}
		// No other cell in the row should carry a verdict style.
		for ci := 1; ci <= 7; ci++ {
			if strings.Contains(dataCells[ci], "ss:StyleID") {
				t.Errorf("row %d cell %d unexpectedly styled", ri, ci)
			}
		}
	}
}

// TestXlsEscapesSpecialChars verifies XML escaping does not corrupt data that
// contains &, < or quotes (e.g. a custom channel name or device name).
func TestXlsEscapesSpecialChars(t *testing.T) {
	recs := []Record{{Date: "2026-11-25", T: "09:00:00", ValueUM: 10000, Ch: 1, Verdict: "合格", DeviceMAC: "A&B <test> \"x\""}}
	xls := RecordsToXls(recs, CSVMeta{})
	if !strings.Contains(xls, "A&amp;B &lt;test&gt; &quot;x&quot;") {
		t.Fatalf("special chars not escaped:\n%s", xls)
	}
}
