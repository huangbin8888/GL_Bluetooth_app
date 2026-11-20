//go:build windows

// Package ble wraps tinygo.org/x/bluetooth (whose Windows backend uses WinRT)
// to scan/connect Guanglu GL-BLE# calipers, subscribe to ffb2 notifications and
// acknowledge commands. On Win7 the WinRT APIs are absent, so Enable()/Scan()
// fail and the caller falls back to HID capture automatically.
package ble

import (
	"errors"
	"strings"
	"sync"
	"time"

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
	mu        sync.Mutex
	adapter   *bluetooth.Adapter
	scanning  bool
	device    bluetooth.Device
	connected bool
	notifyChar bluetooth.DeviceCharacteristic
	onData    func(string) // raw notify payload as string
	onStatus  func(string)
	namePrefix string // advertised-name prefix used by Scan() filtering
}

// New creates a BLE client.
func New(onData func(string), onStatus func(string)) *Client {
	return &Client{
		adapter:  bluetooth.DefaultAdapter,
		onData:   onData,
		onStatus: onStatus,
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
	c.mu.Lock()
	dev := c.device
	connected := c.connected
	c.device = bluetooth.Device{}
	c.notifyChar = bluetooth.DeviceCharacteristic{}
	c.connected = false
	c.mu.Unlock()
	if connected {
		_ = dev.Disconnect()
		c.status("已断开")
	}
}

func (c *Client) IsConnected() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.connected
}
