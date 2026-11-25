//go:build windows

package hid

import (
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

// TestRawCaptureTerminatorIsEffective verifies the configured terminator is
// the only key that commits: with ENTER selected, TAB must not cut a reading.
func TestRawCaptureTerminatorIsEffective(t *testing.T) {
	ch := make(chan string, 8)
	c := NewRaw("ENTER", func(tok, fp string) { ch <- tok })
	c.handleRawKey('4', "HID#VID_3333")
	c.handleRawKey(vkTab, "HID#VID_3333") // wrong terminator: ignored
	select {
	case tok := <-ch:
		t.Fatalf("TAB committed reading %q while ENTER was configured", tok)
	case <-time.After(60 * time.Millisecond):
	}
	c.handleRawKey(vkReturn, "HID#VID_3333")
	select {
	case tok := <-ch:
		if tok != "4" {
			t.Fatalf("ENTER committed %q, want 4", tok)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("ENTER did not commit a reading")
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
