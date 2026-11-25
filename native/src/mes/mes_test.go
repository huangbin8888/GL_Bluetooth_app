package mes

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"glcaliper/protocol"
)

func sampleItem(seq int, verdict string) UploadItem {
	return UploadItem{
		Record: protocol.Record{
			Seq: seq, Date: "2026-11-20", T: "09:30:00",
			DeviceMAC: "AA:BB:CC:DD:EE:FF", Ch: 2,
			ValueUM: 10050, RawUnit: "m", Verdict: verdict,
			Batch: "BATCH-1", Src: "BLE",
		},
		ChannelName: "内径",
	}
}

// TestSendPostPayload verifies the exact JSON field mapping and Bearer token
// against a local httptest server (acceptance criterion 3).
func TestSendPostPayload(t *testing.T) {
	var gotBody []byte
	var gotAuth, gotCT string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotCT = r.Header.Get("Content-Type")
		gotAuth = r.Header.Get("Authorization")
		gotBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	cfg := Config{Enable: true, URL: srv.URL, Token: "secret", TimeoutMS: 1000}.Normalize()
	c := New(cfg, nil)
	body, err := cfg.buildBody(sampleItem(7, "合格"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.send(body); err != nil {
		t.Fatalf("send: %v", err)
	}

	if gotCT != "application/json" {
		t.Errorf("Content-Type = %q", gotCT)
	}
	if gotAuth != "Bearer secret" {
		t.Errorf("Authorization = %q, want Bearer secret", gotAuth)
	}
	var p map[string]interface{}
	if err := json.Unmarshal(gotBody, &p); err != nil {
		t.Fatalf("body not JSON: %v / %s", err, gotBody)
	}
	checks := map[string]interface{}{
		"device": "AA:BB:CC:DD:EE:FF", "channel": "CH2",
		"channelName": "内径", "value": 10.05, "unit": "mm",
		"verdict": "合格", "batch": "BATCH-1",
		"timestamp": "2026-11-20 09:30:00", "seq": float64(7), "source": "BLE",
	}
	for k, want := range checks {
		got, ok := p[k]
		if !ok {
			t.Errorf("missing field %q in %s", k, gotBody)
			continue
		}
		if got != want {
			t.Errorf("field %q = %v (%T), want %v", k, got, got, want)
		}
	}
}

// TestFieldPrefix verifies every key is prefixed.
func TestFieldPrefix(t *testing.T) {
	cfg := Config{FieldPrefix: "mes_"}.Normalize()
	body, err := cfg.buildBody(sampleItem(1, "合格"))
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatal(err)
	}
	if len(m) != 10 {
		t.Fatalf("prefaced field count = %d, want 10: %s", len(m), body)
	}
	for k := range m {
		if !strings.HasPrefix(k, "mes_") {
			t.Errorf("field %q not prefixed", k)
		}
	}
	if _, ok := m["mes_value"]; !ok {
		t.Error("missing mes_value")
	}
}

// TestScopeFilter verifies all/pass/fail selection.
func TestScopeFilter(t *testing.T) {
	if !inScope(ScopeAll, "合格") || !inScope(ScopeAll, "超差") {
		t.Error("ScopeAll should accept both")
	}
	if !inScope(ScopePass, "合格") || inScope(ScopePass, "超差") {
		t.Error("ScopePass wrong")
	}
	if inScope(ScopeFail, "合格") || !inScope(ScopeFail, "超差") {
		t.Error("ScopeFail wrong")
	}
}

// TestEnqueueDisabled verifies a disabled client accepts nothing (no
// behaviour change when MES is off).
func TestEnqueueDisabled(t *testing.T) {
	c := New(Config{Enable: false, URL: "http://x"}, nil)
	if c.Enqueue(sampleItem(1, "合格")) {
		t.Error("disabled client accepted an item")
	}
	c2 := New(Config{Enable: true, URL: ""}, nil)
	if c2.Enqueue(sampleItem(1, "合格")) {
		t.Error("blank URL client accepted an item")
	}
}

// TestAsyncUploadEndToEnd verifies records flow through Enqueue -> worker ->
// HTTP POST, with the status callback observing success.
func TestAsyncUploadEndToEnd(t *testing.T) {
	received := make(chan int, 16)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		var p Payload
		_ = json.Unmarshal(b, &p)
		received <- p.Seq
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	var mu sync.Mutex
	var statuses []Status
	c := New(Config{Enable: true, URL: srv.URL, TimeoutMS: 1000, QueueSize: 10}, func(s Status) {
		mu.Lock()
		statuses = append(statuses, s)
		mu.Unlock()
	})
	c.Start()
	for i := 1; i <= 5; i++ {
		if !c.Enqueue(sampleItem(i, "合格")) {
			t.Fatalf("enqueue %d failed", i)
		}
	}
	deadline := time.After(3 * time.Second)
	got := 0
	for got < 5 {
		select {
		case <-received:
			got++
		case <-deadline:
			t.Fatalf("only received %d/5 uploads", got)
		}
	}
	c.Stop()
	mu.Lock()
	defer mu.Unlock()
	okCount := 0
	for _, s := range statuses {
		if s.OK {
			okCount++
		}
	}
	if okCount != 5 {
		t.Errorf("success statuses = %d, want 5: %+v", okCount, statuses)
	}
}

// TestRetryThenSuccess verifies transient 500 triggers retry and eventual OK.
func TestRetryThenSuccess(t *testing.T) {
	attempts := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts < 2 {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	done := make(chan Status, 1)
	c := New(Config{Enable: true, URL: srv.URL, TimeoutMS: 500, Retry: 2, QueueSize: 5}, func(s Status) {
		done <- s
	})
	c.Start()
	c.Enqueue(sampleItem(1, "合格"))
	select {
	case s := <-done:
		if !s.OK {
			t.Errorf("expected eventual success, got %+v", s)
		}
		if s.Attempts != 2 {
			t.Errorf("attempts = %d, want 2", s.Attempts)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("retry test timeout")
	}
	c.Stop()
}

// TestQueueOverflow verifies the bounded queue rejects the incoming item and
// reports a dropped status.
func TestQueueOverflow(t *testing.T) {
	// No worker started -> queue never drains.
	c := New(Config{Enable: true, URL: "http://127.0.0.1:1", QueueSize: 2}, nil)
	dropped := make(chan bool, 1)
	c.onStat = func(s Status) {
		if s.Dropped {
			select {
			case dropped <- true:
			default:
			}
		}
	}
	if !c.Enqueue(sampleItem(1, "合格")) || !c.Enqueue(sampleItem(2, "合格")) {
		t.Fatal("first two enqueues should succeed")
	}
	if c.Enqueue(sampleItem(3, "合格")) {
		t.Error("third enqueue should be rejected when full")
	}
	select {
	case <-dropped:
	case <-time.After(time.Second):
		t.Error("no drop status emitted")
	}
}
