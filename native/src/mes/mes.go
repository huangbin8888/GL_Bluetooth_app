// Package mes implements the generic, configurable MES real-time upload
// module. Every measurement committed by core.App can be asynchronously
// POSTed as JSON to a configured cloud URL. The package uses only the Go
// standard library (net/http), keeps no on-disk state, and defaults to OFF so
// that a build without MES configuration behaves exactly like the old version.
//
// Design contract (requirement lock 20261120-0923):
//   - HTTP REST POST JSON, Bearer token in Authorization header.
//   - Non-blocking: Enqueue never waits for the network. A bounded in-memory
//     queue (default 500) rejects the incoming item on overflow with a status
//     callback (drop-newest), keeping already-queued data intact.
//   - Failed sends are retried in-process with exponential backoff up to a
//     configurable count; the queue is memory-only and clears on restart.
//   - Field name prefix is configurable to ease integration with real MES.
//   - Scope filter: all / pass only / fail only.
package mes

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"glcaliper/protocol"
)

// Scope selects which committed records are uploaded.
type Scope string

const (
	ScopeAll  Scope = "all"  // every record
	ScopePass Scope = "pass" // verdict == 合格/OK/PASS only
	ScopeFail Scope = "fail" // out-of-tolerance only
)

// Config is a snapshot of the MES settings. It is immutable once passed to
// New/Configure; changing settings rebuilds a client.
type Config struct {
	Enable      bool
	URL         string
	Token       string
	FieldPrefix string // prepended to every JSON key; empty = no prefix
	TimeoutMS   int
	Retry       int
	QueueSize   int
	Scope       Scope
}

// Normalize fills safe defaults and validates the URL/scope. It never mutates
// the caller's Config.
func (c Config) Normalize() Config {
	if c.TimeoutMS <= 0 {
		c.TimeoutMS = 5000
	}
	if c.Retry < 0 {
		c.Retry = 0
	}
	if c.Retry > 5 {
		c.Retry = 5
	}
	if c.QueueSize <= 0 {
		c.QueueSize = 500
	}
	if c.FieldPrefix == "" {
		c.FieldPrefix = ""
	}
	switch c.Scope {
	case ScopeAll, ScopePass, ScopeFail:
	default:
		c.Scope = ScopeAll
	}
	return c
}

// Enabled reports whether a client should actually send. A blank URL disables
// sending even when Enable is true, preventing misconfiguration noise.
func (c Config) Enabled() bool {
	return c.Enable && strings.TrimSpace(c.URL) != ""
}

// Payload is the JSON body sent to the MES endpoint. Field names are the
// generic keys from the requirement lock; key() applies FieldPrefix.
type Payload struct {
	Device      string  `json:"device"`
	Channel     string  `json:"channel"`
	ChannelName string  `json:"channelName"`
	Value       float64 `json:"value"`
	Unit        string  `json:"unit"`
	Verdict     string  `json:"verdict"`
	Batch       string  `json:"batch"`
	Timestamp   string  `json:"timestamp"`
	Seq         int     `json:"seq"`
	Source      string  `json:"source"`
}

// UploadItem carries the resolved channel name alongside a record so the
// worker can build the payload without depending on core.Settings.
type UploadItem struct {
	Record      protocol.Record
	ChannelName string
}

// Status is emitted on every terminal upload outcome and on queue overflow.
type Status struct {
	OK       bool
	Seq      int
	Err      string
	Queued   int // current queue length at emit time
	Dropped  bool
	Attempts int
}

// Client is a single-flight asynchronous uploader. A worker goroutine drains
// the queue sequentially so HTTP connections and retry ordering stay simple.
type Client struct {
	cfg    Config
	client *http.Client
	queue  chan UploadItem
	onStat func(Status)

	stopOnce sync.Once
	done     chan struct{}
	mu       sync.Mutex
	queued   int
	pending  map[int]bool // seq currently in-flight or queued, for dedup metrics
}

// OnStatus installs a status callback. It is safe to call before Start.
func (c *Client) OnStatus(fn func(Status)) { c.onStat = fn }

// New builds a client from cfg. Call Start to spawn the worker.
func New(cfg Config, onStatus func(Status)) *Client {
	cfg = cfg.Normalize()
	return &Client{
		cfg:     cfg,
		client:  &http.Client{Timeout: time.Duration(cfg.TimeoutMS) * time.Millisecond},
		queue:   make(chan UploadItem, cfg.QueueSize),
		onStat:  onStatus,
		done:    make(chan struct{}),
		pending: make(map[int]bool),
	}
}

// Start launches the background worker. Safe to call once.
func (c *Client) Start() {
	go c.worker()
}

// Len returns the current queue depth.
func (c *Client) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.queued
}

// inScope applies the configured scope filter to a verdict string.
func inScope(scope Scope, verdict string) bool {
	switch scope {
	case ScopePass:
		return verdict == "合格" || verdict == "OK" || verdict == "PASS"
	case ScopeFail:
		return !(verdict == "合格" || verdict == "OK" || verdict == "PASS")
	default:
		return true
	}
}

// Enqueue offers an item to the queue without blocking on the network. It
// returns false and reports a dropped status when the bounded queue is full.
func (c *Client) Enqueue(item UploadItem) bool {
	if !c.cfg.Enabled() {
		return false
	}
	if !inScope(c.cfg.Scope, item.Record.Verdict) {
		return false
	}
	select {
	case c.queue <- item:
		c.mu.Lock()
		c.queued++
		c.mu.Unlock()
		return true
	default:
		c.emit(Status{OK: false, Seq: item.Record.Seq, Err: "MES 队列已满，本次记录未上传", Dropped: true, Queued: c.Len()})
		return false
	}
}

// buildBody marshals a payload with the configured field prefix. It is a
// method on Config so tests can verify field mapping without a network.
func (c Config) buildBody(item UploadItem) ([]byte, error) {
	p := Payload{
		Device:      item.Record.DeviceMAC,
		Channel:     fmt.Sprintf("CH%d", item.Record.Ch),
		ChannelName: item.ChannelName,
		Value:       protocol.ToMM(item.Record.ValueUM),
		Unit:        protocol.FriendlyUnit(item.Record.RawUnit),
		Verdict:     item.Record.Verdict,
		Batch:       item.Record.Batch,
		Timestamp:   item.Record.Date + " " + item.Record.T,
		Seq:         item.Record.Seq,
		Source:      item.Record.Src,
	}
	prefix := c.FieldPrefix
	if prefix == "" {
		return json.Marshal(p)
	}
	// Marshal to a generic map to apply the prefix to every key.
	raw, err := json.Marshal(p)
	if err != nil {
		return nil, err
	}
	var m map[string]interface{}
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, err
	}
	prefixed := make(map[string]interface{}, len(m))
	for k, v := range m {
		prefixed[prefix+k] = v
	}
	return json.Marshal(prefixed)
}

// send performs one POST with optional Bearer token.
func (c *Client) send(body []byte) (int, error) {
	req, err := http.NewRequest(http.MethodPost, c.cfg.URL, bytes.NewReader(body))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	if tok := strings.TrimSpace(c.cfg.Token); tok != "" {
		req.Header.Set("Authorization", "Bearer "+tok)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return resp.StatusCode, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return resp.StatusCode, nil
}

// worker drains the queue with bounded exponential-backoff retries.
func (c *Client) worker() {
	defer close(c.done)
	for item := range c.queue {
		c.mu.Lock()
		c.queued--
		c.pending[item.Record.Seq] = true
		c.mu.Unlock()

		body, err := c.cfg.buildBody(item)
		attempts := 0
		if err == nil {
			for a := 0; a <= c.cfg.Retry; a++ {
				attempts = a + 1
				if _, err = c.send(body); err == nil {
					break
				}
				if a < c.cfg.Retry {
					backoff := time.Duration(1<<uint(a)) * 100 * time.Millisecond
					time.Sleep(backoff)
				}
			}
		}
		c.mu.Lock()
		delete(c.pending, item.Record.Seq)
		c.mu.Unlock()

		c.emit(Status{
			OK: err == nil, Seq: item.Record.Seq,
			Err: func() string {
				if err != nil {
					return err.Error()
				}
				return ""
			}(),
			Queued: c.Len(), Attempts: attempts,
		})
	}
}

func (c *Client) emit(s Status) {
	if c.onStat != nil {
		c.onStat(s)
	}
}

// Stop closes the queue and waits for the worker to drain. It is idempotent.
func (c *Client) Stop() {
	c.stopOnce.Do(func() {
		close(c.queue)
		<-c.done
	})
}
