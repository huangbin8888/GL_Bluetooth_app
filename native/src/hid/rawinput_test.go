//go:build windows

package hid

import (
	"fmt"
	"sync"
	"testing"
	"time"
)

func TestNormalizeFingerprint(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{
			`\\?\HID#VID_1234&PID_5678#8&ABCDEF#{4d1e55b2-f16f-11cf-88cb-001111000030}`,
			`HID#VID_1234&PID_5678#8&ABCDEF`,
		},
		{
			`  \\?\HID#VID_AAAA&PID_BBBB#1&111  `,
			`HID#VID_AAAA&PID_BBBB#1&111`,
		},
		{
			`\\?\hid#vid_1234&pid_5678#8&abcdef`,
			`HID#VID_1234&PID_5678#8&ABCDEF`, // case-insensitive -> stable
		},
		{
			``,
			``,
		},
	}
	for _, c := range cases {
		if got := NormalizeFingerprint(c.in); got != c.want {
			t.Errorf("NormalizeFingerprint(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

// TestNormalizeFingerprintStability confirms two paths differing only in the
// trailing interface GUID / case map to the same key.
func TestNormalizeFingerprintStability(t *testing.T) {
	a := NormalizeFingerprint(`\\?\HID#VID_045E&PID_07B4#7&1A2B3C#{884b96c3-56ef-11d1-bc8c-00a0c91405dd}`)
	b := NormalizeFingerprint(`\\?\hid#vid_045e&pid_07b4#7&1a2b3c#{00000000-0000-0000-0000-000000000000}`)
	if a != b {
		t.Fatalf("same device produced different fingerprints: %q vs %q", a, b)
	}
}

// TestRawCaptureDeviceBucketsIsolateTokens is the regression test for the
// "串台" defect: two calipers typing interleaved digits and terminating with
// ENTER must yield two independent, correctly attributed readings.
func TestRawCaptureDeviceBucketsIsolateTokens(t *testing.T) {
	type ev struct{ tok, fp string }
	ch := make(chan ev, 8)
	c := NewRaw("ENTER", func(tok, fp string) { ch <- ev{tok, fp} })
	fpA, fpB := "HID#VID_1111", "HID#VID_2222"
	// A types "12", B types "56", A types "3", then A commits with ENTER.
	c.handleRawKey('1', fpA)
	c.handleRawKey('5', fpB)
	c.handleRawKey('2', fpA)
	c.handleRawKey('6', fpB)
	c.handleRawKey('3', fpA)
	c.handleRawKey(vkReturn, fpA)
	// B still holds "56" and commits with its own ENTER.
	c.handleRawKey(vkReturn, fpB)

	byFP := map[string]string{}
	for i := 0; i < 2; i++ {
		select {
		case e := <-ch:
			byFP[e.fp] = e.tok
		case <-time.After(500 * time.Millisecond):
			t.Fatalf("timed out waiting for reading %d/2", i+1)
		}
	}
	if byFP[fpA] != "123" {
		t.Errorf("A reading = %q, want 123", byFP[fpA])
	}
	if byFP[fpB] != "56" {
		t.Errorf("B reading = %q, want 56", byFP[fpB])
	}
}

// TestRawCaptureBothTerminatorsCommit guards the "no data in table" fix:
// BOTH ENTER and TAB must commit a reading, regardless of the configured
// terminator. Calipers ship with either key and a saved-setting mismatch used
// to hold every token in the buffer forever.
func TestRawCaptureBothTerminatorsCommit(t *testing.T) {
	for _, tc := range []struct {
		name       string
		configured string
		key        uint32
	}{
		{"TAB with ENTER configured", "ENTER", vkTab},
		{"ENTER with TAB configured", "TAB", vkReturn},
		{"ENTER with ENTER configured", "ENTER", vkReturn},
		{"TAB with TAB configured", "TAB", vkTab},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ch := make(chan string, 4)
			c := NewRaw(tc.configured, func(tok, fp string) { ch <- tok })
			c.handleRawKey('4', "HID#VID_3333")
			c.handleRawKey(tc.key, "HID#VID_3333")
			select {
			case tok := <-ch:
				if tok != "4" {
					t.Fatalf("committed %q, want 4", tok)
				}
			case <-time.After(500 * time.Millisecond):
				t.Fatal("terminator did not commit a reading")
			}
		})
	}
}

// TestRawCaptureClearBufferClearsAllDevices verifies ClearBuffer drops every
// device's partial token, not just a single shared one.
func TestRawCaptureClearBufferClearsAllDevices(t *testing.T) {
	ch := make(chan string, 8)
	c := NewRaw("ENTER", func(tok, fp string) { ch <- tok })
	c.handleRawKey('7', "HID#VID_AAAA")
	c.handleRawKey('8', "HID#VID_BBBB")
	c.ClearBuffer()
	c.handleRawKey(vkReturn, "HID#VID_AAAA")
	c.handleRawKey(vkReturn, "HID#VID_BBBB")
	select {
	case tok := <-ch:
		t.Fatalf("cleared buffers still produced reading %q", tok)
	case <-time.After(100 * time.Millisecond):
	}
}

// TestDeviceNameWCharLenRejectsRealDeviceSizes is the P0 crash regression.
// MSDN: for RIDI_DEVICENAME, pcbSize is a CHARACTER count while the return
// value is a BYTE count (or -1 when the buffer is too small). The old code
// allocated (chars+1)/2 WCHARs and sliced with the byte count, so the first
// reading of a real caliper panicked with "slice bounds out of range".
func TestDeviceNameWCharLenRejectsRealDeviceSizes(t *testing.T) {
	const chars uint32 = 101 // typical device interface-name length
	cases := []struct {
		name   string
		chars  uint32
		ret    uintptr
		bufCap int
		want   int
	}{
		{"old half-sized buffer + failure sentinel", chars, ^uintptr(0), 51, 0},
		{"old half-sized buffer + byte count", chars, uintptr(2 * chars), 51, 0},
		{"32-bit failure sentinel", chars, uintptr(^uint32(0)), 101, 0},
		{"correct buffer, byte-count result", chars, uintptr(2 * chars), 101, int(chars)},
		{"zero return", chars, 0, 101, 0},
		{"zero chars", 0, uintptr(2 * chars), 101, 0},
		{"oversized chars", maxDeviceNameChars + 1, uintptr(2 * (maxDeviceNameChars + 1)), maxDeviceNameChars + 1, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := deviceNameWCharLen(tc.chars, tc.ret, tc.bufCap); got != tc.want {
				t.Fatalf("deviceNameWCharLen(%d, %#x, %d) = %d, want %d",
					tc.chars, tc.ret, tc.bufCap, got, tc.want)
			}
		})
	}
}

// TestDeviceFingerprintFromInvalidHandleIsSafe exercises the Win32 query with
// an invalid handle: it must degrade to an empty fingerprint, not panic.
func TestDeviceFingerprintFromInvalidHandleIsSafe(t *testing.T) {
	if got := deviceFingerprintFromHandle(0); got != "" {
		t.Fatalf("invalid handle produced fingerprint %q, want empty", got)
	}
}

// TestRawCaptureConcurrentKeysAndClearBuffer stresses the per-fingerprint
// bucket map against concurrent writers and ClearBuffer. An unprotected map
// would abort the process with "concurrent map writes" / "map read and map
// write"; the mutex in handleRawKey/ClearBuffer must keep both safe.
func TestRawCaptureConcurrentKeysAndClearBuffer(t *testing.T) {
	c := NewRaw("ENTER", func(string, string) {})
	var wg sync.WaitGroup
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			fp := fmt.Sprintf("HID#VID_%04d", id)
			for i := 0; i < 500; i++ {
				c.handleRawKey('1', fp)
				c.handleRawKey(vkReturn, fp)
			}
		}(g)
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 500; i++ {
			c.ClearBuffer()
		}
	}()
	wg.Wait()
}

// TestRawCaptureStaleFinishRunCannotClearNewSession guards the Raw Input
// lifecycle: a late teardown from an old run must not disable a newer one.
func TestRawCaptureStaleFinishRunCannotClearNewSession(t *testing.T) {
	c := NewRaw("ENTER", func(string, string) {})
	oldRun := &rawRun{}
	newRun := &rawRun{}
	c.run = newRun
	c.enabled = true

	c.finishRun(oldRun)

	if c.run != newRun || !c.enabled {
		t.Fatal("stale Raw Input finishRun disturbed the new session")
	}
}

// TestRawHookRefConcurrentAccess covers the callback pointer handoff between
// Start/Stop and the hidden-window message pump.
func TestRawHookRefConcurrentAccess(t *testing.T) {
	c := NewRaw("ENTER", func(string, string) {})
	setRawHookRef(c)
	defer clearRawHookRef(c)

	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 1000; j++ {
				_ = getRawHookRef()
			}
		}()
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for j := 0; j < 1000; j++ {
			clearRawHookRef(c)
			setRawHookRef(c)
		}
	}()
	wg.Wait()
}

// TestRawCaptureOnKeyPreview verifies the Raw Input path emits live per-key
// feedback (like the legacy hook) so typing is visible before the terminator.
func TestRawCaptureOnKeyPreview(t *testing.T) {
	keys := make(chan string, 8)
	c := NewRaw("ENTER", func(string, string) {})
	c.SetOnKey(func(ch string) { keys <- ch })
	for _, r := range []rune("123") {
		c.handleRawKey(uint32(r), "HID#VID_1111")
	}
	// Terminator must not be echoed as a character (only onToken commits).
	c.handleRawKey(vkReturn, "HID#VID_1111")
	// onKey is dispatched in a goroutine (no locking while calling back), so
	// wait with a timeout rather than assuming synchronous delivery.
	got := make([]string, 0, 3)
	for i := 0; i < 3; i++ {
		select {
		case ch := <-keys:
			got = append(got, ch)
		case <-time.After(500 * time.Millisecond):
			t.Fatalf("timed out waiting for onKey event %d/3", i+1)
		}
	}
	// onKey runs in a goroutine per key, so delivery order is not guaranteed;
	// assert the exact character set instead.
	seen := map[string]bool{}
	for _, ch := range got {
		seen[ch] = true
	}
	if len(seen) != 3 || !seen["1"] || !seen["2"] || !seen["3"] {
		t.Errorf("onKey events = %v, want exactly {1,2,3} (no terminator echo)", got)
	}
	select {
	case extra := <-keys:
		t.Errorf("unexpected extra onKey event %q (terminator should not echo)", extra)
	case <-time.After(50 * time.Millisecond):
	}
}
