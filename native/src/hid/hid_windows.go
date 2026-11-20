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

	procSetWindowsHookEx   = user32.NewProc("SetWindowsHookExW")
	procUnhookWindowsHook  = user32.NewProc("UnhookWindowsHookEx")
	procCallNextHookEx     = user32.NewProc("CallNextHookEx")
	procGetMessage         = user32.NewProc("GetMessageW")
	procTranslateMessage   = user32.NewProc("TranslateMessage")
	procDispatchMessage    = user32.NewProc("DispatchMessageW")
	procMapVirtualKey      = user32.NewProc("MapVirtualKeyW")
	procGetKeyState        = user32.NewProc("GetKeyState")
	procPostThreadMessage  = user32.NewProc("PostThreadMessageW")
)

const (
	whKeyboardLL = 13
	hcAction     = 0
	wmKeyDown    = 0x0100
	wmSysKeyDown = 0x0104
	wmQuit       = 0x0012

	vkTab    = 0x09
	vkReturn = 0x0D
	vkShift  = 0x10
	vkDecimal = 0x6E // numpad '.'
	vkSubtract = 0x6D // numpad '-'
)

type kbdLLHookStruct struct {
	vkCode     uint32
	scanCode   uint32
	flags      uint32
	time       uint32
	dwExtraInfo uintptr
}

// Capture holds the hook state and the token stream.
type Capture struct {
	mu        sync.Mutex
	hook      uintptr
	threadID  uint32
	enabled   bool
	buf       strings.Builder
	onToken   func(string)
	onKey     func(string) // optional live key preview
	terminator string     // "ENTER" or "TAB"
	running   chan struct{}
	stopOnce  sync.Once
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
// thread via lockOSThread semantics enforced by the caller (walk runs on the
// UI goroutine; we spin a separate goroutine that locks itself to a thread).
func (c *Capture) Start() error {
	c.mu.Lock()
	if c.enabled {
		c.mu.Unlock()
		return nil
	}
	c.running = make(chan struct{})
	c.enabled = true
	c.mu.Unlock()

	hookRef = c
	go c.hookLoop()
	return nil
}

func (c *Capture) hookLoop() {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	c.mu.Lock()
	c.threadID = windows.GetCurrentThreadId()
	h, _, callErr := procSetWindowsHookEx.Call(
		uintptr(whKeyboardLL),
		windows.NewCallback(hookProc),
		0, // hMod = 0 for WH_KEYBOARD_LL (callback in current process)
		0, // dwThreadId = 0 => global
	)
	c.hook = h
	if c.hook == 0 {
		c.mu.Unlock()
		c.mu.Lock()
		c.enabled = false
		c.mu.Unlock()
		close(c.running)
		return
	}
	_ = callErr
	c.mu.Unlock()

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
		if int32(r) <= 0 {
			break
		}
		if msg.message == wmQuit {
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&msg)))
		procDispatchMessage.Call(uintptr(unsafe.Pointer(&msg)))
	}

	procUnhookWindowsHook.Call(uintptr(c.hook))
	c.mu.Lock()
	c.hook = 0
	c.enabled = false
	c.mu.Unlock()
	close(c.running)
}

// Stop posts WM_QUIT to the hook thread and waits for it to unhook.
func (c *Capture) Stop() {
	c.stopOnce.Do(func() {
		c.mu.Lock()
		tid, enabled := c.threadID, c.enabled
		ch := c.running
		c.enabled = false
		c.mu.Unlock()
		if enabled && tid != 0 && ch != nil {
			procPostThreadMessage.Call(uintptr(tid), uintptr(wmQuit), 0, 0)
			select {
			case <-ch:
			case <-time.After(1500 * time.Millisecond):
			}
		}
		if hookRef == c {
			hookRef = nil
		}
	})
}

func (c *Capture) IsEnabled() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.enabled
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
	// Always accept the other terminator too, so a mismatched device setting
	// never merges two readings.
	if vk == vkTab || vk == vkReturn {
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
	_ = isTerm
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
