//go:build windows

package ui

import (
	"fmt"
	"os"

	"github.com/lxn/walk"
	. "github.com/lxn/walk/declarative"
	"glcaliper/core"
	"glcaliper/mes"
	"glcaliper/protocol"
)

// DeviceBridge abstracts HID/BLE hardware control so the UI never imports
// hardware packages directly (main.go injects the concrete implementation).
type DeviceBridge interface {
	// HID
	HIDStart() error
	HIDStop()
	HIDEnabled() bool
	HIDIsRaw() bool // true when Raw Input (per-device fingerprint) is active
	HIDSetTerminator(string)
	HIDClearBuffer()
	HIDListDevices() []HIDDeviceInfo // Raw Input keyboard devices with fingerprints

	// BLE
	BLEAvailable() bool
	BLEScan() ([]DeviceView, error)
	BLEConnect(addr string) error
	BLEDisconnect()
	BLEConnected() bool
	BLEWrite(cmd string) error
	SetNamePrefix(prefix string)
}

// DeviceView is one scanned device shown in the UI.
type DeviceView struct {
	Address string
	Name    string
	RSSI    int16
}

// HIDDeviceInfo is one keyboard-class HID device with its Raw Input
// fingerprint, surfaced by the settings self-check button.
type HIDDeviceInfo struct {
	Fingerprint string
}

// UI owns all controls and wires them to core + bridge.
type UI struct {
	app    *core.App
	bridge DeviceBridge
	main   *walk.MainWindow
	model  *RecordModel

	// navigation
	tabWidget *walk.TabWidget

	// dynamic controls
	lblReading    *walk.Label
	lblUnit       *walk.Label
	lblMode       *walk.Label
	lblEnv        *walk.Label
	lblEnvDetail  *walk.Label
	lblStatus     *walk.StatusBarItem
	lblStatus2    *walk.StatusBarItem
	tableView     *walk.TableView
	lblCount      *walk.Label
	lblStats      *walk.Label
	lblHidState   *walk.Label
	lblHidLive    *walk.Label
	btnHidToggle  *walk.PushButton
	btnDemo       *walk.PushButton
	btnScan       *walk.PushButton
	btnConnect    *walk.PushButton
	btnDisconnect *walk.PushButton
	lblDevices    *walk.Label
	lstDevices    *walk.ListBox
	cmbChannel    *walk.ComboBox
	cmbFilter     *walk.ComboBox

	// analysis labels
	anTotal *walk.Label
	anOK    *walk.Label
	anRate  *walk.Label
	anMean  *walk.Label
	anMax   *walk.Label
	anMin   *walk.Label
	anSD    *walk.Label

	// export controls
	exportScope  *walk.ComboBox
	exportResult *walk.Label

	// settings inputs
	edNominal  *walk.LineEdit
	edLower    *walk.LineEdit
	edUpper    *walk.LineEdit
	edDigits   *walk.LineEdit
	edDebounce *walk.LineEdit
	cmbUnit    *walk.ComboBox
	cmbTerm    *walk.ComboBox
	edBatch    *walk.LineEdit

	// analysis chart
	chartWidget    *walk.CustomWidget
	chartData      core.ChartData
	chartKind      core.ChartKind
	chartFilter    string
	btnChartKind   *walk.PushButton
	cmbChartFilter *walk.ComboBox

	// settings: per-channel name + Bluetooth name + independent tolerance
	edChName    [protocol.MaxChannels]*walk.LineEdit
	edChBleName [protocol.MaxChannels]*walk.LineEdit
	ckChUseTol  [protocol.MaxChannels]*walk.CheckBox
	edChLower   [protocol.MaxChannels]*walk.LineEdit
	edChUpper   [protocol.MaxChannels]*walk.LineEdit
	// settings: per-channel bound HID device fingerprint (read-only display)
	lblChHidFp [protocol.MaxChannels]*walk.Label

	// settings: alert / link / device registration
	ckAlertOnBad     *walk.CheckBox
	ckAutoReconnect  *walk.CheckBox
	ckIdleDisconnect *walk.CheckBox
	edIdleMinutes    *walk.LineEdit
	edNamePrefix     *walk.LineEdit

	// settings: MES upload
	ckMESEnable  *walk.CheckBox
	edMESURL     *walk.LineEdit
	edMESToken   *walk.LineEdit
	edMESPrefix  *walk.LineEdit
	edMESTimeout *walk.LineEdit
	edMESRetry   *walk.LineEdit
	cmbMESScope  *walk.ComboBox
	lblMESStatus *walk.Label

	deviceList  []string // display strings for ListBox
	deviceAddrs []string // matching addresses
	deviceNames []string // matching Bluetooth advertising names (GL-BLE#XXX)
}

// New creates the UI bound to app + bridge.
func New(app *core.App, bridge DeviceBridge) *UI {
	return &UI{
		app: app, bridge: bridge,
		deviceList:  make([]string, 0),
		deviceAddrs: make([]string, 0),
		deviceNames: make([]string, 0),
	}
}

// buildStamp returns the exe file's modification time as a compact tag, so a
// running instance visibly identifies which build it is. Repeated filter
// reports were traced to stale exe instances; showing the build time in the
// title/status bar makes version mismatches obvious at a glance.
func buildStamp() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	st, err := os.Stat(exe)
	if err != nil {
		return ""
	}
	return st.ModTime().Format("2006-01-02 15:04")
}

// Run builds and runs the main window.
func (u *UI) Run() error {
	initFonts()
	stamp := buildStamp()
	title := "广陆蓝牙卡尺助手 · 原生版 v1.2"
	if stamp != "" {
		title += " · 构建 " + stamp
	}
	u.model = NewRecordModel(u.app)
	s := u.app.GetSettings()
	u.model.SetDisplay(s.Digits, s.Unit, "", false)
	u.chartKind = core.ChartLine
	u.chartFilter = ""
	u.chartData = core.ChartData{Points: make([]core.ChartPoint, 0), UnitLabel: "mm"}

	// Start MES uploader from persisted settings before any record can arrive.
	u.app.ConfigureMES(s)

	u.app.OnChange(func(c core.Change) {
		if u.main == nil {
			return
		}
		u.main.Synchronize(func() { u.onChange(c) })
	})

	// MES upload status arrives on the uploader's worker goroutine; marshal it
	// to the UI thread before touching labels.
	u.app.OnMESStatus(func(st mes.Status) {
		if u.main == nil {
			return
		}
		u.main.Synchronize(func() { u.onMESStatus(st) })
	})

	statusItem := walk.NewStatusBarItem()
	status2Item := walk.NewStatusBarItem()
	u.lblStatus, u.lblStatus2 = statusItem, status2Item

	mw := &MainWindow{
		AssignTo: &u.main,
		Title:    title,
		MinSize:  Size{760, 480},
		Size:     Size{1180, 720},
		Layout:   VBox{MarginsZero: true, SpacingZero: true},
		StatusBarItems: []StatusBarItem{
			{AssignTo: &u.lblStatus, Text: "就绪", Width: 380},
			{AssignTo: &u.lblStatus2, Text: "", Width: 240},
		},
		Children: []Widget{
			Composite{
				Layout:     HBox{Margins: Margins{14, 9, 14, 9}, Spacing: 12},
				Background: SolidColorBrush{Color: colorDark},
				Children: []Widget{
					Label{Text: "◈", Font: Font{PointSize: 16, Bold: true}, TextColor: colorWarn},
					Label{Text: "广陆蓝牙卡尺助手", Font: Font{PointSize: 12, Bold: true}, TextColor: colorDarkText},
					Label{Text: "PRECISION · v1.2", Font: Font{Family: "Consolas", PointSize: 9, Bold: true}, TextColor: colorWarn},
					HSpacer{},
					Label{AssignTo: &u.lblMode, Text: "检测环境中…", TextColor: colorDarkText},
					Label{AssignTo: &u.lblEnv, Text: "", TextColor: colorSub},
				},
			},
			TabWidget{
				AssignTo:      &u.tabWidget,
				StretchFactor: 1, // fill all vertical space left by the header
				Pages: []TabPage{
					u.pageDetect(),
					u.pageConnect(),
					u.pageAnalysis(),
					u.pageExport(),
					u.pageSettings(),
				},
			},
		},
	}
	if err := mw.Create(); err != nil {
		return err
	}
	u.afterCreate()
	u.refreshAll()
	u.main.Run()
	return nil
}

func (u *UI) onChange(c core.Change) {
	switch c.Kind {
	case "status":
		u.lblStatus.SetText(c.Text)
	case "record":
		u.model.PublishRowsReset()
		u.repaintTable() // owner-data rows need an explicit redraw under a filter
		// Newest-first display: the latest record is now row 0 (top of the
		// table). Do NOT call SetCurrentIndex(n-1) here: it used to yank the
		// view to the bottom on every sample, which made a long table jump
		// and flicker while streaming. LVSICF_NOSCROLL in walk's setItemCount
		// keeps the scroll position stable, so the newest row stays visible
		// at the top and older rows remain reachable by manual scrolling.
		u.refreshReading()
		u.refreshCountStats()
		// out-of-tolerance alert (settings switch + source filter inside)
		if u.app.GetSettings().AlertOnBad {
			recs := u.app.RecordsSnapshot()
			if len(recs) > 0 {
				u.maybeAlertOnBad(recs[len(recs)-1])
			}
		}
	case "reset":
		u.model.PublishRowsReset()
		u.repaintTable()
		u.refreshReading()
		u.refreshCountStats()
		u.lblStatus.SetText(c.Text)
	case "hidbind":
		// A new device fingerprint was auto-learned: persist it and refresh the
		// per-channel binding labels so the settings page reflects the change.
		u.SaveSettingsNow()
		u.refreshHIDBindingLabels()
		u.lblStatus.SetText(c.Text)
	}
}

func (u *UI) refreshAll() {
	u.refreshMode()
	u.refreshReading()
	u.refreshCountStats()
	u.refreshSettingsInputs()
	u.lblStatus.SetText(u.app.Status())
	if u.lblStatus2 != nil {
		if stamp := buildStamp(); stamp != "" {
			u.lblStatus2.SetText("构建 " + stamp)
		}
	}
}

func (u *UI) setStatus(text string) {
	if u.lblStatus != nil {
		u.lblStatus.SetText(text)
	}
	u.app.SetStatus(text)
}

// unitSuffix is the SINGLE source of truth for the unit text shown beside a
// converted reading and in the table's 单位 column. Both must follow the
// display setting; the caliper's raw protocol unit must never leak into a
// converted value's suffix (that produced "10.00 mm" next to "inch").
func unitSuffix(unit string) string {
	if unit == "inch" {
		return "in"
	}
	return "mm"
}

func (u *UI) fmtMM(um int64) string {
	s := u.app.GetSettings()
	if s.Unit == "inch" {
		return fmt.Sprintf("%.4f %s", protocolToInch(um), unitSuffix(s.Unit))
	}
	return fmt.Sprintf("%.*f %s", s.Digits, protocolToMM(um), unitSuffix(s.Unit))
}

func protocolToMM(um int64) float64   { return float64(um) / 1000.0 }
func protocolToInch(um int64) float64 { return float64(um) / 25400.0 }
