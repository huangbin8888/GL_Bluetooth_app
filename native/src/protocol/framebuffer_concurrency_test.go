package protocol

import (
	"fmt"
	"sync"
	"testing"
)

const fbConcurrentWriters = 8

// TestFrameBufferConcurrentPushFlush is the regression guard for the F1
// data race: FeedRaw (BLE notify goroutine) and FlushPending (400ms ticker)
// call Push/Flush on the SAME FrameBuffer from different goroutines.
//
// The invariant under test: every complete frame that a goroutine pushes in
// one shot must be observable exactly once, either in that Push's return or
// in a concurrent Flush's return. With an unsynchronized buf string this
// tears: chunks interleave mid-frame, frames are lost/corrupted or the Go
// runtime raises a concurrent-write fatal. The mutual exclusion added to
// FrameBuffer keeps the conservation invariant.
//
// This test is deliberately race-detector-independent: it asserts a logical
// conservation property (unique values, no loss, no duplicates) so it also
// guards when `go test -race` is unavailable (no C toolchain on the build box).
func TestFrameBufferConcurrentPushFlush(t *testing.T) {
	const (
		writers     = fbConcurrentWriters
		perWriter   = 400
		totalFrames = writers * perWriter
	)

	fb := NewFrameBuffer()
	var wg sync.WaitGroup

	// Collect every data frame observed from any Push or Flush return.
	var mu sync.Mutex
	seen := make(map[int64]int) // ValueUM -> count

	observe := func(items []Item) {
		for _, it := range items {
			if it.Type != "data" || it.Frame == nil {
				continue
			}
			mu.Lock()
			seen[it.Frame.ValueUM]++
			mu.Unlock()
		}
	}

	// Writers: each pushes a unique integral-mm frame in a single call. Using
	// unique values (1..N mm) makes loss and duplication directly detectable.
	for w := 0; w < writers; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			for i := 0; i < perWriter; i++ {
				mm := w*perWriter + i + 1
				observe(fb.Push(fmt.Sprintf("DAT0:m+%d", mm)))
			}
		}(w)
	}

	// Concurrent flushers: race the writers exactly like the idle ticker does.
	for f := 0; f < 2; f++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < perWriter; i++ {
				observe(fb.Flush())
			}
		}()
	}

	wg.Wait()
	// Final flush drains any tail left in the buffer.
	observe(fb.Flush())

	// Conservation: every pushed value must be seen exactly once.
	missing := 0
	for mm := 1; mm <= totalFrames; mm++ {
		um := int64(mm) * UMPerMM
		if seen[um] != 1 {
			missing++
			if missing <= 5 {
				t.Errorf("value %dmm (UM=%d): observed %d times, want 1", mm, um, seen[um])
			}
		}
	}
	if missing > 0 {
		t.Fatalf("frame conservation violated: %d/%d values lost or duplicated under concurrent Push/Flush", missing, totalFrames)
	}
	if extra := len(seen) - totalFrames; extra > 0 {
		t.Fatalf("observed %d unexpected frame values (corruption under race)", extra)
	}
}

// TestFrameBufferConcurrentReset ensures Reset (called by core.Reset while a
// BLE notify may be in flight) can coexist with Push without losing buffer
// integrity or panicking.
func TestFrameBufferConcurrentReset(t *testing.T) {
	fb := NewFrameBuffer()
	var wg sync.WaitGroup
	for w := 0; w < fbConcurrentWriters; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 500; i++ {
				fb.Push("DAT0:m+1.25")
			}
		}()
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 500; i++ {
			fb.Reset()
			_ = fb.Pending()
		}
	}()
	wg.Wait()
	// Reaching here without a fatal/concurrent-write error is the assertion:
	// an unlocked buf would trip the Go runtime under this contention.
}
