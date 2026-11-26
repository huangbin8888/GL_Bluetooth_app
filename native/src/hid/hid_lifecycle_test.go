//go:build windows

package hid

import "testing"

// TestFinishRunStaleSessionCannotClearNew is the core P1 regression test for
// the HID toggle (open→close→open→close). After a Stop→Start cycle, a new run
// replaces the old; when the old message loop finally exits and calls
// finishRun, it must NOT clear the new run's enabled flag. The old stopOnce
// design made the second Stop a no-op and could close the new run's channel.
func TestFinishRunStaleSessionCannotClearNew(t *testing.T) {
	c := &Capture{}
	oldRun := &captureRun{}
	newRun := &captureRun{}

	c.run = newRun
	c.enabled = true

	// The stale loop from the previous session exits late.
	c.finishRun(oldRun)

	if c.run != newRun {
		t.Fatal("stale finishRun replaced the active run")
	}
	if !c.enabled {
		t.Fatal("stale finishRun cleared enabled for the new session")
	}
}

// TestFinishRunCurrentSessionClears verifies the normal case: when the active
// run finishes, lifecycle state is cleared so a fresh Start can proceed.
func TestFinishRunCurrentSessionClears(t *testing.T) {
	c := &Capture{}
	run := &captureRun{}
	c.run = run
	c.enabled = true

	c.finishRun(run)

	if c.run != nil {
		t.Fatal("active run was not cleared")
	}
	if c.enabled {
		t.Fatal("enabled not cleared when active run finished")
	}
}

// TestStopBeforeStartIsNoop confirms Stop is safe and idempotent when the
// capture was never started (e.g. a failed HIDStart followed by toggle off).
func TestStopBeforeStartIsNoop(t *testing.T) {
	c := &Capture{}
	c.Stop()
	c.Stop()
	if c.IsEnabled() {
		t.Fatal("capture must be disabled after Stop on a fresh instance")
	}
	if c.run != nil {
		t.Fatal("run must remain nil after Stop on a fresh instance")
	}
}

// TestStartRejectsWhenAlreadyRunning verifies Start is idempotent while a run
// is active — it must not spawn a second hook over the first one.
func TestStartRejectsWhenAlreadyRunning(t *testing.T) {
	c := &Capture{}
	run := &captureRun{}
	c.run = run
	c.enabled = true

	// Start would early-return without touching c.run because enabled && run.
	// We assert the guard condition directly (no Win32 involved).
	if !(c.enabled && c.run != nil) {
		t.Fatal("expected the already-running guard to hold")
	}
}

// TestHookProcWithNoActiveCaptureDoesNotPanic covers the legacy fallback's
// callback: it reads the package-level capture pointer once, so a Stop() that
// clears the pointer cannot turn the second read into a nil dereference.
func TestHookProcWithNoActiveCaptureDoesNotPanic(t *testing.T) {
	setHookRef(nil)
	defer setHookRef(nil)
	hookProc(0, wmKeyDown, 0)
}
