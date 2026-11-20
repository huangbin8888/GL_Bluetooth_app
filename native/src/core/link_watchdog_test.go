package core

import "testing"

func TestShouldAutoReconnect(t *testing.T) {
	cases := []struct {
		name    string
		enabled bool
		addr    string
		want    bool
	}{
		{"enabled with addr", true, "AA:BB:CC:DD:EE:FF", true},
		{"disabled with addr", false, "AA:BB:CC:DD:EE:FF", false},
		{"enabled empty addr", true, "", false},
		{"disabled empty addr", false, "", false},
		{"whitespace addr is non-empty", true, " ", true},
	}
	for _, c := range cases {
		if got := ShouldAutoReconnect(c.enabled, c.addr); got != c.want {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}

func TestShouldIdleDisconnect(t *testing.T) {
	const minute = int64(60 * 1000)
	last := int64(1_000_000)
	cases := []struct {
		name    string
		enabled bool
		lastTS  int64
		now     int64
		minutes int
		want    bool
	}{
		{"just below threshold", true, last, last + 5*minute - 1, 5, false},
		{"exactly threshold", true, last, last + 5*minute, 5, true},
		{"past threshold", true, last, last + 6*minute, 5, true},
		{"switch off", false, last, last + 10*minute, 5, false},
		{"zero minutes disabled", true, last, last + 10*minute, 0, false},
		{"negative minutes disabled", true, last, last + 10*minute, -3, false},
		{"no measurements yet", true, 0, last + 10*minute, 5, false},
		{"negative lastTS", true, -1, last + 10*minute, 5, false},
		{"clock rollback", true, last, last - minute, 5, false},
		{"same instant", true, last, last, 5, false},
	}
	for _, c := range cases {
		if got := ShouldIdleDisconnect(c.enabled, c.lastTS, c.now, c.minutes); got != c.want {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}
