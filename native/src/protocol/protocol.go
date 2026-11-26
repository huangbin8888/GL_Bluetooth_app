// Package protocol is the data/protocol heart of the native caliper tool.
// It is an equivalent port of mvp/protocol.js and contains NO UI / NO DOM code,
// so it can be unit-tested with the plain Go toolchain.
package protocol

import (
	"math"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	ServiceUUID = "0000ffb0-0000-1000-8000-00805f9b34fb"
	CharUUID    = "0000ffb2-0000-1000-8000-00805f9b34fb"
	NamePrefix  = "GL-BLE#"
	MaxChannels = 20
	UMPerMM     = 1000
	UMPerInch   = 25400 // 1 inch = 25.4 mm
)

// 16 columns (15 base + CHANNEL). Kept as the PARSE whitelist so legacy
// English CSV files remain importable; do NOT use it for export anymore.
var CSVHeaders = []string{
	"DATE", "TIME", "SEQ", "BATCH", "ITEM", "NOMINAL", "LOWER_TOL",
	"UPPER_TOL", "VALUE_MM", "RAW_VALUE", "RAW_UNIT", "VERDICT",
	"SOURCE", "DEVICE_MAC", "NOTE", "CHANNEL",
}

// CSVHeadersCN is the 8-column Chinese header used for EXPORT only.
// The canonical keys below are what CsvParse maps them to via aliases.
// The last column "是否合格" carries the plain-text verdict (合格/超差).
var CSVHeadersCN = []string{
	"日期", "时间", "设备名称", "测量值", "公差上限", "公差下限", "通道名称", "是否合格",
}

var aliases = map[string]string{
	"日期": "DATE", "时间": "TIME", "序号": "SEQ", "批次": "BATCH",
	"检验项目": "ITEM", "公称值": "NOMINAL", "下偏差": "LOWER_TOL",
	"上偏差": "UPPER_TOL", "实测值": "VALUE_MM", "测量值": "VALUE_MM",
	"原始值": "RAW_VALUE", "原始单位": "RAW_UNIT", "判定": "VERDICT",
	"来源": "SOURCE", "设备MAC": "DEVICE_MAC", "备注": "NOTE", "通道": "CHANNEL",
	// Chinese export headers — required for export -> import roundtrip.
	"设备名称": "DEVICE_MAC", "公差上限": "UPPER_TOL",
	"公差下限": "LOWER_TOL", "通道名称": "CHANNEL",
	"是否合格": "VERDICT", // 8th export column; roundtrips as VERDICT
}

var (
	ackRe       = regexp.MustCompile(`(MEAS|CLR|MOD|NAM):(OK|ERR)`)
	frameFullRe = regexp.MustCompile(`^DAT([01]):([mi])([+-])(\d+(\.\d+)?)$`)
	frameRe     = regexp.MustCompile(`^DAT([01]):([mi])([+-])(\d+(\.\d+)?)`)
	partialRe   = regexp.MustCompile(`(^|[^A-Za-z0-9])(D|DA|DAT|DAT0|DAT1|DAT0:|DAT1:)$`)
	datHeadRe   = regexp.MustCompile(`DAT[01]:`)
	namSuffixRe = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
	hidTokenRe  = regexp.MustCompile(`\A([+-]?)(\d+(\.\d+)?)\s*(mm|inch|in|m|i)?\z`)
)

// ---------- core value types ----------

// Frame is one parsed DAT0/DAT1 frame.
type Frame struct {
	Kind     string
	Unit     string // "m" | "i"
	Sign     string // "+" | "-"
	RawValue float64
	ValueUM  int64
	Raw      string
}

// Item is one output of FrameBuffer.Push: either a data frame or an ACK.
type Item struct {
	Type   string // "data" | "ack"
	Frame  *Frame
	Cmd    string // ack: MEAS|CLR|MOD|NAM
	Status string // ack: OK|ERR
}

// Record is one measurement row, matching the JS record shape.
type Record struct {
	Date      string
	T         string
	Seq       int
	Batch     string
	Item      string
	Nominal   string
	LowerTol  string
	UpperTol  string
	ValueUM   int64
	V         float64 // rendering layer reads V
	Raw       string
	RawValue  string
	RawUnit   string
	Verdict   string
	Src       string
	DeviceMAC string
	// DeviceName is the human-facing Bluetooth advertising name (GL-BLE#XXX)
	// captured at connect time. It is UI-display-only: CSV/MES keep DeviceMAC,
	// so exporting and uploading output is byte-for-byte unchanged.
	DeviceName string
	Note       string
	Ch         int
	Legacy     bool
	Inch       bool
	TS         int64 // unix milliseconds
	// TolLowerUM/TolUpperUM freeze the absolute tolerance bounds that were in
	// force when this record was committed. Verdict is likewise frozen at
	// commit time; keeping the bounds alongside it makes exported CSV/XLS rows
	// self-consistent even if tolerance settings change later.
	// HasTolSnapshot distinguishes a real (possibly zero) snapshot from records
	// built without one (legacy/imported), which fall back to export metadata.
	TolLowerUM     int64
	TolUpperUM     int64
	HasTolSnapshot bool
}

// DropKey is the minimal shape needed by ShouldDrop.
type DropKey struct {
	Ch      int
	ValueUM int64
	TS      int64
}

// ParseFrame parses one complete frame. Returns nil when incomplete/invalid.
func ParseFrame(raw string) *Frame {
	m := frameFullRe.FindStringSubmatch(strings.TrimSpace(raw))
	if m == nil {
		return nil
	}
	n, err := strconv.ParseFloat(m[4], 64)
	if err != nil || math.IsNaN(n) || math.IsInf(n, 0) {
		return nil
	}
	factor := float64(UMPerMM)
	if m[2] == "i" {
		factor = float64(UMPerInch)
	}
	um := int64(math.Round(n * factor))
	if m[3] == "-" {
		um = -um
	}
	return &Frame{
		Kind: "DAT" + m[1], Unit: m[2], Sign: m[3],
		RawValue: n, ValueUM: um, Raw: m[0],
	}
}

// FrameBuffer reassembles byte streams: partial frames, glued frames, ACKs.
// It is safe for concurrent use: the BLE notify callback (FeedRaw) and the
// periodic idle-flush ticker (FlushPending) call Push/Flush from different
// goroutines, so every access to buf is serialized by mu. Without this lock
// the two writers race on f.buf (Go data race: lost/torn frames under BLE).
type FrameBuffer struct {
	mu  sync.Mutex
	buf string
}

func NewFrameBuffer() *FrameBuffer { return &FrameBuffer{} }
func (f *FrameBuffer) Reset() {
	f.mu.Lock()
	f.buf = ""
	f.mu.Unlock()
}
func (f *FrameBuffer) Pending() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.buf
}

func segmentFrame(seg string) *Frame {
	m := frameRe.FindStringSubmatch(seg)
	if m == nil {
		return nil
	}
	return ParseFrame(m[0])
}

// Push appends a chunk and returns every complete frame/ACK parsed so far.
// Safe for concurrent use with Flush/Reset/Pending.
func (f *FrameBuffer) Push(chunk string) []Item {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.buf += chunk
	out := make([]Item, 0)

	// Extract and strip ACKs first.
	for _, m := range ackRe.FindAllStringSubmatch(f.buf, -1) {
		out = append(out, Item{Type: "ack", Cmd: m[1], Status: m[2]})
	}
	f.buf = ackRe.ReplaceAllString(f.buf, "")

	// Locate DAT heads.
	starts := make([]int, 0)
	for _, loc := range datHeadRe.FindAllStringIndex(f.buf, -1) {
		starts = append(starts, loc[0])
	}
	if len(starts) == 0 {
		if partialRe.MatchString(f.buf) {
			m := partialRe.FindStringSubmatch(f.buf)
			f.buf = m[0]
		} else {
			f.buf = ""
		}
		return out
	}

	for i, s := range starts {
		end := len(f.buf)
		if i+1 < len(starts) {
			end = starts[i+1]
		}
		seg := f.buf[s:end]
		if i < len(starts)-1 {
			if fr := segmentFrame(seg); fr != nil {
				out = append(out, Item{Type: "data", Frame: fr})
			}
		} else {
			f.buf = seg // possibly truncated tail
		}
	}
	return out
}

// Flush commits the held tail frame once no further chunk arrives.
// Safe for concurrent use with Push/Reset/Pending.
func (f *FrameBuffer) Flush() []Item {
	f.mu.Lock()
	defer f.mu.Unlock()
	seg := f.buf
	f.buf = ""
	if fr := segmentFrame(seg); fr != nil {
		return []Item{{Type: "data", Frame: fr}}
	}
	return nil
}

// ---------- formatting ----------

func ToMM(um int64) float64   { return float64(um) / float64(UMPerMM) }
func ToInch(um int64) float64 { return float64(um) / float64(UMPerInch) }

func fmtFixed(v float64, digits int) string {
	return strconv.FormatFloat(v, 'f', digits, 64)
}

func FmtMM(um int64, digits int) string {
	if digits < 0 {
		digits = 2
	}
	return fmtFixed(ToMM(um), digits)
}

func FmtSignedMM(um int64, digits int) string {
	if digits < 0 {
		digits = 2
	}
	s := fmtFixed(ToMM(um), digits)
	if um >= 0 {
		return "+" + s
	}
	return s
}

// FriendlyUnit maps the raw protocol unit token to a human-readable label.
// RawUnit is "m" or "i" and must NEVER be displayed directly.
func FriendlyUnit(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "m", "mm":
		return "mm"
	case "i", "in", "inch":
		return "inch"
	default:
		return "mm"
	}
}

// ShouldDrop: debounce only within the same channel, |Δum|<=maxDeltaUM and
// |Δts|<=maxMs. maxDeltaUM<=0 disables value debounce (every reading is kept),
// which makes "0" an honest "off" instead of silently using 10µm.
// maxMs<=0 uses 300. NOTE: clock rollback is handled by absolute value, so
// never mis-drops.
func ShouldDrop(prev *DropKey, cur DropKey, maxDeltaUM, maxMS int64) bool {
	if prev == nil || prev.Ch != cur.Ch {
		return false
	}
	if maxDeltaUM <= 0 {
		return false
	}
	if maxMS <= 0 {
		maxMS = 300
	}
	delta := cur.ValueUM - prev.ValueUM
	if delta < 0 {
		delta = -delta
	}
	if delta > maxDeltaUM {
		return false
	}
	dt := cur.TS - prev.TS
	if dt < 0 {
		dt = -dt
	}
	if dt > maxMS {
		return false
	}
	return true
}

func Verdict(um, lowerUM, upperUM int64) string {
	if um >= lowerUM && um <= upperUM {
		return "合格"
	}
	return "超差"
}

// Stats mirrors protocol.stats (population standard deviation).
type Stats struct {
	N      int
	MeanUM float64
	MaxUM  int64
	MinUM  int64
	SDUM   float64
	OK     int
	OKRate int
}

// TolResolver returns the tolerance bounds (in micrometres) for a channel.
// It is the single injected seam that lets commit/Stats/chart/CSV share the
// exact same tolerance source (core.Settings.ChannelBounds).
type TolResolver func(ch int) (lowerUM, upperUM int64)

// CalcStats keeps the original constant-tolerance behaviour by wrapping
// CalcStatsWithResolver; existing callers and tests stay source-compatible.
func CalcStats(recs []Record, lowerUM, upperUM int64) Stats {
	return CalcStatsWithResolver(recs, func(int) (int64, int64) {
		return lowerUM, upperUM
	})
}

// CalcStatsWithResolver computes the same 7 statistics, resolving the
// tolerance independently for every record's channel.
func CalcStatsWithResolver(recs []Record, resolve TolResolver) Stats {
	if len(recs) == 0 {
		return Stats{}
	}
	if resolve == nil {
		resolve = func(int) (int64, int64) { return 0, 0 }
	}
	sum := float64(0)
	maxV, minV := recs[0].ValueUM, recs[0].ValueUM
	ok := 0
	for _, r := range recs {
		v := r.ValueUM
		sum += float64(v)
		if v > maxV {
			maxV = v
		}
		if v < minV {
			minV = v
		}
		lo, hi := resolve(r.Ch)
		if Verdict(v, lo, hi) == "合格" {
			ok++
		}
	}
	mean := sum / float64(len(recs))
	variance := float64(0)
	for _, r := range recs {
		d := float64(r.ValueUM) - mean
		variance += d * d
	}
	variance /= float64(len(recs))
	return Stats{
		N: len(recs), MeanUM: mean, MaxUM: maxV, MinUM: minV,
		SDUM: math.Sqrt(variance), OK: ok,
		OKRate: int(math.Round(float64(ok) / float64(len(recs)) * 100)),
	}
}

// ---------- CSV ----------

func csvEscape(v string) string {
	if strings.ContainsAny(v, ",\"\r\n") {
		return `"` + strings.ReplaceAll(v, `"`, `""`) + `"`
	}
	return v
}

// CSVParseResult is the parsed shape of a CSV file.
type CSVParseResult struct {
	Headers    []string // empty string = skipped column
	RawHeaders []string
	Skipped    []string
	Rows       []map[string]string
	BOMRemoved bool
}

// CsvParse is an RFC-4180-ish parser; strips UTF-8 BOM from the first cell.
func CsvParse(text string) CSVParseResult {
	bom := strings.HasPrefix(text, "\ufeff")
	if bom {
		text = strings.TrimPrefix(text, "\ufeff")
	}
	rows := make([][]string, 0)
	var row []string
	cell := strings.Builder{}
	quoted := false
	runes := []rune(text)
	for i := 0; i < len(runes); i++ {
		c := runes[i]
		if quoted {
			if c == '"' {
				if i+1 < len(runes) && runes[i+1] == '"' {
					cell.WriteRune('"')
					i++
				} else {
					quoted = false
				}
			} else {
				cell.WriteRune(c)
			}
		} else if c == '"' && cell.Len() == 0 {
			quoted = true
		} else if c == ',' {
			row = append(row, cell.String())
			cell.Reset()
		} else if c == '\n' {
			row = append(row, cell.String())
			rows = append(rows, row)
			row = nil
			cell.Reset()
		} else if c == '\r' {
			// ignore, \n terminates
		} else {
			cell.WriteRune(c)
		}
	}
	if cell.Len() > 0 || len(row) > 0 {
		row = append(row, cell.String())
		rows = append(rows, row)
	}

	// Drop leading all-empty rows.
	for len(rows) > 0 {
		allEmpty := true
		for _, x := range rows[0] {
			if strings.TrimSpace(x) != "" {
				allEmpty = false
				break
			}
		}
		if !allEmpty {
			break
		}
		rows = rows[1:]
	}
	res := CSVParseResult{BOMRemoved: bom}
	if len(rows) == 0 {
		return res
	}

	res.RawHeaders = make([]string, len(rows[0]))
	for i, h := range rows[0] {
		res.RawHeaders[i] = strings.TrimSpace(h)
	}
	res.Headers = make([]string, len(rows[0]))
	headerSet := make(map[string]bool)
	for _, h := range CSVHeaders {
		headerSet[h] = true
	}
	for i, h := range res.RawHeaders {
		clean := strings.TrimPrefix(h, "\ufeff")
		clean = strings.TrimSpace(clean)
		key := strings.ToUpper(clean)
		if headerSet[key] {
			res.Headers[i] = key
		} else if a, ok := aliases[clean]; ok {
			res.Headers[i] = a
		} else {
			if clean == "" {
				res.Skipped = append(res.Skipped, "(空列"+strconv.Itoa(i)+")")
			} else {
				res.Skipped = append(res.Skipped, clean)
			}
		}
	}
	for _, r := range rows[1:] {
		o := make(map[string]string)
		for i, key := range res.Headers {
			if key == "" {
				continue
			}
			if i < len(r) {
				o[key] = r[i]
			} else {
				o[key] = ""
			}
		}
		nonEmpty := false
		for _, v := range o {
			if v != "" {
				nonEmpty = true
				break
			}
		}
		if nonEmpty {
			res.Rows = append(res.Rows, o)
		}
	}
	return res
}

// CSVDefaults supplies fallbacks for csvToRecords.
type CSVDefaults struct {
	ChannelNo    int
	Date         string
	Batch        string
	LowerUM      int64
	UpperUM      int64
	ChannelNames []string    // index 0..6 -> custom CHn name; reverse lookup on import
	ResolveTol   TolResolver // optional per-channel tolerance resolver
}

// resolveChannelFromCell accepts "CHn"/"n" and custom names written by
// RecordsToCsv. Custom names MUST be matched before numeric parsing: a purely
// numeric custom name such as "3" belongs to its configured channel (e.g.
// CH6), and must not be mistaken for CH3 by strconv.Atoi.
func resolveChannelFromCell(cell string, d CSVDefaults) int {
	cell = strings.TrimSpace(cell)
	if cell == "" {
		return 0
	}
	// Exact custom-name match (first configured name wins).
	for i, name := range d.ChannelNames {
		if name != "" && name == cell {
			return i + 1
		}
	}
	// Canonical "CHn" or legacy bare-number form.
	numPart := strings.TrimPrefix(cell, "CH")
	numPart = strings.TrimPrefix(numPart, "ch")
	if n, e := strconv.Atoi(strings.TrimSpace(numPart)); e == nil && n > 0 {
		return n
	}
	return 0
}

// CsvToRecords converts parsed rows into the same record shape as live data.
func CsvToRecords(parsed CSVParseResult, d CSVDefaults) (records []Record, badRows int) {
	seen := make(map[string]bool)
	records = make([]Record, 0)
	for _, o := range parsed.Rows {
		mm, err := strconv.ParseFloat(o["VALUE_MM"], 64)
		if err != nil || math.IsNaN(mm) || math.IsInf(mm, 0) {
			badRows++
			continue
		}
		um := int64(math.Round(mm * float64(UMPerMM)))
		ch := resolveChannelFromCell(o["CHANNEL"], d)
		if ch < 1 {
			ch = d.ChannelNo
			if ch < 1 {
				ch = 1
			}
		}
		legacy := ch > MaxChannels // legacy CH8-CH10 kept, never clamped/dropped
		key := o["DATE"] + "|" + o["TIME"] + "|" + strconv.Itoa(ch) + "|" + strconv.FormatInt(um, 10)
		if seen[key] {
			continue
		}
		seen[key] = true

		seq, _ := strconv.Atoi(o["SEQ"])
		if seq == 0 {
			seq = len(records) + 1
		}
		t := o["TIME"]
		if t == "" {
			t = "00:00:00"
		}
		raw := o["RAW_VALUE"]
		if raw == "" {
			src := o["SOURCE"]
			if src == "" {
				src = "IMPORT"
			}
			raw = src + ":m" + strconv.FormatFloat(mm, 'f', -1, 64)
		}
		rawValue := o["RAW_VALUE"]
		if rawValue == "" {
			rawValue = strconv.FormatFloat(mm, 'f', -1, 64)
		}
		rawUnit := o["RAW_UNIT"]
		if rawUnit == "" {
			rawUnit = "m"
		}
		date := o["DATE"]
		if date == "" {
			date = d.Date
		}
		batch := o["BATCH"]
		if batch == "" {
			batch = d.Batch
		}
		verd := o["VERDICT"]
		if verd == "" {
			lo, hi := d.LowerUM, d.UpperUM
			if d.ResolveTol != nil {
				lo, hi = d.ResolveTol(ch)
			}
			verd = Verdict(um, lo, hi)
		}
		src := o["SOURCE"]
		if src == "" {
			src = "IMPORT"
		}
		rec := Record{
			Date: date, T: t, Seq: seq, Batch: batch,
			ValueUM: um, V: mm, Raw: raw, RawValue: rawValue, RawUnit: rawUnit,
			Verdict: verd, Src: src, DeviceMAC: o["DEVICE_MAC"], Note: o["NOTE"],
			Ch: ch, Legacy: legacy, Inch: rawUnit == "i", TS: time.Now().UnixMilli(),
		}
		// Freeze absolute tolerance bounds from the CSV when they are genuine
		// bounds for this row: both cells parse and they reproduce the stored
		// verdict. This keeps export->import->export self-consistent (P1-1)
		// while rejecting legacy deviation-style columns that would be wrong.
		if lf, e1 := strconv.ParseFloat(strings.TrimSpace(o["LOWER_TOL"]), 64); e1 == nil {
			if uf, e2 := strconv.ParseFloat(strings.TrimSpace(o["UPPER_TOL"]), 64); e2 == nil {
				l := int64(math.Round(lf * float64(UMPerMM)))
				u := int64(math.Round(uf * float64(UMPerMM)))
				if u >= l && Verdict(um, l, u) == verd {
					rec.TolLowerUM, rec.TolUpperUM, rec.HasTolSnapshot = l, u, true
				}
			}
		}
		records = append(records, rec)
	}
	return
}

// CSVMeta supplies metadata written into exported rows.
// ChannelNames / ResolveTol are appended fields so existing callers that only
// set the global LowerUM/UpperUM keep working unchanged.
type CSVMeta struct {
	Date, Batch, Item, Nominal, LowerTol, UpperTol string
	LowerUM, UpperUM                               int64
	ChannelNames                                   []string
	ResolveTol                                     TolResolver
}

// channelLabel writes the custom channel name when configured, otherwise the
// canonical "CHn" literal (which also keeps legacy imports working).
func channelLabel(ch int, names []string) string {
	if ch >= 1 && ch <= MaxChannels && ch-1 < len(names) {
		if n := strings.TrimSpace(names[ch-1]); n != "" {
			return n
		}
	}
	return "CH" + strconv.Itoa(ch)
}

// exportRowValues returns the 8 display cells for one record, aligned with
// CSVHeadersCN. Shared by RecordsToCsv and RecordsToXls so the two formats
// always carry exactly the same content and column order.
func exportRowValues(r Record, meta CSVMeta) []string {
	lo, hi := meta.LowerUM, meta.UpperUM
	if r.HasTolSnapshot {
		// Prefer the bounds frozen at commit time so the exported lower/upper
		// always match the frozen Verdict (P1-1: no self-contradictory rows).
		lo, hi = r.TolLowerUM, r.TolUpperUM
	} else if meta.ResolveTol != nil {
		// Legacy/imported records without a snapshot keep the old behaviour.
		lo, hi = meta.ResolveTol(r.Ch)
	}
	verdict := r.Verdict
	if verdict == "" {
		verdict = Verdict(r.ValueUM, lo, hi)
	}
	row := map[string]string{
		"DATE": r.Date, "TIME": r.T,
		"DEVICE_MAC": r.DeviceMAC,
		"VALUE_MM":   FmtMM(r.ValueUM, 4),
		"UPPER_TOL":  FmtMM(hi, 4),
		"LOWER_TOL":  FmtMM(lo, 4),
		"CHANNEL":    channelLabel(r.Ch, meta.ChannelNames),
		"VERDICT":    verdict,
	}
	cells := make([]string, len(CSVHeadersCN))
	for j, k := range CSVHeadersCN {
		cells[j] = row[mustCanonical(k)]
	}
	return cells
}

// RecordsToCsv renders records into a BOM-prefixed RFC-4180 CSV string with
// the Chinese 8-column layout. Tolerance values are absolute millimetres for
// the record's own channel (via ResolveTol), never a hard-coded global value.
// The final "是否合格" column is plain text (合格/超差); CSV cannot carry colour.
func RecordsToCsv(recs []Record, meta CSVMeta) string {
	lines := make([]string, 0, len(recs)+1)
	lines = append(lines, strings.Join(CSVHeadersCN, ","))
	for _, r := range recs {
		cells := exportRowValues(r, meta)
		for j := range cells {
			cells[j] = csvEscape(cells[j])
		}
		lines = append(lines, strings.Join(cells, ","))
	}
	return "\ufeff" + strings.Join(lines, "\r\n") + "\r\n"
}

// xmlEscape escapes the five XML predefined entities for SpreadsheetML text.
func xmlEscape(v string) string {
	v = strings.ReplaceAll(v, "&", "&amp;")
	v = strings.ReplaceAll(v, "<", "&lt;")
	v = strings.ReplaceAll(v, ">", "&gt;")
	v = strings.ReplaceAll(v, "\"", "&quot;")
	v = strings.ReplaceAll(v, "'", "&apos;")
	return v
}

// RecordsToXls renders the same 8-column Chinese layout as an Excel 2003 XML
// Spreadsheet (SpreadsheetML). Excel/WPS open it directly as a native .xls and
// keep the per-cell colours: the final "是否合格" cell is green for 合格 and
// red for 超差. Pure standard library, no third-party dependency.
func RecordsToXls(recs []Record, meta CSVMeta) string {
	var b strings.Builder
	b.WriteString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n")
	b.WriteString("<?mso-application progid=\"Excel.Sheet\"?>\r\n")
	b.WriteString("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"")
	b.WriteString(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"")
	b.WriteString(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"")
	b.WriteString(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">\r\n")
	b.WriteString(" <Styles>\r\n")
	b.WriteString("  <Style ss:ID=\"sPass\"><Interior ss:Color=\"#C6EFCE\" ss:Pattern=\"Solid\"/><Font ss:Color=\"#006100\"/></Style>\r\n")
	b.WriteString("  <Style ss:ID=\"sFail\"><Interior ss:Color=\"#FFC7CE\" ss:Pattern=\"Solid\"/><Font ss:Color=\"#9C0006\"/></Style>\r\n")
	b.WriteString(" </Styles>\r\n")
	b.WriteString(" <Worksheet ss:Name=\"记录\">\r\n")
	b.WriteString("  <Table>\r\n")
	widths := []string{"90", "84", "180", "96", "96", "96", "140", "88"}
	for _, w := range widths {
		b.WriteString("   <Column ss:Width=\"" + w + "\"/>\r\n")
	}
	// Header row.
	b.WriteString("   <Row>\r\n")
	for _, h := range CSVHeadersCN {
		b.WriteString("    <Cell><Data ss:Type=\"String\">" + xmlEscape(h) + "</Data></Cell>\r\n")
	}
	b.WriteString("   </Row>\r\n")
	// Data rows: cells stored as String to preserve the exact CSV display text.
	for _, r := range recs {
		cells := exportRowValues(r, meta)
		b.WriteString("   <Row>\r\n")
		for j, v := range cells {
			style := ""
			if j == len(cells)-1 {
				switch v {
				case "合格":
					style = " ss:StyleID=\"sPass\""
				case "超差":
					style = " ss:StyleID=\"sFail\""
				}
			}
			b.WriteString("    <Cell" + style + "><Data ss:Type=\"String\">" + xmlEscape(v) + "</Data></Cell>\r\n")
		}
		b.WriteString("   </Row>\r\n")
	}
	b.WriteString("  </Table>\r\n")
	b.WriteString(" </Worksheet>\r\n")
	b.WriteString("</Workbook>\r\n")
	return b.String()
}

// mustCanonical maps a Chinese export header to its canonical parse key.
// All 7 headers are guaranteed to be present in aliases.
func mustCanonical(headerCN string) string {
	if k, ok := aliases[headerCN]; ok {
		return k
	}
	return headerCN
}

// ---------- commands / HID ----------

// ValidateNam validates a NAM rename command.
func ValidateNam(name string) (ok bool, cmd, reason string) {
	s := strings.TrimSpace(name)
	if s == "" {
		return false, "", "名称不能为空"
	}
	if len([]rune(s)) > 16 {
		return false, "", "名称最长 16 字符（当前 " + strconv.Itoa(len([]rune(s))) + "）"
	}
	if !strings.HasPrefix(s, NamePrefix) {
		return false, "", "必须保留 " + NamePrefix + " 前缀"
	}
	suffix := strings.TrimPrefix(s, NamePrefix)
	if suffix == "" {
		return false, "", "前缀后需有自定义名称"
	}
	if !namSuffixRe.MatchString(suffix) {
		return false, "", "仅支持字母、数字、下划线与连字符"
	}
	return true, "NAM:" + s, ""
}

// HidSplit splits a HID keyboard stream on the terminator, tolerating the other.
func HidSplit(text, terminator string) []string {
	s := strings.ReplaceAll(text, "\r\n", "\r")
	primary := "\r"
	if terminator == "TAB" {
		primary = "\t"
	}
	out := make([]string, 0)
	for _, part := range strings.Split(s, primary) {
		for _, x := range strings.FieldsFunc(part, func(r rune) bool {
			return r == '\r' || r == '\n' || r == '\t'
		}) {
			tok := strings.TrimSpace(x)
			if tok != "" {
				out = append(out, tok)
			}
		}
	}
	return out
}

// HidToFrame turns one HID token into the same DAT0 frame the BLE chain uses.
// Returns "" when the token is not a valid measurement.
func HidToFrame(token string) string {
	m := hidTokenRe.FindStringSubmatch(strings.TrimSpace(token))
	if m == nil {
		return ""
	}
	unit := "m"
	u := strings.ToLower(m[4])
	if u == "i" || u == "inch" || u == "in" {
		unit = "i"
	}
	sign := "+"
	if m[1] == "-" {
		sign = "-"
	}
	return "DAT0:" + unit + sign + m[2]
}

// ---------- time / filenames ----------

func pad2(n int) string {
	if n < 10 {
		return "0" + strconv.Itoa(n)
	}
	return strconv.Itoa(n)
}

func NowStamp() string {
	d := time.Now()
	return strconv.Itoa(d.Year()) + pad2(int(d.Month())) + pad2(d.Day()) + "_" +
		pad2(d.Hour()) + pad2(d.Minute()) + pad2(d.Second())
}

func Today() string {
	d := time.Now()
	return strconv.Itoa(d.Year()) + "-" + pad2(int(d.Month())) + "-" + pad2(d.Day())
}

func Clock() string {
	d := time.Now()
	return pad2(d.Hour()) + ":" + pad2(d.Minute()) + ":" + pad2(d.Second())
}

func FileName(batch, stamp, scope, ext string) string {
	if batch == "" {
		batch = "BATCH"
	}
	if stamp == "" {
		stamp = NowStamp()
	}
	if scope == "" {
		scope = "ALL"
	}
	return batch + "_" + stamp + "_" + scope + "." + ext
}
