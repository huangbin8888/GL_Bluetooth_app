// Package core holds the application state and the unified data pipeline:
// feed() -> FrameBuffer -> parseFrame -> debounce -> store -> observers.
// It is UI-independent so it can be unit tested, and is the single path that
// both BLE notifications, HID keyboard capture and demo mode flow through.
package core

import (
	"fmt"
	"math/rand"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"glcaliper/mes"
	"glcaliper/protocol"
)

// MESSettings is the persisted configuration for the optional MES upload
// module. It is appended to Settings with JSON tags; old config files that
// lack these keys deserialize to zero values and are Normalize()-ed to the
// defaults (Enable=false), so behaviour is byte-for-byte the old version.
type MESSettings struct {
	Enable      bool   `json:"enable"`
	URL         string `json:"url"`
	Token       string `json:"token"`
	FieldPrefix string `json:"fieldPrefix"`
	TimeoutMS   int    `json:"timeoutMS"`
	Retry       int    `json:"retry"`
	QueueSize   int    `json:"queueSize"`
	Scope       string `json:"scope"` // "all" | "pass" | "fail"
}

// ToConfig converts persisted settings into a MES client config, applying the
// same defaults the mes package uses.
func (m MESSettings) ToConfig() mes.Config {
	return mes.Config{
		Enable: m.Enable, URL: m.URL, Token: m.Token,
		FieldPrefix: m.FieldPrefix, TimeoutMS: m.TimeoutMS,
		Retry: m.Retry, QueueSize: m.QueueSize,
		Scope: mes.Scope(m.Scope),
	}.Normalize()
}

// LinkMode is the active data source.
type LinkMode string

const (
	ModeAuto LinkMode = "auto"
	ModeBLE  LinkMode = "ble"
	ModeHID  LinkMode = "hid"
	ModeDemo LinkMode = "demo"
)

// ChannelConfig is the per-channel name and independent tolerance.
// UseTol=false (or an invalid range) means "fall back to global tolerance".
type ChannelConfig struct {
	Name    string  `json:"name"`
	BleName string  `json:"bleName"` // user-editable Bluetooth name for this channel (display/config only)
	HidFP   string  `json:"hidFp"`   // learned Raw Input device fingerprint bound to this channel
	UseTol  bool    `json:"useTol"`
	LowerMM float64 `json:"lowerMM"`
	UpperMM float64 `json:"upperMM"`
}

// Settings mirrors the configurable values in P7. New fields are appended at
// the end and carry JSON tags; existing fields keep their original key names.
type Settings struct {
	Unit       string // "mm" | "inch" (display)
	Digits     int
	NominalMM  float64
	LowerTolMM float64
	UpperTolMM float64
	DebounceUM int64
	MaxMS      int64
	MaxCh      int
	AutoEmit   bool
	Terminator string // "ENTER" | "TAB"
	Batch      string

	// —— 新增字段（缺字段由 Normalize/DefaultSettings 兜底）——
	NamePrefix     string                              `json:"namePrefix"`
	Channels       [protocol.MaxChannels]ChannelConfig `json:"channels"`
	AlertOnBad     bool                                `json:"alertOnBad"`
	AutoReconnect  bool                                `json:"autoReconnect"`
	IdleDisconnect bool                                `json:"idleDisconnect"`
	IdleMinutes    int                                 `json:"idleMinutes"`
	LastDeviceAddr string                              `json:"lastDeviceAddr"`
	MES            MESSettings                         `json:"mes"`
}

func DefaultSettings() Settings {
	return Settings{
		Unit: "mm", Digits: 2, NominalMM: 10.0,
		LowerTolMM: -0.05, UpperTolMM: 0.05,
		DebounceUM: 10, MaxMS: 300, MaxCh: protocol.MaxChannels,
		AutoEmit: true, Terminator: "ENTER", Batch: "BATCH",
		NamePrefix: protocol.NamePrefix,
		AlertOnBad: false, AutoReconnect: false, IdleDisconnect: false,
		IdleMinutes: 5, LastDeviceAddr: "",
		MES: MESSettings{
			Enable: false, TimeoutMS: 5000, Retry: 2,
			QueueSize: 500, Scope: "all",
		},
	}
}

func (s Settings) lowerUM() int64 { return int64((s.NominalMM + s.LowerTolMM) * 1000) }
func (s Settings) upperUM() int64 { return int64((s.NominalMM + s.UpperTolMM) * 1000) }

// Normalize folds all legacy sanity merges plus new-field defaults into one
// pure step. Both LoadSettings and onResetSettings go through it so an old
// config file with missing new fields behaves exactly like the old version.
func (s *Settings) Normalize() {
	d := DefaultSettings()
	if s.Unit != "mm" && s.Unit != "inch" {
		s.Unit = d.Unit
	}
	if s.Digits < 0 || s.Digits > 6 {
		s.Digits = d.Digits
	}
	// MaxCh is a legacy field with no UI editor. The Channels array and every
	// selector are fixed at protocol.MaxChannels, so MaxCh no longer caps
	// functionality. Preserve a user's stored value (don't overwrite config),
	// only repairing an out-of-range legacy value. SetChannel and DEMO use
	// protocol.MaxChannels directly.
	if s.MaxCh < 1 || s.MaxCh > protocol.MaxChannels {
		s.MaxCh = protocol.MaxChannels
	}
	if s.DebounceUM < 0 {
		s.DebounceUM = d.DebounceUM
	}
	if s.MaxMS <= 0 {
		s.MaxMS = d.MaxMS
	}
	if s.Terminator != "ENTER" && s.Terminator != "TAB" {
		s.Terminator = d.Terminator
	}
	if s.Batch == "" {
		s.Batch = d.Batch
	}
	if strings.TrimSpace(s.NamePrefix) == "" {
		s.NamePrefix = d.NamePrefix
	}
	if s.IdleMinutes <= 0 {
		s.IdleMinutes = d.IdleMinutes
	}
	// MES defaults: an absent/legacy "mes" object normalizes to disabled with
	// safe queue/timeout/retry values. ToConfig applies the same defaults again.
	mesDefaults := d.MES.ToConfig()
	if s.MES.TimeoutMS <= 0 {
		s.MES.TimeoutMS = mesDefaults.TimeoutMS
	}
	if s.MES.Retry < 0 {
		s.MES.Retry = 0
	}
	// Keep the stored Retry equal to the effective value used by the upload
	// worker (mes.Config.Normalize clamps to 5). Without this, a config saved
	// with 12 echoes 12 after restart but uploads with 5 (P3-1).
	if s.MES.Retry > 5 {
		s.MES.Retry = 5
	}
	if s.MES.QueueSize <= 0 {
		s.MES.QueueSize = mesDefaults.QueueSize
	}
	switch s.MES.Scope {
	case "all", "pass", "fail":
	default:
		s.MES.Scope = "all"
	}
	// Channels[i].Name empty is legal; UseTol=false means "use global".
	// Invalid bounds (UpperMM <= LowerMM) are handled by ChannelBounds.
}

// ChannelBounds is the single source of truth for tolerance. Returns the
// absolute bounds in micrometres for the given channel.
func (s Settings) ChannelBounds(ch int) (int64, int64) {
	if ch >= 1 && ch <= protocol.MaxChannels {
		cfg := s.Channels[ch-1]
		if cfg.UseTol && cfg.UpperMM > cfg.LowerMM {
			return int64(cfg.LowerMM * 1000), int64(cfg.UpperMM * 1000)
		}
	}
	return s.lowerUM(), s.upperUM()
}

// ChannelName returns the configured custom name or the canonical "CHn".
func (s Settings) ChannelName(ch int) string {
	if ch >= 1 && ch <= protocol.MaxChannels {
		if n := strings.TrimSpace(s.Channels[ch-1].Name); n != "" {
			return n
		}
	}
	return "CH" + itoa(ch) // itoa lives in chart.go, same package
}

// ChannelBleName returns the user-edited Bluetooth name configured for a
// channel. It is the display override for the live table's "设备名称" column:
// a blank (or out-of-range) value lets the caller fall back to the advertising
// name captured at connect time. Mirrors ChannelName's 1-based indexing.
func (s Settings) ChannelBleName(ch int) string {
	if ch >= 1 && ch <= protocol.MaxChannels {
		return strings.TrimSpace(s.Channels[ch-1].BleName)
	}
	return ""
}

// Change is pushed to UI observers whenever state changes.
type Change struct {
	Kind string // "record" | "status" | "reset"
	Text string
}

// App is the central state holder.
type App struct {
	mu          sync.Mutex
	Settings    Settings
	Records     []protocol.Record
	fb          *protocol.FrameBuffer
	last        map[int]protocol.DropKey
	channel     int
	mode        LinkMode
	status      string
	seq         int
	demoOn      bool
	demoStop    chan struct{}
	deviceLabel string // address/name of the currently connected BLE device
	deviceName  string // Bluetooth advertising name (GL-BLE#XXX) of that device; UI-only

	changeCh  chan Change
	listeners []func(Change)

	mesMu   sync.RWMutex
	mesCtl  *mes.Client // nil when MES is disabled
	mesStat func(mes.Status)
}

func NewApp() *App {
	return &App{
		Settings: DefaultSettings(),
		Records:  make([]protocol.Record, 0),
		fb:       protocol.NewFrameBuffer(),
		last:     make(map[int]protocol.DropKey),
		channel:  1,
		mode:     ModeAuto,
		status:   "就绪",
		changeCh: make(chan Change, 256),
	}
}

// OnChange registers a UI listener.
func (a *App) OnChange(fn func(Change)) {
	a.mu.Lock()
	a.listeners = append(a.listeners, fn)
	a.mu.Unlock()
}

func (a *App) emit(c Change) {
	a.mu.Lock()
	ls := append([]func(Change){}, a.listeners...)
	a.mu.Unlock()
	for _, fn := range ls {
		fn(c)
	}
}

func (a *App) SetStatus(s string) {
	a.mu.Lock()
	a.status = s
	a.mu.Unlock()
	a.emit(Change{Kind: "status", Text: s})
}

func (a *App) Status() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.status
}

func (a *App) Mode() LinkMode {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.mode
}

func (a *App) SetMode(m LinkMode) {
	a.mu.Lock()
	a.mode = m
	a.mu.Unlock()
}

func (a *App) SetChannel(ch int) {
	a.mu.Lock()
	if ch >= 1 && ch <= protocol.MaxChannels {
		a.channel = ch
	}
	a.mu.Unlock()
}

func (a *App) Channel() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.channel
}

func (a *App) GetSettings() Settings {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.Settings
}

func (a *App) SetSettings(s Settings) {
	a.mu.Lock()
	a.Settings = s
	a.mu.Unlock()
}

// OnMESStatus registers the UI callback for upload status/queue changes.
func (a *App) OnMESStatus(fn func(mes.Status)) {
	a.mesMu.Lock()
	a.mesStat = fn
	a.mesMu.Unlock()
}

// ConfigureMES (re)builds the uploader from the given settings. It stops any
// previous client first. When MES is disabled or the URL is blank, no client
// is kept, so commit() takes the zero-cost path. Safe to call repeatedly.
func (a *App) ConfigureMES(s Settings) {
	a.mesMu.Lock()
	old := a.mesCtl
	a.mesCtl = nil
	cfg := s.MES.ToConfig()
	var next *mes.Client
	if cfg.Enabled() {
		next = mes.New(cfg, func(st mes.Status) {
			a.mesMu.RLock()
			fn := a.mesStat
			a.mesMu.RUnlock()
			if fn != nil {
				fn(st)
			}
		})
	}
	a.mesCtl = next
	a.mesMu.Unlock()

	if old != nil {
		// Drain+stop the previous uploader off the caller's goroutine. Stopping
		// a backed-up queue (bounded retries x timeout per item) must never
		// block the UI thread when settings are saved. Drain still preserves
		// already-queued records; only the UI wait is removed.
		go old.Stop()
	}
	if next != nil {
		next.Start()
	}
}

// MESQueueLen returns the current upload queue depth (0 when disabled).
func (a *App) MESQueueLen() int {
	a.mesMu.RLock()
	defer a.mesMu.RUnlock()
	if a.mesCtl == nil {
		return 0
	}
	return a.mesCtl.Len()
}

// MESActive reports whether uploading is currently enabled.
func (a *App) MESActive() bool {
	a.mesMu.RLock()
	defer a.mesMu.RUnlock()
	return a.mesCtl != nil
}

// SetDeviceLabel injects the address/name of the currently connected BLE
// device. It is written into Record.DeviceMAC by commit() so the exported
// "设备名称" column is populated for live BLE measurements.
func (a *App) SetDeviceLabel(addr string) {
	a.mu.Lock()
	a.deviceLabel = addr
	a.mu.Unlock()
}

// DeviceLabel returns the currently injected device label.
func (a *App) DeviceLabel() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.deviceLabel
}

// SetDeviceName injects the Bluetooth advertising name (e.g. "GL-BLE#1234")
// of the currently connected device. commit() stamps it into Record.DeviceName
// for the live table's "设备名称" column. It is display-only: DeviceMAC (used
// by CSV/MES) is untouched.
func (a *App) SetDeviceName(name string) {
	a.mu.Lock()
	a.deviceName = strings.TrimSpace(name)
	a.mu.Unlock()
}

// DeviceName returns the currently injected Bluetooth advertising name.
func (a *App) DeviceName() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.deviceName
}

// FeedRaw pushes a raw BLE notify chunk into the assembly buffer.
func (a *App) FeedRaw(chunk string) {
	a.feedItems(a.fb.Push(chunk))
}

// FeedHIDToken turns one HID token into a DAT0 frame and feeds it through the
// SAME pipeline as BLE, keeping protocol behaviour identical.
func (a *App) FeedHIDToken(token string) bool {
	frameStr := protocol.HidToFrame(token)
	if frameStr == "" {
		return false
	}
	fb := protocol.NewFrameBuffer()
	items := append(fb.Push(frameStr), fb.Flush()...)
	for _, it := range items {
		if it.Type == "data" {
			a.commit(it.Frame, a.Channel(), "HID")
		}
	}
	return true
}

// HIDBinding is one learned fingerprint-to-channel association.
type HIDBinding struct {
	Fingerprint string
	Channel     int
}

// HIDBoundChannel returns the 1-based channel bound to a HID device
// fingerprint, or 0 when the fingerprint is empty/unbound.
func (a *App) HIDBoundChannel(fp string) int {
	fp = strings.TrimSpace(fp)
	if fp == "" {
		return 0
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	for i := range a.Settings.Channels {
		if a.Settings.Channels[i].HidFP == fp {
			return i + 1
		}
	}
	return 0
}

// BindHID learns/binds a device fingerprint to a channel. Binding one
// fingerprint to a new channel clears any previous binding it had; binding a
// different fingerprint to an occupied channel replaces that channel's token.
// It returns true when the binding changed.
func (a *App) BindHID(fp string, ch int) bool {
	fp = strings.TrimSpace(fp)
	if fp == "" || ch < 1 || ch > protocol.MaxChannels {
		return false
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	// Compute whether anything will change before mutating. The fingerprint
	// must live on exactly one channel: the requested one.
	target := &a.Settings.Channels[ch-1]
	alreadyOnlyHere := target.HidFP == fp
	for i := range a.Settings.Channels {
		if i+1 == ch {
			continue
		}
		if a.Settings.Channels[i].HidFP == fp {
			alreadyOnlyHere = false
		}
	}
	if alreadyOnlyHere {
		return false
	}
	for i := range a.Settings.Channels {
		cfg := &a.Settings.Channels[i]
		if cfg.HidFP == fp && i+1 != ch {
			cfg.HidFP = ""
		}
	}
	target.HidFP = fp
	return true
}

// UnbindHID clears the channel currently bound to a fingerprint.
func (a *App) UnbindHID(fp string) bool {
	fp = strings.TrimSpace(fp)
	if fp == "" {
		return false
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	for i := range a.Settings.Channels {
		if a.Settings.Channels[i].HidFP == fp {
			a.Settings.Channels[i].HidFP = ""
			return true
		}
	}
	return false
}

// UnbindHIDChannel clears any fingerprint bound to a channel.
func (a *App) UnbindHIDChannel(ch int) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if ch >= 1 && ch <= protocol.MaxChannels && a.Settings.Channels[ch-1].HidFP != "" {
		a.Settings.Channels[ch-1].HidFP = ""
		return true
	}
	return false
}

// HIDBindings returns all learned fingerprint-to-channel bindings.
func (a *App) HIDBindings() []HIDBinding {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := make([]HIDBinding, 0)
	for i := range a.Settings.Channels {
		if fp := a.Settings.Channels[i].HidFP; fp != "" {
			out = append(out, HIDBinding{Fingerprint: fp, Channel: i + 1})
		}
	}
	return out
}

// FeedHIDTokenWithDevice turns one HID token into a DAT0 frame and commits it
// to the channel resolved from the device fingerprint. A bound device always
// goes to its bound channel (ignoring the current selector); an unbound device
// is auto-learned onto the current channel on its first reading. When no
// fingerprint is available it behaves exactly like FeedHIDToken.
func (a *App) FeedHIDTokenWithDevice(token, fp string) bool {
	frameStr := protocol.HidToFrame(token)
	if frameStr == "" {
		return false
	}
	ch := a.Channel()
	fp = strings.TrimSpace(fp)
	if fp != "" {
		if bound := a.HIDBoundChannel(fp); bound > 0 {
			ch = bound
		} else {
			// First reading from an unseen device: learn it onto the current
			// channel so subsequent readings auto-file even if the selector moves.
			if a.BindHID(fp, ch) {
				a.emit(Change{Kind: "hidbind", Text: fmt.Sprintf("已绑定 HID 设备到 CH%d", ch)})
			}
		}
	}
	fb := protocol.NewFrameBuffer()
	items := append(fb.Push(frameStr), fb.Flush()...)
	for _, it := range items {
		if it.Type == "data" {
			a.commit(it.Frame, ch, "HID")
		}
	}
	return true
}

// FeedDemoValue feeds a synthetic millimetre value.
func (a *App) FeedDemoValue(mm float64, ch int) {
	sign := "+"
	if mm < 0 {
		sign = "-"
	}
	abs := mm
	if abs < 0 {
		abs = -abs
	}
	frameStr := fmt.Sprintf("DAT0:m%s%.4f", sign, abs)
	fb := protocol.NewFrameBuffer()
	items := fb.Push(frameStr)
	items = append(items, fb.Flush()...)
	for _, it := range items {
		if it.Type == "data" {
			a.commit(it.Frame, ch, "DEMO")
		}
	}
}

func (a *App) feedItems(items []protocol.Item) {
	for _, it := range items {
		switch it.Type {
		case "ack":
			a.emit(Change{Kind: "status", Text: fmt.Sprintf("回包 %s:%s", it.Cmd, it.Status)})
		case "data":
			a.commit(it.Frame, a.Channel(), "BLE")
		}
	}
}

// FlushPending commits a held tail frame (called on idle timer).
func (a *App) FlushPending() {
	a.feedItems(a.fb.Flush())
}

// commit applies debounce and stores one record.
func (a *App) commit(fr *protocol.Frame, ch int, src string) {
	if fr == nil {
		return
	}
	a.mu.Lock()
	s := a.Settings
	now := time.Now().UnixMilli()
	cur := protocol.DropKey{Ch: ch, ValueUM: fr.ValueUM, TS: now}
	prev := a.last[ch]
	if protocol.ShouldDrop(&prev, cur, s.DebounceUM, s.MaxMS) {
		a.mu.Unlock()
		return
	}
	a.last[ch] = cur
	a.seq++
	lo, hi := s.ChannelBounds(ch)
	// Device label: only BLE records pick up the injected connection address;
	// HID and DEMO keep their source tag so the column is never empty.
	devLabel := src
	if src == "BLE" && a.deviceLabel != "" {
		devLabel = a.deviceLabel
	}
	// Device name for the live table's "设备名称" column. BLE records use the
	// injected advertising name (GL-BLE#XXX), falling back to DeviceMAC so the
	// cell is never blank; HID/DEMO keep their source tag. DeviceMAC itself is
	// not changed, so CSV/MES output remains identical.
	devName := src
	if src == "BLE" {
		if a.deviceName != "" {
			devName = a.deviceName
		} else if devLabel != "" {
			devName = devLabel
		}
	}
	rec := protocol.Record{
		Date: protocol.Today(), T: protocol.Clock(), Seq: a.seq,
		Batch: s.Batch, ValueUM: fr.ValueUM, V: protocol.ToMM(fr.ValueUM),
		Raw: fr.Raw, RawValue: fmt.Sprintf("%.4f", fr.RawValue), RawUnit: fr.Unit,
		Verdict: protocol.Verdict(fr.ValueUM, lo, hi),
		Src:     src, DeviceMAC: devLabel, DeviceName: devName, Ch: ch, Inch: fr.Unit == "i", TS: now,
		// Freeze the bounds used for the verdict so CSV/XLS export stays
		// self-consistent after tolerance settings change (P1-1).
		TolLowerUM: lo, TolUpperUM: hi, HasTolSnapshot: true,
	}
	a.Records = append(a.Records, rec)
	total := len(a.Records)
	channelName := s.ChannelName(ch)
	a.mu.Unlock()

	// MES upload happens OUTSIDE a.mu and never blocks the record pipeline.
	// Grab the client under mesMu, release, then a lock-free bounded enqueue.
	a.mesMu.RLock()
	ctl := a.mesCtl
	a.mesMu.RUnlock()
	if ctl != nil {
		ctl.Enqueue(mes.UploadItem{Record: rec, ChannelName: channelName})
	}

	a.emit(Change{Kind: "record", Text: fmt.Sprintf("已记录 #%d", total)})
}

// Records returns a snapshot of current records.
func (a *App) RecordsSnapshot() []protocol.Record {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := make([]protocol.Record, len(a.Records))
	copy(out, a.Records)
	return out
}

// RecordsStamp returns a cheap (count, lastSeq) fingerprint of the record set
// without cloning the slice. UI models use it to detect whether their cached
// view needs rebuilding, avoiding a full copy on every table cell query.
func (a *App) RecordsStamp() (int, int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	lastSeq := -1
	if len(a.Records) > 0 {
		lastSeq = a.Records[len(a.Records)-1].Seq
	}
	return len(a.Records), lastSeq
}

func (a *App) Count() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.Records)
}

// Stats returns current statistics; pass rate is judged per record's channel
// using the same ChannelBounds source as commit() and the chart.
func (a *App) Stats() protocol.Stats {
	a.mu.Lock()
	recs := append([]protocol.Record{}, a.Records...)
	resolve := a.Settings.ChannelBounds
	a.mu.Unlock()
	return protocol.CalcStatsWithResolver(recs, resolve)
}

// Reset clears all records and sequence state.
func (a *App) Reset() {
	a.mu.Lock()
	a.Records = make([]protocol.Record, 0)
	a.seq = 0
	a.last = make(map[int]protocol.DropKey)
	a.fb.Reset()
	a.mu.Unlock()
	a.emit(Change{Kind: "reset", Text: "已清空记录"})
}

// DeleteLast removes the most recent record.
func (a *App) DeleteLast() {
	a.mu.Lock()
	if len(a.Records) > 0 {
		a.Records = a.Records[:len(a.Records)-1]
	}
	a.mu.Unlock()
	a.emit(Change{Kind: "reset", Text: "已删除最后一条"})
}

// DeleteIndex removes a record by 0-based index.
func (a *App) DeleteIndex(idx int) {
	a.mu.Lock()
	if idx >= 0 && idx < len(a.Records) {
		a.Records = append(a.Records[:idx], a.Records[idx+1:]...)
	}
	a.mu.Unlock()
	a.emit(Change{Kind: "reset", Text: "已删除"})
}

// ImportCsv merges records from CSV. Returns imported/bad counts.
func (a *App) ImportCsv(path string) (int, int, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, 0, err
	}
	parsed := protocol.CsvParse(string(data))
	s := a.GetSettings()
	recs, bad := protocol.CsvToRecords(parsed, protocol.CSVDefaults{
		ChannelNo: 1, Date: protocol.Today(), Batch: s.Batch,
		LowerUM: s.lowerUM(), UpperUM: s.upperUM(),
		ChannelNames: channelNamesOf(s), ResolveTol: s.ChannelBounds,
	})
	a.mu.Lock()
	// dedup against existing by date|time|ch|um
	seen := make(map[string]bool)
	for _, r := range a.Records {
		seen[fmt.Sprintf("%s|%s|%d|%d", r.Date, r.T, r.Ch, r.ValueUM)] = true
	}
	added := 0
	for _, r := range recs {
		key := fmt.Sprintf("%s|%s|%d|%d", r.Date, r.T, r.Ch, r.ValueUM)
		if seen[key] {
			continue
		}
		seen[key] = true
		a.seq++
		r.Seq = a.seq
		a.Records = append(a.Records, r)
		added++
	}
	a.mu.Unlock()
	a.emit(Change{Kind: "reset", Text: fmt.Sprintf("导入 %d 条", added)})
	return added, bad, nil
}

// exportRecordsAndMeta selects records by scope and builds CSV metadata shared
// by ExportCsv and ExportXls.
func (a *App) exportRecordsAndMeta(scope string) ([]protocol.Record, protocol.CSVMeta) {
	recs := a.RecordsSnapshot()
	if scope != "" && scope != "all" {
		filtered := make([]protocol.Record, 0)
		for _, r := range recs {
			if fmt.Sprintf("CH%d", r.Ch) == scope {
				filtered = append(filtered, r)
			}
		}
		recs = filtered
	}
	s := a.GetSettings()
	meta := protocol.CSVMeta{
		Date: protocol.Today(), Batch: s.Batch,
		Nominal:  fmt.Sprintf("%.4f", s.NominalMM),
		LowerTol: fmt.Sprintf("%.4f", s.LowerTolMM),
		UpperTol: fmt.Sprintf("%.4f", s.UpperTolMM),
		LowerUM:  s.lowerUM(), UpperUM: s.upperUM(),
		ChannelNames: channelNamesOf(s), ResolveTol: s.ChannelBounds,
	}
	return recs, meta
}

// ExportCsv writes records to path. scope: "all" or "CHn".
func (a *App) ExportCsv(path, scope string) (int, error) {
	recs, meta := a.exportRecordsAndMeta(scope)
	csv := protocol.RecordsToCsv(recs, meta)
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return 0, err
	}
	if err := os.WriteFile(path, []byte(csv), 0644); err != nil {
		return 0, err
	}
	return len(recs), nil
}

// ExportXls writes the same records as a coloured Excel 2003 XML Spreadsheet
// (.xls): 合格 cells green, 超差 cells red. scope: "all" or "CHn".
func (a *App) ExportXls(path, scope string) (int, error) {
	recs, meta := a.exportRecordsAndMeta(scope)
	xls := protocol.RecordsToXls(recs, meta)
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return 0, err
	}
	if err := os.WriteFile(path, []byte(xls), 0644); err != nil {
		return 0, err
	}
	return len(recs), nil
}

// SortedByChannel returns records sorted by channel then time.
func (a *App) SortedByChannel() []protocol.Record {
	recs := a.RecordsSnapshot()
	sort.SliceStable(recs, func(i, j int) bool {
		if recs[i].Ch != recs[j].Ch {
			return recs[i].Ch < recs[j].Ch
		}
		return recs[i].TS < recs[j].TS
	})
	return recs
}

// StartDemo begins synthetic measurements on a timer.
func (a *App) StartDemo() {
	a.mu.Lock()
	if a.demoOn {
		a.mu.Unlock()
		return
	}
	a.demoOn = true
	a.demoStop = make(chan struct{})
	stop := a.demoStop
	a.mu.Unlock()
	a.SetStatus("演示模式运行中")
	go func() {
		rng := rand.New(rand.NewSource(time.Now().UnixNano()))
		base := a.GetSettings().NominalMM
		ticker := time.NewTicker(600 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				v := base + (rng.Float64()-0.5)*0.16
				ch := rng.Intn(protocol.MaxChannels) + 1
				a.FeedDemoValue(v, ch)
			}
		}
	}()
}

func (a *App) StopDemo() {
	a.mu.Lock()
	if !a.demoOn {
		a.mu.Unlock()
		return
	}
	a.demoOn = false
	if a.demoStop != nil {
		close(a.demoStop)
	}
	a.mu.Unlock()
	a.SetStatus("演示模式已停止")
}

func (a *App) IsDemo() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.demoOn
}

// ToggleDemo starts or stops demo mode.
func (a *App) ToggleDemo() bool {
	if a.IsDemo() {
		a.StopDemo()
		return false
	}
	a.StartDemo()
	return true
}

// channelNamesOf flattens Settings.Channels into the []string required by the
// protocol CSV layer for custom-name export and reverse-lookup import.
func channelNamesOf(s Settings) []string {
	names := make([]string, protocol.MaxChannels)
	for i, c := range s.Channels {
		names[i] = c.Name
	}
	return names
}

// DefaultExportDir returns a sensible default export directory next to the exe.
func DefaultExportDir() string {
	exe, err := os.Executable()
	if err != nil {
		return "exports"
	}
	return filepath.Join(filepath.Dir(exe), "exports")
}
