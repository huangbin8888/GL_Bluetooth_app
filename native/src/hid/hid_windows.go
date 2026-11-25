//go:build windows

// Package hid implements a global low-level keyboard hook (WH_KEYBOARD_LL)
// to capture caliper readings typed as keyboard input, regardless of which
// window has focus. This is the key capability a browser/Electron cannot do,
// and is what makes Win7 real-device measurement possible.
package hid

import (
	"runtime"
	"strings"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	user32 = windows.NewLazySystemDLL("user32.dll")

	procSetWindowsHookEx  = user32.NewProc("SetWindowsHookExW")
	procUnhookWindowsHook = user32.NewProc("UnhookWindowsHookEx")
	procCallNextHookEx    = user32.NewProc("CallNextHookEx")
	procGetMessage        = user32.NewProc("GetMessageW")
	procTranslateMessage  = user32.NewProc("TranslateMessage")
	procDispatchMessage   = user32.NewProc("DispatchMessageW")
	procMapVirtualKey     = user32.NewProc("MapVirtualKeyW")
	procGetKeyState       = user32.NewProc("GetKeyState")
	procPostThreadMessage = user32.NewProc("PostThreadMessageW")
)

const (
	whKeyboardLL = 13
	hcAction     = 0
	wmKeyDown    = 0x0100
	wmSysKeyDown = 0x0104
	wmQuit       = 0x0012

	vkTab      = 0x09
	vkReturn   = 0x0D
	vkShift    = 0x10
	vkDecimal  = 0x6E // numpad '.'
	vkSubtract = 0x6D // numpad '-'
)

type kbdLLHookStruct struct {
	vkCode      uint32
	scanCode    uint32
	flags       uint32
	time        uint32
	dwExtraInfo uintptr
}

// captureRun is one Start→Stop lifecycle. Keeping per-run state (thread ID,
// hook handle, quit channel) in its own object is what makes a Capture
// safely reusable: a second Start creates a fresh run and an old hookLoop can
// never close the new run's channel or post WM_QUIT to the new thread.
type captureRun struct {
	threadID uint32
	hook     uintptr
	ready    chan struct{} // closed once hook install has been attempted
	done     chan struct{} // closed once the hook thread has fully exited
	quitOnce sync.Once     // at most one WM_QUIT per run
}

// Capture holds the hook state and the token stream.
type Capture struct {
	mu         sync.Mutex
	enabled    bool
	buf        strings.Builder
	onToken    func(string)
	onKey      func(string) // optional live key preview
	terminator string       // "ENTER" or "TAB"
	run        *captureRun  // current active lifecycle; nil when stopped
}

// New creates a capture. onToken receives one complete reading line;
// onKey (optional) receives each accepted character live.
func New(terminator string, onToken func(string), onKey func(string)) *Capture {
	if terminator == "" {
		terminator = "ENTER"
	}
	return &Capture{onToken: onToken, onKey: onKey, terminator: terminator}
}

var hookRef *Capture // package-level so the callback trampoline can reach it

// Start installs the hook and runs its own message pump on a dedicated OS
// thread. Each call after a Stop creates a brand-new run, so the Capture is
// safely reusable across repeated toggle on/off cycles.
func (c *Capture) Start() error {
	c.mu.Lock()
	if c.enabled && c.run != nil {
		c.mu.Unlock()
		return nil
	}
	run := &captureRun{
		ready: make(chan struct{}),
		done:  make(chan struct{}),
	}
	c.run = run
	c.enabled = true
	c.mu.Unlock()

	hookRef = c
	go c.hookLoop(run)
	return nil
}

// hookLoop owns one captureRun. It never touches c.run/c.hook/c.running so an
// old loop exiting late cannot disturb a newer lifecycle that replaced it.
func (c *Capture) hookLoop(run *captureRun) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	c.mu.Lock()
	run.threadID = windows.GetCurrentThreadId()
	h, _, _ := procSetWindowsHookEx.Call(
		uintptr(whKeyboardLL),
		windows.NewCallback(hookProc),
		0, // hMod = 0 for WH_KEYBOARD_LL (callback in current process)
		0, // dwThreadId = 0 => global
	)
	run.hook = h
	installed := h != 0
	if !installed {
		// Installation failed: mark this run finished and drop enabled only if
		// we are still the active run.
		c.enabled = false
		c.run = nil
	}
	c.mu.Unlock()
	close(run.ready)
	if !installed {
		close(run.done)
		return
	}

	var msg struct {
		hwnd    uintptr
		message uint32
		wParam  uintptr
		lParam  uintptr
		time    uint32
		ptX     int32
		ptY     int32
	}
	for {
		r, _, _ := procGetMessage.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		if int32(r) <= 0 || msg.message == wmQuit {
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&msg)))
		procDispatchMessage.Call(uintptr(unsafe.Pointer(&msg)))
	}

	procUnhookWindowsHook.Call(run.hook)

	c.finishRun(run)
	close(run.done)
}

// finishRun clears the lifecycle state only when run is still the active one.
// This is the guard that makes a stale loop harmless: after Stop→Start a new
// run replaces the old, so the old loop's late exit must not clear the new
// run's enabled flag or run pointer.
func (c *Capture) finishRun(run *captureRun) {
	c.mu.Lock()
	if c.run == run {
		c.run = nil
		c.enabled = false
	}
	c.mu.Unlock()
}

// Stop posts WM_QUIT to this run's hook thread and waits for it to unhook.
// It is idempotent and, unlike the old stopOnce version, works on every
// subsequent Start→Stop cycle.
func (c *Capture) Stop() {
	c.mu.Lock()
	run := c.run
	c.enabled = false
	// A stop→start cycle reuses this instance; drop any half-typed token.
	c.buf.Reset()
	c.mu.Unlock()

	if run == nil {
		if hookRef == c {
			hookRef = nil
		}
		return
	}

	run.quitOnce.Do(func() {
		// Wait until the hook thread has finished installing. Without this, a
		// Stop issued immediately after Start could read threadID==0, skip the
		// WM_QUIT post, and leave the message loop blocked forever. ready is
		// always closed by hookLoop (success or failure), so this cannot hang.
		<-run.ready
		c.mu.Lock()
		tid := run.threadID
		c.mu.Unlock()
		if tid != 0 {
			procPostThreadMessage.Call(uintptr(tid), uintptr(wmQuit), 0, 0)
		}
	})
	select {
	case <-run.done:
	case <-time.After(1500 * time.Millisecond):
	}
	if hookRef == c {
		hookRef = nil
	}
}

func (c *Capture) IsEnabled() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.enabled && c.run != nil
}

// SetTerminator switches the token terminator at runtime.
func (c *Capture) SetTerminator(t string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if t == "TAB" || t == "ENTER" {
		c.terminator = t
	}
}

// ClearBuffer drops any partial token currently accumulating.
func (c *Capture) ClearBuffer() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.buf.Reset()
}

func hookProc(nCode, wParam, lParam uintptr) uintptr {
	if int32(nCode) >= 0 && hookRef != nil {
		if wParam == wmKeyDown || wParam == wmSysKeyDown {
			kb := (*kbdLLHookStruct)(unsafe.Pointer(lParam))
			hookRef.handleKey(kb.vkCode, kb.flags)
		}
	}
	r, _, _ := procCallNextHookEx.Call(0, nCode, wParam, lParam)
	return r
}

func (c *Capture) handleKey(vk uint32, flags uint32) {
	// injected events (from our own demo) are ignored to avoid feedback loops
	if flags&0x10 != 0 {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()

	isTerm := (c.terminator == "TAB" && vk == vkTab) ||
		(c.terminator == "ENTER" && vk == vkReturn)
	if isTerm {
		tok := strings.TrimSpace(c.buf.String())
		c.buf.Reset()
		if tok != "" && c.onToken != nil {
			fn := c.onToken
			go fn(tok)
		}
		return
	}

	ch := vkToChar(vk, flags)
	if ch == "" {
		return // ignore control / untranslatable keys
	}
	c.buf.WriteString(ch)
	if c.onKey != nil {
		fn := c.onKey
		go fn(ch)
	}
}

// vkToChar maps a virtual-key code + shift state to a displayable char.
// Covers digits, sign, decimal point, and unit letters used by calipers.
func vkToChar(vk uint32, flags uint32) string {
	ks, _, _ := procGetKeyState.Call(uintptr(vkShift))
	shift := (flags&1 != 0) || (ks&0x8000 != 0)
	// main digits
	if vk >= 0x30 && vk <= 0x39 {
		return string(rune('0' + (vk - 0x30)))
	}
	// letters A-Z
	if vk >= 0x41 && vk <= 0x5A {
		c := rune('a' + (vk - 0x41))
		if shift {
			c = rune('A' + (vk - 0x41))
		}
		return string(c)
	}
	// numpad digits
	if vk >= 0x60 && vk <= 0x69 {
		return string(rune('0' + (vk - 0x60)))
	}
	switch vk {
	case 0xBD: // - / _
		if shift {
			return "_"
		}
		return "-"
	case vkSubtract:
		return "-"
	case 0xBE: // . / >
		if shift {
			return ">"
		}
		return "."
	case vkDecimal:
		return "."
	case 0xBC: // , / <
		if shift {
			return "<"
		}
		return ","
	case 0xBA: // ; / :
		if shift {
			return ":"
		}
		return ";"
	}
	// fallback via MapVirtualKey -> unicode (best effort)
	r, _, _ := procMapVirtualKey.Call(uintptr(vk), 2) // MAPVK_VK_TO_CHAR = 2
	if r > 0 && r < 0x10000 {
		ch := rune(r & 0xFFFF)
		if ch >= 0x20 && ch < 0x7F {
			return string(ch)
		}
	}
	return ""
}
