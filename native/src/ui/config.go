//go:build windows

package ui

import (
	"encoding/json"
	"os"
	"path/filepath"

	"glcaliper/core"
)

func settingsPath() string {
	exe, err := os.Executable()
	if err != nil {
		return "glcaliper-settings.json"
	}
	return filepath.Join(filepath.Dir(exe), "glcaliper-settings.json")
}

func (u *UI) saveSettingsFile(s core.Settings) {
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return
	}
	_ = os.WriteFile(settingsPath(), data, 0644)
}

// SaveSettingsNow persists the current in-memory settings. Used by the
// hardware bridge after a successful BLE connection to remember the address.
func (u *UI) SaveSettingsNow() {
	if u == nil || u.app == nil {
		return
	}
	u.saveSettingsFile(u.app.GetSettings())
}

// LoadSettings returns saved settings merged over defaults.
func LoadSettings() core.Settings {
	s := core.DefaultSettings()
	s.Normalize()
	data, err := os.ReadFile(settingsPath())
	if err != nil {
		return s
	}
	var saved core.Settings
	if err := json.Unmarshal(data, &saved); err != nil {
		return s
	}
	// All sanity merges (legacy + new fields) are centralised in core.
	saved.Normalize()
	return saved
}
