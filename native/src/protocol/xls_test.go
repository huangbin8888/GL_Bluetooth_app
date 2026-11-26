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
		// No other cell should carry a style, except the 测量值 cell (col 4),
		// which intentionally uses a larger font so the number stands out.
		for ci := 1; ci <= 7; ci++ {
			if ci == 4 {
				continue
			}
			if strings.Contains(dataCells[ci], "ss:StyleID") {
				t.Errorf("row %d cell %d unexpectedly styled", ri, ci)
			}
		}
	}
}

// TestXlsValueFontOneSizeBigger guards the "测量值 slightly larger" request:
// only the 4th column gets the larger-font style; verdict colours, other
// columns and the plain CSV export stay byte-for-byte unchanged in behaviour.
func TestXlsValueFontOneSizeBigger(t *testing.T) {
	recs := []Record{
		{Date: "2026-11-26", T: "09:00:00", ValueUM: 10000, Ch: 1, Verdict: "合格"},
		{Date: "2026-11-26", T: "09:00:01", ValueUM: 15000, Ch: 1, Verdict: "超差"},
	}
	meta := CSVMeta{LowerUM: 9000, UpperUM: 11000}
	xls := RecordsToXls(recs, meta)

	// Style definition must exist and be one point larger than the default 10.
	if !strings.Contains(xls, "ss:ID=\"sValue\"") {
		t.Fatal("missing sValue style definition")
	}
	if !strings.Contains(xls, "<Font ss:Size=\"11\"/>") {
		t.Errorf("sValue font size = ? want 11 (default 10 + 1):\n%s", xls)
	}

	rows := strings.Split(xls, "<Row>")
	for ri := 2; ri < len(rows); ri++ {
		cells := strings.Split(rows[ri], "<Cell")
		if len(cells)-1 != len(CSVHeadersCN) {
			t.Fatalf("row %d cells = %d, want %d", ri, len(cells)-1, len(CSVHeadersCN))
		}
		// 第 4 个单元格 (cells[4]) = 测量值，必须带 sValue。
		if !strings.Contains(cells[4], "ss:StyleID=\"sValue\"") {
			t.Errorf("row %d 测量值 cell missing sValue: %q", ri, cells[4])
		}
		// 判定颜色样式还在最后一列，未被放大样式取代。
		last := cells[len(cells)-1]
		wantStyle := "sPass"
		if recs[ri-2].Verdict == "超差" {
			wantStyle = "sFail"
		}
		if !strings.Contains(last, "ss:StyleID=\""+wantStyle+"\"") {
			t.Errorf("row %d verdict cell lost style %s: %q", ri, wantStyle, last)
		}
	}

	// CSV export must stay plain text with no Excel style markup.
	csv := RecordsToCsv(recs, meta)
	if strings.Contains(csv, "StyleID") || strings.Contains(csv, "sValue") {
		t.Errorf("CSV export must not carry Excel styles:\n%s", csv)
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
