// Package ui: pure helpers that derive selector display text from settings.
// The CHn prefix is retained so a ComboBox index still maps directly to the
// internal "CHn" filter key used by core.BuildChartData / ExportCsv.
package ui

import (
	"strconv"
	"strings"

	"glcaliper/core"
)

// channelDisplayName returns the text shown for one channel in every selector.
// A configured custom name follows the CHn token; if the name is empty (or is
// already the token itself) only CHn is shown, avoiding "CH1 CH1".
func channelDisplayName(s core.Settings, ch int) string {
	token := "CH" + strconv.Itoa(ch)
	name := strings.TrimSpace(s.ChannelName(ch))
	if name == "" || name == token {
		return token
	}
	return token + " " + name
}

// channelDisplayNames returns the display model for the live channel combobox.
func channelDisplayNames(s core.Settings) []string {
	out := make([]string, len(s.Channels))
	for i := range out {
		out[i] = channelDisplayName(s, i+1)
	}
	return out
}

// filterDisplayNames returns the display model shared by all scope
// selectors. Index 0 is the "all channels" sentinel and index i maps to CHi
// internally; the display text may be a user-defined channel name.
func filterDisplayNames(s core.Settings) []string {
	out := make([]string, 0, len(s.Channels)+1)
	out = append(out, "全部通道")
	out = append(out, channelDisplayNames(s)...)
	return out
}

// filterKeyFromIndex maps a scope-selector ComboBox index to the internal
// channel filter key. Index 0 is the "全部通道" sentinel (no filter); index i
// (i >= 1) maps to the CHi key. A negative index is the transient
// "no selection" state walk reports while rebuilding a selector model; it is
// mapped to "no filter" here, while callers that must preserve a live filter
// across a rebuild ignore -1 explicitly. Pure and display-name independent
// by design.
func filterKeyFromIndex(idx int) string {
	if idx <= 0 {
		return ""
	}
	return "CH" + strconv.Itoa(idx)
}
