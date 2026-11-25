//go:build windows

// Package ble wraps tinygo.org/x/bluetooth (whose Windows backend uses WinRT)
// to scan/connect Guanglu GL-BLE# calipers, subscribe to ffb2 notifications and
// acknowledge commands. On Win7 the WinRT APIs are absent, so Enable()/Scan()
// fail; the caller then prompts the user to switch to HID capture mode, which
// provides the same live readings without WinRT.
package ble

import (
	"errors"
	"reflect"
	"strings"
	"sync"
	"time"
	"unsafe"

	winrtbt "github.com/saltosystems/winrt-go/windows/devices/bluetooth"
	"tinygo.org/x/bluetooth"
)

var (
	serviceUUID, _ = bluetooth.ParseUUID("0000ffb0-0000-1000-8000-00805f9b34fb")
	charUUID, _    = bluetooth.ParseUUID("0000ffb2-0000-1000-8000-00805f9b34fb")
)

// DeviceInfo is one discovered caliper.
type DeviceInfo struct {
	Address string
	Name    string
	RSSI    int16
}

// Client manages BLE scanning and a single active connection.
type Client struct {
	mu         sync.Mutex
	adapter    *bluetooth.Adapter
	scanning   bool
	device     bluetooth.Device
	connected  bool
	notifyChar bluetooth.DeviceCharacteristic
	onData     func(string) // raw notify payload as string
	onStatus   func(string)
	namePrefix string // advertised-name prefix used by Scan() filtering

	// probeLink reports whether the physical link is gone. When nil, the real
	// WinRT connection-status query is used. Tests inject a deterministic stub
	// so the disconnect→convergence state machine can be verified without a
	// physical BLE radio. The bool result is the detected lost state; a nil
	// probe on a zero device is treated conservatively (not lost).
	probeLink func(bluetooth.Device) bool
}

// New creates a BLE client.
func New(onData func(string), onStatus func(string)) *Client {
	return &Client{
		adapter:    bluetooth.DefaultAdapter,
		onData:     onData,
		onStatus:   onStatus,
		namePrefix: "GL-BLE#",
	}
}

// SetNamePrefix updates the advertised-name prefix used to filter scan
// results. An empty value restores the built-in default.
func (c *Client) SetNamePrefix(p string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if strings.TrimSpace(p) == "" {
		c.namePrefix = "GL-BLE#"
		return
	}
	c.namePrefix = p
}

// currentNamePrefix returns a safe copy for use inside scan callbacks.
func (c *Client) currentNamePrefix() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.namePrefix == "" {
		return "GL-BLE#"
	}
	return c.namePrefix
}

// Available probes whether this system can actually use BLE (Win10+ with a
// working radio). It never reports available on Win7 because WinRT BLE is
// part of the Win10 API surface.
func Available() bool {
	a := bluetooth.DefaultAdapter
	if err := a.Enable(); err != nil {
		return false
	}
	return true
}

func (c *Client) status(s string) {
	if c.onStatus != nil {
		c.onStatus(s)
	}
}

// Scan discovers GL-BLE# devices for the given duration (ms). The callback
// receives the full deduplicated list when scanning completes.
func (c *Client) Scan(durationMs int) ([]DeviceInfo, error) {
	c.mu.Lock()
	if c.scanning {
		c.mu.Unlock()
		return nil, errors.New("扫描正在进行中")
	}
	if err := c.adapter.Enable(); err != nil {
		c.mu.Unlock()
		return nil, err
	}
	c.scanning = true
	c.mu.Unlock()
	prefix := c.currentNamePrefix()

	found := make(map[string]DeviceInfo)
	var fmu sync.Mutex
	err := c.adapter.Scan(func(a *bluetooth.Adapter, r bluetooth.ScanResult) {
		name := r.AdvertisementPayload.LocalName()
		if !strings.HasPrefix(name, prefix) {
			return
		}
		addr := r.Address.String()
		fmu.Lock()
		old, exists := found[addr]
		if !exists || r.RSSI > old.RSSI {
			found[addr] = DeviceInfo{Address: addr, Name: name, RSSI: r.RSSI}
		}
		fmu.Unlock()
	})
	// Scan() on Windows returns after the OS scan completes; tinygo's Windows
	// backend scans for a fixed window (~10s). We cap it by stopping early.
	if durationMs > 0 {
		time.AfterFunc(time.Duration(durationMs)*time.Millisecond, func() {
			_ = c.adapter.StopScan()
		})
	}
	c.mu.Lock()
	c.scanning = false
	c.mu.Unlock()

	if err != nil {
		return nil, err
	}
	list := make([]DeviceInfo, 0, len(found))
	for _, v := range found {
		list = append(list, v)
	}
	return list, nil
}

// StopScan aborts an in-progress scan.
func (c *Client) StopScan() {
	_ = c.adapter.StopScan()
	c.mu.Lock()
	c.scanning = false
	c.mu.Unlock()
}

// Connect connects to a device and subscribes to the ffb2 notification.
func (c *Client) Connect(addr string) error {
	c.mu.Lock()
	if c.connected {
		c.mu.Unlock()
		return errors.New("已连接设备，请先断开")
	}
	if err := c.adapter.Enable(); err != nil {
		c.mu.Unlock()
		return err
	}
	c.mu.Unlock()

	var a bluetooth.Address
	a.Set(addr) // MACAddress.Set parses a "AA:BB:CC:DD:EE:FF" string
	dev, err := c.adapter.Connect(a, bluetooth.ConnectionParams{
		ConnectionTimeout: bluetooth.NewDuration(8 * time.Second),
	})
	if err != nil {
		return err
	}

	svcs, err := dev.DiscoverServices([]bluetooth.UUID{serviceUUID})
	if err != nil || len(svcs) == 0 {
		_ = dev.Disconnect()
		if err != nil {
			return err
		}
		return errors.New("未发现服务 ffb0")
	}
	chars, err := svcs[0].DiscoverCharacteristics([]bluetooth.UUID{charUUID})
	if err != nil || len(chars) == 0 {
		_ = dev.Disconnect()
		if err != nil {
			return err
		}
		return errors.New("未发现特征 ffb2")
	}
	ch := chars[0]
	if err := ch.EnableNotifications(func(buf []byte) {
		if c.onData != nil {
			c.onData(string(buf))
		}
	}); err != nil {
		_ = dev.Disconnect()
		return err
	}

	c.mu.Lock()
	c.device = dev
	c.notifyChar = ch
	c.connected = true
	c.mu.Unlock()
	c.status("已连接 " + addr)
	return nil
}

// Write sends a raw command string (e.g. "DAT0:OK", "MEAS", "NAM:...") to ffb2.
func (c *Client) Write(cmd string) error {
	c.mu.Lock()
	ch := c.notifyChar
	connected := c.connected
	c.mu.Unlock()
	if !connected {
		return errors.New("未连接设备")
	}
	n, err := ch.Write([]byte(cmd))
	if err != nil {
		return err
	}
	if n != len(cmd) {
		return errors.New("写入不完整")
	}
	return nil
}

// Disconnect closes the active connection.
func (c *Client) Disconnect() {
	c.disconnectWithStatus("已断开")
}

// disconnectWithStatus tears down whatever connection state exists and reports
// the given status. It is the single convergence point for both an explicit
// Disconnect and a detected physical link loss, so no path can leave
// connected=true while the underlying device is gone.
func (c *Client) disconnectWithStatus(statusText string) {
	c.mu.Lock()
	dev := c.device
	connected := c.connected
	c.device = bluetooth.Device{}
	c.notifyChar = bluetooth.DeviceCharacteristic{}
	c.connected = false
	// Release the WinRT object while holding the lock. This serialises with
	// IsConnected's GetConnectionStatus poll below so it can never touch a
	// device that has already been released.
	// A zero device (e.g. a test stub that injected a lost link without a
	// real COM object) has no WinRT handle to release; calling Disconnect on
	// it would dereference a nil COM pointer and crash. Only release a real
	// WinRT device object.
	if connected && nativeLEDebugDevice(dev) != nil {
		_ = dev.Disconnect()
	}
	c.mu.Unlock()
	// Only report a status when there was a real state change. Repeated
	// Disconnect calls (watchdog + manual button) must not churn the UI.
	if connected {
		c.status(statusText)
	}
}

// nativeLEDebugDevice exposes the underlying WinRT BluetoothLEDevice so the
// physical link state can be polled. tinygo v0.10.0 deliberately hides it in
// an unexported field (`device`) of bluetooth.Device; reflection is used here
// rather than forking the dependency. Returns nil when the layout changes or
// no WinRT object is present, in which case callers fall back to the local
// connected flag instead of reporting a false disconnect.
func nativeLEDebugDevice(d bluetooth.Device) *winrtbt.BluetoothLEDevice {
	v := reflect.ValueOf(d)
	f := v.FieldByName("device")
	if !f.IsValid() || f.Kind() != reflect.Ptr || f.IsNil() {
		return nil
	}
	return (*winrtbt.BluetoothLEDevice)(unsafe.Pointer(f.Pointer()))
}

// IsConnected reports whether the BLE link is still alive. It combines the
// local connection flag with the WinRT connection status: tinygo's notification
// callback carries no link-loss event, so a device that is switched off or
// walks out of range would otherwise leave connected=true forever (a state
// the idle watchdog and Write checks would act on incorrectly). If WinRT
// reports the link is gone, the local state is converged and a status message
// is emitted. If the WinRT object is unavailable, we conservatively trust the
// local flag and never fabricate a disconnect.
func (c *Client) IsConnected() bool {
	c.mu.Lock()
	if !c.connected {
		c.mu.Unlock()
		return false
	}
	// Poll the link state while holding the lock: it serialises with
	// disconnectWithStatus releasing the device object, so the COM pointer is
	// guaranteed valid for the duration of this call.
	lost := c.probeLinkLocked()
	c.mu.Unlock()

	if lost {
		c.disconnectWithStatus("设备已断开连接")
		return false
	}
	return true
}

// probeLinkLocked performs the physical link probe. Caller must hold c.mu.
func (c *Client) probeLinkLocked() bool {
	if c.probeLink != nil {
		return c.probeLink(c.device)
	}
	le := nativeLEDebugDevice(c.device)
	if le == nil {
		// WinRT object unavailable: never fabricate a disconnect.
		return false
	}
	st, err := le.GetConnectionStatus()
	// An error means the COM object is no longer usable, which in practice
	// accompanies a physical disconnect. Only a clean Connected result keeps
	// the link alive.
	return err != nil || st == winrtbt.BluetoothConnectionStatusDisconnected
}
