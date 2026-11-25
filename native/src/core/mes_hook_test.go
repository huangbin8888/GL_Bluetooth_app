package core

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"glcaliper/mes"
	"glcaliper/protocol"
)

// TestMESDisabledNoEffect verifies that with MES off (the default) commit()
// behaves exactly as before: records are stored, no client is active.
func TestMESDisabledNoEffect(t *testing.T) {
	a := NewApp()
	s := a.GetSettings()
	if s.MES.Enable {
		t.Fatal("MES must default to disabled")
	}
	a.ConfigureMES(s)
	if a.MESActive() {
		t.Fatal("MES client should not exist when disabled")
	}
	a.FeedDemoValue(10.0, 1)
	if a.Count() != 1 {
		t.Fatalf("records = %d, want 1", a.Count())
	}
	if a.MESQueueLen() != 0 {
		t.Errorf("queue len = %d, want 0", a.MESQueueLen())
	}
}

// TestMESEnabledReceivesRecord verifies the full chain commit() -> mes.Client
// -> HTTP POST, including that the persisted channel name is forwarded.
func TestMESEnabledReceivesRecord(t *testing.T) {
	got := make(chan mes.Payload, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		var p mes.Payload
		_ = json.Unmarshal(b, &p)
		got <- p
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	a := NewApp()
	s := a.GetSettings()
	s.MES = MESSettings{Enable: true, URL: srv.URL, TimeoutMS: 500, Scope: "all"}
	s.Normalize()
	s.Channels[0].Name = "外径"
	a.SetSettings(s)
	a.ConfigureMES(s)
	defer a.ConfigureMES(DefaultSettings()) // stop worker

	a.FeedDemoValue(10.02, 1)
	select {
	case p := <-got:
		if p.ChannelName != "外径" {
			t.Errorf("channelName = %q, want 外径", p.ChannelName)
		}
		if p.Channel != "CH1" {
			t.Errorf("channel = %q, want CH1", p.Channel)
		}
		if p.Value < 10.01 || p.Value > 10.03 {
			t.Errorf("value = %v, want ~10.02", p.Value)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("MES server did not receive the record")
	}
}

// TestOldConfigMESDefaults verifies a legacy JSON config without a "mes" key
// normalizes to disabled with safe defaults (backward compatibility).
func TestOldConfigMESDefaults(t *testing.T) {
	var s Settings
	if err := json.Unmarshal([]byte(`{"Unit":"mm","Digits":2,"Batch":"B"}`), &s); err != nil {
		t.Fatal(err)
	}
	s.Normalize()
	if s.MES.Enable {
		t.Error("legacy config must not enable MES")
	}
	if s.MES.QueueSize <= 0 || s.MES.TimeoutMS <= 0 {
		t.Errorf("MES defaults not applied: %+v", s.MES)
	}
	if s.MES.Scope != "all" {
		t.Errorf("scope = %q, want all", s.MES.Scope)
	}
}

// TestConfigureMESHotReloadNonBlocking verifies that reconfiguring while an
// old uploader has a backed-up queue does not block the caller (UI thread).
func TestConfigureMESHotReloadNonBlocking(t *testing.T) {
	a := NewApp()
	// Point at an unroutable address so sends hang until timeout; fill queue.
	s := a.GetSettings()
	s.MES = MESSettings{Enable: true, URL: "http://10.255.255.1:9", TimeoutMS: 3000, Retry: 5, QueueSize: 50}
	s.Normalize()
	a.SetSettings(s)
	a.ConfigureMES(s)
	for i := 0; i < 50; i++ {
		a.mesMu.RLock()
		ctl := a.mesCtl
		a.mesMu.RUnlock()
		ctl.Enqueue(mes.UploadItem{Record: protocol.Record{Seq: i + 1, Verdict: "合格"}, ChannelName: "CH1"})
	}

	// Reconfigure with MES off and measure how long the UI-facing call takes.
	off := a.GetSettings()
	off.MES.Enable = false
	done := make(chan struct{})
	go func() {
		a.ConfigureMES(off)
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(500 * time.Millisecond):
		t.Fatal("ConfigureMES blocked while draining the old backed-up queue")
	}
	if a.MESActive() {
		t.Error("MES should be inactive after disabling")
	}
}

// TestCommitWithNilFrameNoUpload ensures a nil frame neither records nor uploads.
func TestCommitWithNilFrameNoUpload(t *testing.T) {
	a := NewApp()
	s := a.GetSettings()
	s.MES.Enable = true
	s.MES.URL = "http://127.0.0.1:1"
	a.SetSettings(s)
	a.ConfigureMES(s)
	a.commit(nil, 1, "TEST")
	if a.Count() != 0 || a.MESQueueLen() != 0 {
		t.Error("nil frame should not record or upload")
	}
}

// Ensure the protocol import is used for the Record type in a compile check.
var _ = protocol.Record{}
