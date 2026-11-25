//go:build windows

package ble

import (
	"testing"

	"tinygo.org/x/bluetooth"
)

// TestDisconnectConvergesState verifies that every disconnect path leaves the
// client in a single consistent state (connected=false, no device/char, status
// emitted exactly once). This is the convergence point that fixes the P1
// "state diverges after disconnect" defect.
func TestDisconnectConvergesState(t *testing.T) {
	statuses := make([]string, 0, 2)
	c := &Client{
		connected: true,
		// A zero device has no WinRT handle; disconnectWithStatus must not try
		// to release it (that would crash on a nil COM pointer).
		device:   bluetooth.Device{},
		onStatus: func(s string) { statuses = append(statuses, s) },
	}

	c.Disconnect()

	if c.connected {
		t.Fatal("connected must be false after Disconnect")
	}
	if c.IsConnected() {
		t.Fatal("IsConnected must be false after Disconnect")
	}
	if len(statuses) != 1 || statuses[0] != "已断开" {
		t.Fatalf("status = %v, want exactly [已断开]", statuses)
	}
}

// TestDisconnectIdempotent checks that a second Disconnect is a no-op: it must
// not emit a second status or touch the (already-cleared) device.
func TestDisconnectIdempotent(t *testing.T) {
	n := 0
	c := &Client{
		connected: false,
		onStatus:  func(string) { n++ },
	}
	c.Disconnect()
	c.Disconnect()
	if n != 0 {
		t.Fatalf("status emitted %d times on an already-disconnected client, want 0", n)
	}
}

// TestIsConnectedDetectsPhysicalLoss is the core P1 regression test: the
// notification path carries no link-loss event, so a physically discharged /
// out-of-range caliper must be detected by the link probe, which converges the
// local state and emits a status. Without the probe, connected would stay true
// forever and the idle watchdog / Write checks would act on a dead link.
func TestIsConnectedDetectsPhysicalLoss(t *testing.T) {
	statuses := make([]string, 0, 2)
	probeCalls := 0
	c := &Client{
		connected: true,
		device:    bluetooth.Device{},
		onStatus:  func(s string) { statuses = append(statuses, s) },
		probeLink: func(bluetooth.Device) bool {
			probeCalls++
			return true // simulate WinRT reporting Disconnected
		},
	}

	if c.IsConnected() {
		t.Fatal("IsConnected must report false when the physical link is lost")
	}
	if probeCalls != 1 {
		t.Fatalf("probe called %d times, want 1", probeCalls)
	}
	if c.connected {
		t.Fatal("connected must converge to false after a detected loss")
	}
	if len(statuses) != 1 || statuses[0] != "设备已断开连接" {
		t.Fatalf("status = %v, want exactly [设备已断开连接]", statuses)
	}
	// After convergence, subsequent polls must short-circuit without re-probing.
	if c.IsConnected() {
		t.Fatal("IsConnected must stay false after convergence")
	}
	if probeCalls != 1 {
		t.Fatalf("probe called %d times after convergence, want 1 (short-circuit)", probeCalls)
	}
}

// TestIsConnectedHealthyLinkKeepsState verifies the probe does not create a
// false disconnect when the link is healthy.
func TestIsConnectedHealthyLinkKeepsState(t *testing.T) {
	c := &Client{
		connected: true,
		device:    bluetooth.Device{},
		probeLink: func(bluetooth.Device) bool { return false }, // link healthy
	}
	if !c.IsConnected() {
		t.Fatal("IsConnected must stay true when the link probe reports healthy")
	}
	if !c.connected {
		t.Fatal("connected must remain true on a healthy link")
	}
}
