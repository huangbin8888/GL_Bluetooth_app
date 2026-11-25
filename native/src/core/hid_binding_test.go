package core

import (
	"testing"

	"glcaliper/protocol"
)

// TestHIDLearnAndAutoFile verifies the core of "按设备自动归档": an unseen
// fingerprint is learned onto the currently selected channel, and every later
// reading from that device is filed to its bound channel even when the UI
// selector moves elsewhere.
func TestHIDLearnAndAutoFile(t *testing.T) {
	a := NewApp()
	a.SetChannel(3)

	fpA := `\\?\HID#VID_1234&PID_5678#8&ABCDEF`
	if !a.FeedHIDTokenWithDevice("10.00", fpA) {
		t.Fatal("first token from device A rejected")
	}
	if got := a.HIDBoundChannel(fpA); got != 3 {
		t.Fatalf("device A learned to CH%d, want CH3", got)
	}
	recs := a.RecordsSnapshot()
	if len(recs) != 1 || recs[0].Ch != 3 || recs[0].Src != "HID" {
		t.Fatalf("first record = %+v, want CH3/HID", recs)
	}

	// Move the selector to CH5. Device A must still file to CH3.
	a.SetChannel(5)
	a.FeedHIDTokenWithDevice("11.00", fpA)
	recs = a.RecordsSnapshot()
	if len(recs) != 2 || recs[1].Ch != 3 {
		t.Fatalf("second record from A = %+v, want still CH3", recs[1])
	}

	// A different device, still selector=CH5, is learned onto CH5.
	fpB := `\\?\HID#VID_1234&PID_5678#9&999999`
	a.FeedHIDTokenWithDevice("12.00", fpB)
	if got := a.HIDBoundChannel(fpB); got != 5 {
		t.Fatalf("device B learned to CH%d, want CH5", got)
	}
}

// TestHIDUnbindingAndRebind covers clearing and re-learning bindings.
func TestHIDUnbindingAndRebind(t *testing.T) {
	a := NewApp()
	a.SetChannel(2)
	fp := `\\?\HID#VID_AAAA&PID_BBBB#1&111`
	a.FeedHIDTokenWithDevice("10.00", fp)
	if !a.UnbindHID(fp) {
		t.Fatal("unbind by fingerprint should report a change")
	}
	if got := a.HIDBoundChannel(fp); got != 0 {
		t.Fatalf("after unbind, bound channel = %d, want 0", got)
	}
	if a.UnbindHID(fp) {
		t.Fatal("second unbind should be a no-op")
	}

	// Rebind explicitly to CH7; later readings go there regardless of selector.
	if !a.BindHID(fp, 7) {
		t.Fatal("explicit bind should report a change")
	}
	a.SetChannel(1)
	a.FeedHIDTokenWithDevice("10.50", fp)
	recs := a.RecordsSnapshot()
	if recs[len(recs)-1].Ch != 7 {
		t.Fatalf("rebound device filed to CH%d, want CH7", recs[len(recs)-1].Ch)
	}
	// Rebinding to the same channel is a no-op: BindHID reports false (no change).
	if a.BindHID(fp, 7) {
		t.Fatal("rebinding to same channel should be a no-op")
	}
}

// TestHIDNoFingerprintUsesSelector covers the legacy fallback: with no device
// identity, readings still go to the current channel and create no binding.
func TestHIDNoFingerprintUsesSelector(t *testing.T) {
	a := NewApp()
	a.SetChannel(4)
	a.FeedHIDTokenWithDevice("10.00", "   ")
	recs := a.RecordsSnapshot()
	if len(recs) != 1 || recs[0].Ch != 4 {
		t.Fatalf("no-fingerprint record = %+v, want CH4", recs)
	}
	if len(a.HIDBindings()) != 0 {
		t.Fatalf("no-fingerprint reading must not create a binding: %+v", a.HIDBindings())
	}
}

// TestLegacyMaxChPreservedButNotLimiting checks that a legacy config's MaxCh=7
// is no longer overwritten by Normalize, yet does not cap channel selection:
// all 20 channels stay usable because functional code uses protocol.MaxChannels.
func TestLegacyMaxChPreservedButNotLimiting(t *testing.T) {
	s := Settings{MaxCh: 7, Terminator: "ENTER", Batch: "B", NamePrefix: "GL-BLE#", IdleMinutes: 5, Unit: "mm"}
	s.Normalize()
	if s.MaxCh != 7 {
		t.Fatalf("Normalize overwrote user MaxCh: got %d, want 7 (preserved)", s.MaxCh)
	}
	a := NewApp()
	a.SetSettings(s)
	a.SetChannel(20)
	if a.Channel() != 20 {
		t.Fatalf("SetChannel(20) with legacy MaxCh=7 => %d, want 20", a.Channel())
	}
	a.FeedDemoValue(10.0, 20)
	if got := a.RecordsSnapshot()[0].Ch; got != 20 {
		t.Fatalf("record channel = %d, want 20", got)
	}
}

// TestChannelCapacity20 guards the 20-channel expansion end-to-end.
func TestChannelCapacity20(t *testing.T) {
	if protocol.MaxChannels != 20 {
		t.Fatalf("MaxChannels = %d, want 20", protocol.MaxChannels)
	}
	s := DefaultSettings()
	if s.MaxCh != 20 || len(s.Channels) != 20 {
		t.Fatalf("defaults: MaxCh=%d channels=%d, want 20/20", s.MaxCh, len(s.Channels))
	}
	a := NewApp()
	a.SetChannel(20)
	if a.Channel() != 20 {
		t.Fatalf("SetChannel(20) => %d, want 20", a.Channel())
	}
	a.SetChannel(21) // out of range must be ignored
	if a.Channel() != 20 {
		t.Fatalf("SetChannel(21) => %d, want 20", a.Channel())
	}
}

// TestHIDBindingsSurviveSettingsRoundTrip ensures learned bindings are not
// dropped when settings are saved through a snapshot (as onSaveSettings does).
func TestHIDBindingsSurviveSettingsRoundTrip(t *testing.T) {
	a := NewApp()
	a.SetChannel(6)
	fp := `\\?\HID#VID_1234&PID_5678#8&ABCDEF`
	a.FeedHIDTokenWithDevice("10.00", fp)
	snapshot := a.GetSettings() // what onSaveSettings reads
	a.SetSettings(snapshot)     // what onSaveSettings writes back
	if got := a.HIDBoundChannel(fp); got != 6 {
		t.Fatalf("binding lost across settings round trip: got CH%d, want 6", got)
	}
}
