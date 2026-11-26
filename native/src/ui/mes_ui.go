//go:build windows

package ui

import (
	"fmt"
	"strings"

	"github.com/lxn/walk"
	"glcaliper/core"
	"glcaliper/mes"
)

// mesScopeIndex maps a persisted scope string to the ComboBox index
// (0=全部 1=仅合格 2=仅超差). Unknown values fall back to "all".
func mesScopeIndex(scope string) int {
	switch strings.ToLower(scope) {
	case "pass":
		return 1
	case "fail":
		return 2
	default:
		return 0
	}
}

func mesScopeString(idx int) string {
	switch idx {
	case 1:
		return "pass"
	case 2:
		return "fail"
	default:
		return "all"
	}
}

// refreshMESInputs fills MES controls from state (called by refreshSettingsInputs).
func (u *UI) refreshMESInputs(s core.Settings) {
	if u.ckMESEnable != nil {
		u.ckMESEnable.SetChecked(s.MES.Enable)
	}
	if u.edMESURL != nil {
		_ = u.edMESURL.SetText(s.MES.URL)
	}
	if u.edMESToken != nil {
		_ = u.edMESToken.SetText(s.MES.Token)
	}
	if u.edMESPrefix != nil {
		_ = u.edMESPrefix.SetText(s.MES.FieldPrefix)
	}
	if u.edMESTimeout != nil {
		_ = u.edMESTimeout.SetText(fmt.Sprintf("%d", s.MES.TimeoutMS))
	}
	if u.edMESRetry != nil {
		_ = u.edMESRetry.SetText(fmt.Sprintf("%d", s.MES.Retry))
	}
	if u.cmbMESScope != nil {
		u.cmbMESScope.SetCurrentIndex(mesScopeIndex(s.MES.Scope))
	}
	u.refreshMESStatusLabel()
}

// readMESFromControls writes MES controls back into settings. Invalid numeric
// text is recorded in issues instead of being silently replaced.
func (u *UI) readMESFromControls(s *core.Settings, issues *inputIssues) {
	if u.ckMESEnable != nil {
		s.MES.Enable = u.ckMESEnable.Checked()
	}
	if u.edMESURL != nil {
		s.MES.URL = strings.TrimSpace(u.edMESURL.Text())
	}
	if u.edMESToken != nil {
		s.MES.Token = strings.TrimSpace(u.edMESToken.Text())
	}
	if u.edMESPrefix != nil {
		s.MES.FieldPrefix = strings.TrimSpace(u.edMESPrefix.Text())
	}
	if u.edMESTimeout != nil {
		s.MES.TimeoutMS = parseIntChecked(u.edMESTimeout.Text(), s.MES.TimeoutMS, issues, "MES 超时")
	}
	if u.edMESRetry != nil {
		s.MES.Retry = parseIntChecked(u.edMESRetry.Text(), s.MES.Retry, issues, "MES 重试次数")
		// Reflect the effective (clamped) value in Settings so the form echo
		// after saving matches what the upload worker actually uses (P3-1).
		if s.MES.Retry < 0 {
			s.MES.Retry = 0
		}
		if s.MES.Retry > 5 {
			s.MES.Retry = 5
		}
	}
	if u.cmbMESScope != nil {
		s.MES.Scope = mesScopeString(u.cmbMESScope.CurrentIndex())
	}
}

// refreshMESStatusLabel shows whether MES is active and the current queue depth.
func (u *UI) refreshMESStatusLabel() {
	if u.lblMESStatus == nil || u.app == nil {
		return
	}
	if !u.app.MESActive() {
		u.lblMESStatus.SetText("MES：未启用")
		u.lblMESStatus.SetTextColor(colorSub)
		return
	}
	u.lblMESStatus.SetText(fmt.Sprintf("MES：上传中 · 队列 %d", u.app.MESQueueLen()))
	u.lblMESStatus.SetTextColor(colorOKText)
}

// onMESStatus runs on the UI thread (marshalled in Run) for each terminal
// upload outcome. It updates the MES label and the status bar without touching
// the record pipeline.
func (u *UI) onMESStatus(st mes.Status) {
	if st.Dropped {
		if u.lblMESStatus != nil {
			u.lblMESStatus.SetText("MES：队列已满，本次未上传")
			u.lblMESStatus.SetTextColor(colorBadText)
		}
		if u.lblStatus != nil {
			u.lblStatus.SetText("MES 队列已满，本次记录未上传")
		}
		return
	}
	tail := fmt.Sprintf(" · 队列 %d", st.Queued)
	if st.OK {
		if u.lblMESStatus != nil {
			u.lblMESStatus.SetText("MES：" + fmt.Sprintf("已上传 #%d", st.Seq) + tail)
			u.lblMESStatus.SetTextColor(colorOKText)
		}
	} else {
		if u.lblMESStatus != nil {
			u.lblMESStatus.SetText("MES：" + fmt.Sprintf("上传失败 #%d", st.Seq) + tail)
			u.lblMESStatus.SetTextColor(colorBadText)
		}
		if u.lblStatus != nil {
			u.lblStatus.SetText("MES 上传失败：" + st.Err)
		}
	}
}

// Ensure walk is referenced (label type used via AssignTo declarations elsewhere).
var _ = (*walk.Label)(nil)
