//go:build windows

package main

import (
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/lxn/walk"
	"glcaliper/ble"
	"glcaliper/core"
	"glcaliper/hid"
	"glcaliper/ui"
)

// bridge implements ui.DeviceBridge using the real HID/BLE hardware modules.
type bridge struct {
	mu         sync.Mutex
	hid        *hid.Capture    // legacy WH_KEYBOARD_LL fallback (no device id)
	raw        *hid.RawCapture // preferred: WM_INPUT with device fingerprint
	ble        *ble.Client
	app        *core.App
	ui         *ui.UI
	namePrefix string // latest scan-name prefix from settings
}

// HIDStart prefers Raw Input (carries a per-device fingerprint for automatic
// channel filing). If Raw Input cannot initialise, it falls back to the legacy
// global keyboard hook, which still captures readings into the current channel.
func (b *bridge) HIDStart() error {
	term := b.app.GetSettings().Terminator
	if b.raw == nil {
		b.raw = hid.NewRaw(term, func(token, fp string) {
			if b.app.FeedHIDTokenWithDevice(token, fp) {
				b.ui.OnHidChar(" [✓已入表格]")
			} else {
				b.ui.OnHidChar(" [×未识别]")
			}
		})
		// Raw Input had no live per-key preview (only the legacy hook did), so
		// typing looked like "nothing happens". Mirror the legacy behaviour.
		b.raw.SetOnKey(func(ch string) { b.ui.OnHidChar(ch) })
	}
	if err := b.raw.Start(); err == nil {
		return nil
	}
	// Raw Input unavailable: use the legacy hook path.
	b.raw = nil
	if b.hid == nil {
		b.hid = hid.New(term,
			func(token string) {
				if b.app.FeedHIDToken(token) {
					b.ui.OnHidChar(" [✓已入表格]")
				} else {
					b.ui.OnHidChar(" [×未识别]")
				}
			},
			func(ch string) { b.ui.OnHidChar(ch) })
	}
	return b.hid.Start()
}
func (b *bridge) HIDStop() {
	if b.raw != nil {
		b.raw.Stop()
	}
	if b.hid != nil {
		b.hid.Stop()
	}
}
func (b *bridge) HIDEnabled() bool {
	return (b.raw != nil && b.raw.IsEnabled()) || (b.hid != nil && b.hid.IsEnabled())
}

// HIDIsRaw reports whether the active capture carries device fingerprints.
// False means the fallback global hook is in use (no automatic channel filing).
func (b *bridge) HIDIsRaw() bool {
	return b.raw != nil && b.raw.IsEnabled()
}
func (b *bridge) HIDSetTerminator(t string) {
	if b.raw != nil {
		b.raw.SetTerminator(t)
	}
	if b.hid != nil {
		b.hid.SetTerminator(t)
	}
}
func (b *bridge) HIDClearBuffer() {
	if b.raw != nil {
		b.raw.ClearBuffer()
	}
	if b.hid != nil {
		b.hid.ClearBuffer()
	}
}
func (b *bridge) HIDListDevices() []ui.HIDDeviceInfo {
	devs := hid.ListHIDDevices()
	out := make([]ui.HIDDeviceInfo, len(devs))
	for i, d := range devs {
		out[i] = ui.HIDDeviceInfo{Fingerprint: d.Fingerprint}
	}
	return out
}

func (b *bridge) BLEAvailable() bool { return ble.Available() }

// SetNamePrefix applies the configured scan-name prefix to the current and
// all future BLE clients (called from settings save / startup).
func (b *bridge) SetNamePrefix(prefix string) {
	b.mu.Lock()
	b.namePrefix = prefix
	client := b.ble
	b.mu.Unlock()
	if client != nil {
		client.SetNamePrefix(prefix)
	}
}

func (b *bridge) BLEScan() ([]ui.DeviceView, error) {
	client := b.ensureBLE()
	devs, err := client.Scan(10000)
	if err != nil {
		return nil, err
	}
	out := make([]ui.DeviceView, len(devs))
	for i, d := range devs {
		out[i] = ui.DeviceView{Address: d.Address, Name: d.Name, RSSI: d.RSSI}
	}
	return out, nil
}

func (b *bridge) ensureBLE() *ble.Client {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.ble == nil {
		b.ble = ble.New(
			func(payload string) { b.app.FeedRaw(payload) },
			func(status string) { b.app.SetStatus(status) },
		)
		if b.namePrefix != "" {
			b.ble.SetNamePrefix(b.namePrefix)
		}
	}
	return b.ble
}

// NoteConnected records a successful connection: the app's device label is
// used for Record.DeviceMAC and the address is remembered for auto-reconnect.
func (b *bridge) NoteConnected(addr string) {
	b.app.SetDeviceLabel(addr)
	s := b.app.GetSettings()
	if s.LastDeviceAddr != addr {
		s.LastDeviceAddr = addr
		s.Normalize()
		b.app.SetSettings(s)
		if b.ui != nil {
			b.ui.SaveSettingsNow()
		}
	}
}

func (b *bridge) BLEConnect(addr string) error {
	if err := b.ensureBLE().Connect(addr); err != nil {
		return err
	}
	b.NoteConnected(addr)
	return nil
}

// BLEAutoConnect is the startup best-effort reconnect entry point.
func (b *bridge) BLEAutoConnect(addr string) error { return b.BLEConnect(addr) }

func (b *bridge) BLEDisconnect() {
	b.mu.Lock()
	client := b.ble
	b.mu.Unlock()
	if client != nil {
		client.Disconnect()
	}
}
func (b *bridge) BLEConnected() bool {
	b.mu.Lock()
	client := b.ble
	b.mu.Unlock()
	return client != nil && client.IsConnected()
}
func (b *bridge) BLEWrite(cmd string) error {
	b.mu.Lock()
	client := b.ble
	b.mu.Unlock()
	if client == nil {
		return fmt.Errorf("未连接设备")
	}
	return client.Write(cmd)
}

// lastRecordTS returns the timestamp of the newest stored measurement.
func lastRecordTS(app *core.App) int64 {
	recs := app.RecordsSnapshot()
	if len(recs) == 0 {
		return 0
	}
	return recs[len(recs)-1].TS
}

func main() {
	app := core.NewApp()
	settings := ui.LoadSettings()
	settings.Normalize()
	app.SetSettings(settings)

	br := &bridge{app: app, namePrefix: settings.NamePrefix}
	u := ui.New(app, br)
	br.ui = u

	// periodic flush of any held BLE tail frame (idle commit)
	go func() {
		t := time.NewTicker(400 * time.Millisecond)
		defer t.Stop()
		for range t.C {
			app.FlushPending()
		}
	}()

	// startup best-effort auto reconnect (one attempt only, failures are
	// reported in the status bar and never retried).
	go func() {
		if !core.ShouldAutoReconnect(settings.AutoReconnect, settings.LastDeviceAddr) {
			return
		}
		if !br.BLEAvailable() {
			app.SetStatus("自动重连跳过：此系统不支持 BLE")
			return
		}
		app.SetStatus("自动连接 " + settings.LastDeviceAddr + " …")
		if err := br.BLEAutoConnect(settings.LastDeviceAddr); err != nil {
			app.SetStatus("自动重连失败：" + err.Error())
		}
	}()

	// independent 1s watchdog for idle BLE disconnect. HID/DEMO have no
	// "connected" state, so BLEConnected() keeps them as no-ops.
	go func() {
		t := time.NewTicker(1 * time.Second)
		defer t.Stop()
		for range t.C {
			s := app.GetSettings()
			if !core.ShouldIdleDisconnect(s.IdleDisconnect, lastRecordTS(app), time.Now().UnixMilli(), s.IdleMinutes) {
				continue
			}
			if !br.BLEConnected() {
				continue
			}
			br.BLEDisconnect()
			app.SetStatus("空闲超时，已自动断开 BLE")
		}
	}()

	if err := u.Run(); err != nil {
		_ = os.WriteFile("glcaliper-error.log", []byte(err.Error()), 0644)
		walk.MsgBox(nil, "启动失败", err.Error(), walk.MsgBoxIconError)
	}
}
