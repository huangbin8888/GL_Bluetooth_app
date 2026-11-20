package core

// LinkWatchdog holds pure decisions for auto-reconnect and idle-disconnect.
// No Win32 / hardware dependency here so it stays unit testable; the actual
// timers and BLE calls live in main.go.

// ShouldAutoReconnect reports whether a startup reconnect attempt should be
// made. Both the feature switch and a remembered address must be present.
func ShouldAutoReconnect(enabled bool, addr string) bool {
	return enabled && addr != ""
}

// ShouldIdleDisconnect reports whether an idle BLE link should be dropped.
// lastTS / now are unix milliseconds. A non-positive lastTS (no measurement
// yet) or a non-positive minutes value disables the decision entirely.
func ShouldIdleDisconnect(enabled bool, lastTS, now int64, minutes int) bool {
	if !enabled || minutes <= 0 || lastTS <= 0 || now <= lastTS {
		return false
	}
	return now-lastTS >= int64(minutes)*60*1000
}
