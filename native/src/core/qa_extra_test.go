package core

import (
	"encoding/json"
	"testing"
)

// TestQA_ChannelBoundsEqualMM_FallsBackAcrossAllThreeSites probes the
// UpperMM == LowerMM equality boundary (the engineer covered Upper<Lower only)
// and asserts commit / Stats / chart all fall back to the SAME global bounds.
func TestQA_ChannelBoundsEqualMM_FallsBackAcrossAllThreeSites(t *testing.T) {
	a := NewApp()
	s := a.GetSettings()
	// Global bounds for default settings are [9950, 10050] um.
	// Give CH3 an INVALID per-channel range where UpperMM == LowerMM. The
	// design mandates UpperMM > LowerMM to activate; equality must fall back.
	s.Channels[2] = ChannelConfig{Name: "厚度", UseTol: true, LowerMM: 9.5, UpperMM: 9.5}
	a.SetSettings(s)

	// 10.00mm = 10000um: INSIDE global [9950,10050], but OUTSIDE the (invalid)
	// per-channel [9500,9500]. Correct fallback => 合格. If invalid bounds were
	// wrongly used, this would be 超差.
	a.FeedDemoValue(10.00, 3)

	recs := a.RecordsSnapshot()
	if len(recs) != 1 {
		t.Fatalf("records = %d, want 1", len(recs))
	}
	if recs[0].Verdict != "合格" {
		t.Errorf("commit verdict = %q, want 合格 (equal bounds must fall back)", recs[0].Verdict)
	}

	st := a.Stats()
	if st.N != 1 || st.OK != 1 || st.OKRate != 100 {
		t.Errorf("Stats = %+v, want OK=1 rate=100 (global fallback)", st)
	}

	data := BuildChartData(recs, a.GetSettings(), "")
	if len(data.Points) != 1 {
		t.Fatalf("chart points = %d, want 1", len(data.Points))
	}
	p := data.Points[0]
	if p.Out {
		t.Errorf("chart Out = true, want false (global fallback)")
	}
	if p.LowerMM != 9.95 || p.UpperMM != 10.05 {
		t.Errorf("chart tol lines = %v/%v, want 9.95/10.05", p.LowerMM, p.UpperMM)
	}
}

// TestQA_NormalizeLegacyJSONMissingNewFields exercises the REAL load path:
// json.Unmarshal of an old config that contains ONLY legacy keys, followed by
// Normalize. This is what ui.LoadSettings does; the engineer's test constructed
// a struct literal and never deserialized old JSON.
func TestQA_NormalizeLegacyJSONMissingNewFields(t *testing.T) {
	legacy := `{
		"Unit": "mm", "Digits": 2, "NominalMM": 10.0,
		"LowerTolMM": -0.05, "UpperTolMM": 0.05,
		"DebounceUM": 10, "MaxMS": 300, "MaxCh": 7,
		"AutoEmit": true, "Terminator": "ENTER", "Batch": "OLD-BATCH"
	}`
	var s Settings
	if err := json.Unmarshal([]byte(legacy), &s); err != nil {
		t.Fatalf("unmarshal legacy config: %v", err)
	}
	s.Normalize()

	if s.IdleMinutes != 5 {
		t.Errorf("IdleMinutes = %d, want 5", s.IdleMinutes)
	}
	if s.NamePrefix != "GL-BLE#" {
		t.Errorf("NamePrefix = %q, want GL-BLE#", s.NamePrefix)
	}
	if s.AlertOnBad || s.AutoReconnect || s.IdleDisconnect {
		t.Errorf("new switches must be off, got alert=%v auto=%v idle=%v",
			s.AlertOnBad, s.AutoReconnect, s.IdleDisconnect)
	}
	for i := range s.Channels {
		if s.Channels[i].UseTol {
			t.Errorf("channel %d UseTol=true after legacy load, want false", i+1)
		}
	}
	if lo, hi := s.ChannelBounds(4); lo != 9950 || hi != 10050 {
		t.Errorf("legacy ChannelBounds(4) = %d/%d, want 9950/10050", lo, hi)
	}

	// No false 超差: a nominal reading under a legacy config must pass.
	a := NewApp()
	a.SetSettings(s)
	a.FeedDemoValue(10.00, 4)
	if got := a.RecordsSnapshot()[0].Verdict; got != "合格" {
		t.Errorf("legacy config reading verdict = %q, want 合格 (no false out-of-tol)", got)
	}
	if s.Batch != "OLD-BATCH" {
		t.Errorf("legacy Batch lost: %q", s.Batch)
	}
}
