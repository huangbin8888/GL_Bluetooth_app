//go:build windows

// Package hid Raw Input capture. Unlike WH_KEYBOARD_LL (which only sees
// virtual-key codes with no device identity), WM_INPUT carries hDevice so we
// can fingerprint each physical caliper and route its readings to a bound
// channel. The capture runs a hidden window + message pump on its own locked
// OS thread, so it never touches walk's window tree (the source of earlier
// repaint/flicker regressions).
package hid

import (
	"errors"
	"runtime"
	"strings"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Raw Input constants not exposed by x/sys/windows.
const (
	riInput             = 0x10000003
	riDeviceName        = 0x20000007
	rimTypeKeyboard     = 1
	wmInputMsg          = 0x00FF
	wmKeyDownRaw        = 0x0100
	wmSysKeyDownRaw     = 0x0104
	ridevInputSink      = 0x00000100
	ridevRemove         = 0x00000001
	hidUsagePageDesktop = 0x01
	hidUsageKeyboard    = 0x06
)

var (
	user32Raw   = windows.NewLazySystemDLL("user32.dll")
	kernel32Raw = windows.NewLazySystemDLL("kernel32.dll")

	procRegisterClassExW        = user32Raw.NewProc("RegisterClassExW")
	procCreateWindowExW         = user32Raw.NewProc("CreateWindowExW")
	procDestroyWindow           = user32Raw.NewProc("DestroyWindow")
	procUnregisterClassW        = user32Raw.NewProc("UnregisterClassW")
	procDefWindowProcW          = user32Raw.NewProc("DefWindowProcW")
	procGetRawInputData         = user32Raw.NewProc("GetRawInputData")
	procGetRawInputDeviceInfoW  = user32Raw.NewProc("GetRawInputDeviceInfoW")
	procGetRawInputDeviceList   = user32Raw.NewProc("GetRawInputDeviceList")
	procRegisterRawInputDevices = user32Raw.NewProc("RegisterRawInputDevices")
	procGetModuleHandleW        = kernel32Raw.NewProc("GetModuleHandleW")
)

// Windows structs used by Raw Input. Defined locally to keep the hid package
// dependent only on x/sys/windows.
type wndClassExW struct {
	CbSize        uint32
	Style         uint32
	LpfnWndProc   uintptr
	CnClsExtra    int32
	CbWndExtra    int32
	HInstance     windows.Handle
	HIcon         windows.Handle
	HCursor       windows.Handle
	HbrBackground windows.Handle
	LpszMenuName  *uint16
	LpszClassName *uint16
	HIconSm       windows.Handle
}

type rawInputHeader struct {
	DwType  uint32
	DwSize  uint32
	HDevice windows.Handle
	WParam  uintptr
}

type rawKeyboard struct {
	MakeCode         uint16
	Flags            uint16
	Reserved         uint16
	VKey             uint16
	Message          uint32
	ExtraInformation uint32
}

type rawInputDevice struct {
	UsUsagePage uint16
	UsUsage     uint16
	DwFlags     uint32
	HwndTarget  uintptr
}

type rawInputDeviceList struct {
	HDevice windows.Handle
	DwType  uint32
}

// rawRun is one Start->Stop lifecycle of the Raw Input message window.
type rawRun struct {
	threadID uint32
	hwnd     uintptr
	ready    chan struct{}
	done     chan struct{}
	quitOnce sync.Once
	err      error // set when setup fails so Start can report it
}

// RawCapture captures keyboard calipers through WM_INPUT and reports the
// hardware fingerprint alongside each completed reading.
type RawCapture struct {
	mu         sync.Mutex
	enabled    bool
	bufs       map[string]*strings.Builder // token assembly per device fingerprint
	onToken    func(token, fingerprint string)
	terminator string
	run        *rawRun
}

// HIDDeviceInfo describes one keyboard-class HID device visible to Raw Input.
type HIDDeviceInfo struct {
	Fingerprint string // normalised device path, stable key for channel binding
	Name        string // same as Fingerprint (kept for UI clarity)
}

var (
	rawHookRef        *RawCapture // reached by the global window callback
	rawClassOnce      sync.Once
	rawClassName      = windows.StringToUTF16Ptr("GLCaliperRawInputMsg")
	rawWindowCallback = windows.NewCallback(rawWindowProc)
)

// NewRaw creates a Raw Input capture. onToken receives (reading, fingerprint)
// for each terminator-delimited token.
func NewRaw(terminator string, onToken func(string, string)) *RawCapture {
	if terminator == "" {
		terminator = "ENTER"
	}
	return &RawCapture{onToken: onToken, terminator: terminator, bufs: make(map[string]*strings.Builder)}
}

// Start registers for keyboard Raw Input on a hidden window and pumps its
// messages on a dedicated OS thread. Safe to call again after Stop.
func (c *RawCapture) Start() error {
	c.mu.Lock()
	if c.enabled && c.run != nil {
		c.mu.Unlock()
		return nil
	}
	run := &rawRun{ready: make(chan struct{}), done: make(chan struct{})}
	c.run = run
	c.enabled = true
	c.mu.Unlock()

	rawHookRef = c
	go c.rawLoop(run)
	<-run.ready
	c.mu.Lock()
	err := run.err
	c.mu.Unlock()
	return err
}

func (c *RawCapture) rawLoop(run *rawRun) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	// Register the hidden window class once per process. If registration fails
	// (e.g. class already exists from a prior process lifetime), CreateWindow
	// below reports the real outcome through run.err.
	rawClassOnce.Do(func() {
		hInst, _, _ := procGetModuleHandleW.Call(0)
		wc := wndClassExW{
			CbSize:        uint32(unsafe.Sizeof(wndClassExW{})),
			LpfnWndProc:   rawWindowCallback,
			HInstance:     windows.Handle(hInst),
			LpszClassName: rawClassName,
		}
		procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))
	})

	hInst, _, _ := procGetModuleHandleW.Call(0)
	hwnd, _, _ := procCreateWindowExW.Call(0,
		uintptr(unsafe.Pointer(rawClassName)), 0, 0,
		0, 0, 0, 0, 0, 0, uintptr(hInst), 0)
	if hwnd == 0 {
		c.failRun(run, "无法创建 Raw Input 隐藏窗口")
		return
	}

	rid := rawInputDevice{
		UsUsagePage: hidUsagePageDesktop,
		UsUsage:     hidUsageKeyboard,
		DwFlags:     ridevInputSink, // receive even when our window is not focused
		HwndTarget:  hwnd,
	}
	ok, _, _ := procRegisterRawInputDevices.Call(
		uintptr(unsafe.Pointer(&rid)), 1, uintptr(unsafe.Sizeof(rid)))
	if ok == 0 {
		procDestroyWindow.Call(hwnd)
		c.failRun(run, "注册 Raw Input 键盘设备失败")
		return
	}

	c.mu.Lock()
	run.threadID = windows.GetCurrentThreadId()
	run.hwnd = hwnd
	c.mu.Unlock()
	close(run.ready)

	var msg struct {
		Hwnd    uintptr
		Message uint32
		WParam  uintptr
		LParam  uintptr
		Time    uint32
		PtX     int32
		PtY     int32
	}
	for {
		r, _, _ := procGetMessage.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		if int32(r) <= 0 || msg.Message == wmQuit {
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&msg)))
		procDispatchMessage.Call(uintptr(unsafe.Pointer(&msg)))
	}

	// Unregister and tear down the hidden window.
	remove := rawInputDevice{UsUsagePage: hidUsagePageDesktop, UsUsage: hidUsageKeyboard, DwFlags: ridevRemove}
	procRegisterRawInputDevices.Call(uintptr(unsafe.Pointer(&remove)), 1, uintptr(unsafe.Sizeof(remove)))
	procDestroyWindow.Call(hwnd)

	c.finishRun(run)
	close(run.done)
}

func (c *RawCapture) failRun(run *rawRun, reason string) {
	c.mu.Lock()
	run.err = errors.New(reason)
	c.mu.Unlock()
	c.finishRun(run)
	close(run.ready)
	close(run.done)
}

func (c *RawCapture) finishRun(run *rawRun) {
	c.mu.Lock()
	if c.run == run {
		c.run = nil
		c.enabled = false
	}
	if rawHookRef == c {
		rawHookRef = nil
	}
	// A stop→start cycle reuses this instance. Drop any half-typed token so
	// the next session never prefixes its first reading with stale digits.
	for k := range c.bufs {
		delete(c.bufs, k)
	}
	c.mu.Unlock()
}

// Stop posts WM_QUIT to the Raw Input window thread and waits for teardown.
func (c *RawCapture) Stop() {
	c.mu.Lock()
	run := c.run
	c.enabled = false
	for k := range c.bufs {
		delete(c.bufs, k)
	}
	c.mu.Unlock()
	if run == nil {
		return
	}
	run.quitOnce.Do(func() {
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
}

func (c *RawCapture) IsEnabled() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.enabled && c.run != nil
}

func (c *RawCapture) SetTerminator(t string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if t == "TAB" || t == "ENTER" {
		c.terminator = t
	}
}

func (c *RawCapture) ClearBuffer() {
	c.mu.Lock()
	defer c.mu.Unlock()
	for k := range c.bufs {
		delete(c.bufs, k)
	}
}

// rawWindowProc is the global callback for the hidden Raw Input window.
func rawWindowProc(hwnd uintptr, msg uint32, wParam, lParam uintptr) uintptr {
	if msg == wmInputMsg {
		handleWmInput(lParam)
		return 0
	}
	r, _, _ := procDefWindowProcW.Call(hwnd, uintptr(msg), wParam, lParam)
	return r
}

func handleWmInput(hRawInput uintptr) {
	c := rawHookRef
	if c == nil {
		return
	}
	headerSize := uint32(unsafe.Sizeof(rawInputHeader{}))
	var size uint32
	procGetRawInputData.Call(hRawInput, riInput, 0, uintptr(unsafe.Pointer(&size)), uintptr(headerSize))
	if size == 0 || size > 4096 {
		return
	}
	buf := make([]byte, size)
	ret, _, _ := procGetRawInputData.Call(hRawInput, riInput,
		uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)), uintptr(headerSize))
	if ret == ^uintptr(0) || ret == 0 {
		return
	}
	header := (*rawInputHeader)(unsafe.Pointer(&buf[0]))
	if header.DwType != rimTypeKeyboard {
		return
	}
	off := int(unsafe.Sizeof(rawInputHeader{}))
	if len(buf) < off+int(unsafe.Sizeof(rawKeyboard{})) {
		return
	}
	kb := (*rawKeyboard)(unsafe.Pointer(&buf[off]))
	if kb.Message != wmKeyDownRaw && kb.Message != wmSysKeyDownRaw {
		return
	}
	fingerprint := deviceFingerprintFromHandle(header.HDevice)
	c.handleRawKey(uint32(kb.VKey), fingerprint)
}

// bufFor returns the token-assembly buffer belonging to one device. Different
// calipers type concurrently, so sharing a single strings.Builder let their
// digits interleave into one bogus reading and mis-attributed the fingerprint.
// The caller must hold c.mu.
func (c *RawCapture) bufFor(fingerprint string) *strings.Builder {
	b := c.bufs[fingerprint]
	if b == nil {
		b = &strings.Builder{}
		c.bufs[fingerprint] = b
	}
	return b
}

// terminatorKey maps the configured terminator to its virtual-key code.
func (c *RawCapture) terminatorKey() uint32 {
	if c.terminator == "TAB" {
		return vkTab
	}
	return vkReturn // "ENTER" (the only other accepted setting)
}

func (c *RawCapture) handleRawKey(vk uint32, fingerprint string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	// Only the configured terminator commits a reading. Accepting both keys
	// made the setting inert and could cut one device's reading short when a
	// second device emitted the other key.
	if vk == c.terminatorKey() {
		buf := c.bufFor(fingerprint)
		tok := strings.TrimSpace(buf.String())
		buf.Reset()
		if tok != "" && c.onToken != nil {
			fn := c.onToken
			go fn(tok, fingerprint)
		}
		return
	}
	ch := vkToChar(vk, 0)
	if ch == "" {
		return
	}
	c.bufFor(fingerprint).WriteString(ch)
}

// deviceFingerprintFromHandle returns the normalised device path for hDevice.
func deviceFingerprintFromHandle(h windows.Handle) string {
	var size uint32
	procGetRawInputDeviceInfoW.Call(uintptr(h), riDeviceName, 0, uintptr(unsafe.Pointer(&size)))
	if size == 0 || size > 4096 {
		return ""
	}
	buf := make([]uint16, (size+1)/2)
	n, _, _ := procGetRawInputDeviceInfoW.Call(uintptr(h), riDeviceName,
		uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)))
	if n == 0 {
		return ""
	}
	return NormalizeFingerprint(windows.UTF16ToString(buf[:n]))
}

// NormalizeFingerprint strips noisy suffixes from a Raw Input device path so
// the same physical device yields a stable key across plug/unplug cycles.
func NormalizeFingerprint(name string) string {
	s := strings.ToUpper(strings.TrimSpace(name))
	// Drop the trailing interface GUID: "...#{884b96c3-...}" -> "...".
	if i := strings.Index(s, "#{"); i >= 0 {
		s = s[:i]
	}
	// Drop the "\\?\" device namespace prefix for readability.
	s = strings.TrimPrefix(s, `\\?\`)
	return strings.TrimSpace(s)
}

// ListHIDDevices enumerates all keyboard-class devices visible to Raw Input.
// It is the self-check entry point used by the settings page.
func ListHIDDevices() []HIDDeviceInfo {
	entrySize := uint32(unsafe.Sizeof(rawInputDeviceList{}))
	var count uint32
	procGetRawInputDeviceList.Call(0, uintptr(unsafe.Pointer(&count)), uintptr(entrySize))
	if count == 0 || count > 512 {
		return nil
	}
	list := make([]rawInputDeviceList, count)
	n, _, _ := procGetRawInputDeviceList.Call(
		uintptr(unsafe.Pointer(&list[0])), uintptr(unsafe.Pointer(&count)), uintptr(entrySize))
	out := make([]HIDDeviceInfo, 0)
	for i := 0; i < int(n); i++ {
		if list[i].DwType != rimTypeKeyboard {
			continue
		}
		fp := deviceFingerprintFromHandle(list[i].HDevice)
		if fp == "" {
			continue
		}
		out = append(out, HIDDeviceInfo{Fingerprint: fp, Name: fp})
	}
	return out
}
