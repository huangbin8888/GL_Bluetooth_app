//go:build windows

package ui

import (
	"fmt"
	"strings"
	"time"

	"github.com/lxn/walk"
	"glcaliper/core"
	"glcaliper/protocol"
)

// afterCreate runs once the window controls exist.
func (u *UI) afterCreate() {
	// Keep the whole window (incl. title-bar buttons) on screen on small/hi-DPI
	// displays. The subclass caps walk's layout-driven min/max track size to
	// the work area, then we center + clamp the initial rectangle.
	if u.main != nil {
		installWindowFit(u.main.Handle())
		fitWindowToWorkArea(u.main.Handle())
	}

	// 表格样式器：判定色块 / 来源分色 / 等宽数值
	if u.tableView != nil && u.model != nil {
		u.tableView.SetCellStyler(u.model)
	}

	// Cache the internal owner-data ListView HWNDs and re-assert double
	// buffering. repaintTable() then redraws only visible rows via
	// LVM_REDRAWITEMS instead of a whole-client WM_ERASEBKGND (flicker fix).
	u.initTablePainting()

	// Per-column header fonts: small headers except the "实测值" column.
	u.initHeaderPainting()

	// Channel selectors must show the names from the persisted settings, not
	// the hardcoded CHn tokens; index -> internal "CHn" key stays unchanged.
	u.refreshChannelComboBoxes()

	// Authoritative channel filter: the dropdown's real index is snapshotted
	// into the model's stable cache at explicit sync points (applyFilter and
	// the watcher). Rendering then reads one consistent cached value.
	if u.model != nil {
		u.model.SetFilterProvider(u.liveFilter)
	}
	// Safety net: if the index changes without an event, sync + repaint.
	u.startFilterWatch()

	// wire combo boxes initial index
	if u.cmbChannel != nil && u.cmbChannel.CurrentIndex() < 0 {
		u.cmbChannel.SetCurrentIndex(u.app.Channel() - 1)
	}
	s := u.app.GetSettings()
	if u.cmbUnit != nil {
		idx := 0
		if s.Unit == "inch" {
			idx = 1
		}
		u.cmbUnit.SetCurrentIndex(idx)
	}
	if u.cmbTerm != nil {
		idx := 0
		if s.Terminator == "TAB" {
			idx = 1
		}
		u.cmbTerm.SetCurrentIndex(idx)
	}

	// HID terminator sync
	if u.bridge != nil {
		u.bridge.HIDSetTerminator(s.Terminator)
	}

	// Responsive table sizing: poll the ListView width and re-fit columns +
	// fonts on change (maximize/restore/window resize). Poll-based to read the
	// settled client width that walk computes asynchronously.
	u.startTableAdaptWatch()

	// Maximize/resize flicker fix, scoped to the live table HWNDs only.
	// Applying WS_CLIPCHILDREN to the whole tree broke the settings and
	// connection pages (walk repaints widget backgrounds by inheriting an
	// ancestor brush; containers that move/hide children need that repaint),
	// so only the table and its static ListViews get the style now.
	if u.tableView != nil {
		applyNoFlickerStyles(u.tableView.Handle())
	}
}

// refreshChannelComboBoxes rebuilds every channel selector's display model
// from the current settings while preserving its selected index. The index is
// the internal channel id (0 = all channels for scope selectors), so renaming
// a channel never breaks the "CHn" keys used by filtering and export.
func (u *UI) refreshChannelComboBoxes() {
	s := u.app.GetSettings()
	channelModel := channelDisplayNames(s)
	scopeModel := filterDisplayNames(s)
	setChannelComboModel(u.cmbChannel, channelModel, u.app.Channel()-1)
	setChannelComboModel(u.cmbFilter, scopeModel, 0)
	setChannelComboModel(u.cmbChartFilter, scopeModel, 0)
	setChannelComboModel(u.exportScope, scopeModel, 0)
}

// setChannelComboModel swaps a combobox model without losing the selection.
// walk resets the current index inside SetModel, and may publish an index
// change while doing so; restoring the index (clamped) re-publishes the real
// selection so the existing filter handlers see the correct value again.
func setChannelComboModel(cmb *walk.ComboBox, model []string, defaultIndex int) {
	if cmb == nil || len(model) == 0 {
		return
	}
	idx := cmb.CurrentIndex()
	if idx < 0 {
		idx = defaultIndex
	}
	if idx < 0 {
		idx = 0
	}
	if idx >= len(model) {
		idx = len(model) - 1
	}
	cmb.SetModel(model)
	cmb.SetCurrentIndex(idx)
}

// refreshMode shows the detected link mode & OS environment.
func (u *UI) refreshMode() {
	bleOK := false
	if u.bridge != nil {
		bleOK = u.bridge.BLEAvailable()
	}
	win7 := isWin7()
	var modeText, envText, detail string
	switch {
	case win7 && !bleOK:
		modeText = "链路：HID 键盘捕获"
		envText = "Windows 7"
		detail = "检测到 Windows 7：系统无 BLE LE 接口。\n请使用 HID 键盘捕获模式（可真实测量），\n将卡尺蓝牙配对为键盘后到“实时检测”页开启捕获。\n演示模式可用于无硬件预览。"
	case bleOK:
		modeText = "链路：BLE 直连 + HID 双模"
		envText = "Windows " + osVersionText()
		detail = "检测到 Windows 10+ 且蓝牙可用。\n可用 BLE 直连扫描 GL-BLE# 设备，\n也可用 HID 全局捕获任意键盘卡尺。\n两者数据进入同一处理链路。"
	default:
		modeText = "链路：HID / 演示"
		envText = "Windows " + osVersionText()
		detail = "未检测到可用 BLE。\n可使用 HID 键盘捕获（推荐）或演示模式。"
	}
	if u.lblMode != nil {
		u.lblMode.SetText(modeText)
	}
	if u.lblEnv != nil {
		u.lblEnv.SetText(envText)
	}
	if u.lblEnvDetail != nil {
		u.lblEnvDetail.SetText(detail)
	}
}

// refreshSettingsInputs fills settings fields from state.
func (u *UI) refreshSettingsInputs() {
	s := u.app.GetSettings()
	if u.edNominal != nil {
		u.edNominal.SetText(fmt.Sprintf("%.4f", s.NominalMM))
		u.edLower.SetText(fmt.Sprintf("%.4f", s.LowerTolMM))
		u.edUpper.SetText(fmt.Sprintf("%.4f", s.UpperTolMM))
		u.edDigits.SetText(fmt.Sprintf("%d", s.Digits))
		u.edDebounce.SetText(fmt.Sprintf("%d", s.DebounceUM))
		u.edBatch.SetText(s.Batch)
	}
	for i := 0; i < protocol.MaxChannels; i++ {
		if u.edChName[i] != nil {
			_ = u.edChName[i].SetText(s.Channels[i].Name)
		}
		if u.edChBleName[i] != nil {
			_ = u.edChBleName[i].SetText(s.Channels[i].BleName)
		}
		if u.ckChUseTol[i] != nil {
			u.ckChUseTol[i].SetChecked(s.Channels[i].UseTol)
		}
		if u.edChLower[i] != nil {
			_ = u.edChLower[i].SetText(fmt.Sprintf("%.4f", s.Channels[i].LowerMM))
		}
		if u.edChUpper[i] != nil {
			_ = u.edChUpper[i].SetText(fmt.Sprintf("%.4f", s.Channels[i].UpperMM))
		}
	}
	if u.ckAlertOnBad != nil {
		u.ckAlertOnBad.SetChecked(s.AlertOnBad)
	}
	if u.ckAutoReconnect != nil {
		u.ckAutoReconnect.SetChecked(s.AutoReconnect)
	}
	if u.ckIdleDisconnect != nil {
		u.ckIdleDisconnect.SetChecked(s.IdleDisconnect)
	}
	if u.edIdleMinutes != nil {
		_ = u.edIdleMinutes.SetText(fmt.Sprintf("%d", s.IdleMinutes))
	}
	if u.edNamePrefix != nil {
		_ = u.edNamePrefix.SetText(s.NamePrefix)
	}
	u.refreshHIDBindingLabels()
	u.refreshMESInputs(s)
}

// refreshHIDBindingLabels shows, for every channel, the bound HID device
// fingerprint (shortened for display) so the operator can see which caliper
// is filed to which channel without opening a dialog.
func (u *UI) refreshHIDBindingLabels() {
	s := u.app.GetSettings()
	for i := 0; i < protocol.MaxChannels; i++ {
		lbl := u.lblChHidFp[i]
		if lbl == nil {
			continue
		}
		fp := strings.TrimSpace(s.Channels[i].HidFP)
		if fp == "" {
			lbl.SetText("—")
			continue
		}
		short := fp
		if len(short) > 26 {
			short = "…" + short[len(short)-25:]
		}
		lbl.SetText(short)
	}
}

// syncBridgeFromSettings pushes terminator + scan-name prefix into hardware.
func (u *UI) syncBridgeFromSettings(s core.Settings) {
	if u.bridge == nil {
		return
	}
	u.bridge.HIDSetTerminator(s.Terminator)
	u.bridge.SetNamePrefix(s.NamePrefix)
}

// onSaveSettings reads fields back into state. Non-blank invalid numbers and
// logically invalid combos are reported to the user and the save is aborted,
// so nothing is silently replaced while claiming success (P2-2/P2-3/P3-5).
func (u *UI) onSaveSettings() {
	var issues inputIssues
	s := u.app.GetSettings()
	s.NominalMM = parseFloatChecked(u.edNominal.Text(), s.NominalMM, &issues, "公称值")
	s.LowerTolMM = parseFloatChecked(u.edLower.Text(), s.LowerTolMM, &issues, "下偏差")
	s.UpperTolMM = parseFloatChecked(u.edUpper.Text(), s.UpperTolMM, &issues, "上偏差")
	s.Digits = parseIntChecked(u.edDigits.Text(), s.Digits, &issues, "小数位")
	if s.Digits < 0 {
		s.Digits = 0
	}
	if s.Digits > 6 {
		s.Digits = 6
	}
	s.DebounceUM = int64(parseIntChecked(u.edDebounce.Text(), int(s.DebounceUM), &issues, "去抖阈值"))
	if u.cmbUnit != nil {
		s.Unit = "mm"
		if u.cmbUnit.CurrentIndex() == 1 {
			s.Unit = "inch"
		}
	}
	if u.cmbTerm != nil {
		s.Terminator = "ENTER"
		if u.cmbTerm.CurrentIndex() == 1 {
			s.Terminator = "TAB"
		}
	}
	if u.edBatch != nil && u.edBatch.Text() != "" {
		s.Batch = u.edBatch.Text()
	}
	seenNames := make(map[string]int)
	for i := 0; i < protocol.MaxChannels; i++ {
		if u.edChName[i] != nil {
			s.Channels[i].Name = strings.TrimSpace(u.edChName[i].Text())
			if s.Channels[i].Name != "" {
				if prev, dup := seenNames[s.Channels[i].Name]; dup {
					issues.add("CH%d 与 CH%d 通道名称重复：%s（导出再导入会混淆，请用不同名称）", prev, i+1, s.Channels[i].Name)
				} else {
					seenNames[s.Channels[i].Name] = i + 1
				}
			}
		}
		if u.edChBleName[i] != nil {
			s.Channels[i].BleName = u.edChBleName[i].Text()
		}
		if u.ckChUseTol[i] != nil {
			s.Channels[i].UseTol = u.ckChUseTol[i].Checked()
		}
		if u.edChLower[i] != nil {
			s.Channels[i].LowerMM = parseFloatChecked(u.edChLower[i].Text(), s.Channels[i].LowerMM, &issues, fmt.Sprintf("CH%d 下限", i+1))
		}
		if u.edChUpper[i] != nil {
			s.Channels[i].UpperMM = parseFloatChecked(u.edChUpper[i].Text(), s.Channels[i].UpperMM, &issues, fmt.Sprintf("CH%d 上限", i+1))
		}
		if s.Channels[i].UseTol && s.Channels[i].UpperMM <= s.Channels[i].LowerMM {
			issues.add("CH%d 已启用独立公差，但上限 (%.4f) 未大于下限 (%.4f)，会静默退回全局公差；请修正或关闭该通道独立公差", i+1, s.Channels[i].UpperMM, s.Channels[i].LowerMM)
		}
	}
	if u.ckAlertOnBad != nil {
		s.AlertOnBad = u.ckAlertOnBad.Checked()
	}
	if u.ckAutoReconnect != nil {
		s.AutoReconnect = u.ckAutoReconnect.Checked()
	}
	if u.ckIdleDisconnect != nil {
		s.IdleDisconnect = u.ckIdleDisconnect.Checked()
	}
	if u.edIdleMinutes != nil {
		s.IdleMinutes = parseIntChecked(u.edIdleMinutes.Text(), s.IdleMinutes, &issues, "空闲分钟数")
	}
	if u.edNamePrefix != nil && u.edNamePrefix.Text() != "" {
		s.NamePrefix = u.edNamePrefix.Text()
	}
	u.readMESFromControls(&s, &issues)
	if s.MES.Enable && strings.TrimSpace(s.MES.URL) == "" {
		issues.add("已启用 MES 上传但未填写接口 URL，实际不会启用；请填写 URL 或关闭 MES")
	}
	if issues.any() {
		errBox(u.main, "设置未保存", "请修正以下问题后再保存：\n\n"+issues.text())
		return
	}
	s.Normalize()
	u.app.SetSettings(s)
	u.refreshChannelComboBoxes() // 立即让所有下拉跟随新通道名
	u.syncBridgeFromSettings(s)
	u.app.ConfigureMES(s) // 应用 MES 配置（开关/URL/重试等变更即时生效）
	u.model.SetDisplay(s.Digits, s.Unit, currentFilter(u), false)
	u.model.PublishRowsReset()
	u.repaintTable() // refresh owner-data cells (digits/unit/filter may have changed)
	u.saveSettingsFile(s)
	u.refreshSettingsInputs() // 回显钳制/兜底后的真实生效值（P2-1/P3-1/P3-2/P3-3）
	u.refreshMESStatusLabel() // MES 开关变更后立即刷新状态标签（P3-2）
	u.refreshReading()
	u.refreshCountStats()
	info(u.main, "设置", "设置已保存并生效。")
}

func (u *UI) onResetSettings() {
	s := core.DefaultSettings()
	u.app.SetSettings(s)
	u.refreshChannelComboBoxes() // 恢复默认后同样刷新所有通道下拉
	u.refreshSettingsInputs()
	if u.cmbUnit != nil {
		u.cmbUnit.SetCurrentIndex(0)
	}
	if u.cmbTerm != nil {
		u.cmbTerm.SetCurrentIndex(0)
	}
	u.syncBridgeFromSettings(s)
	u.app.ConfigureMES(s) // 恢复默认会关闭 MES
	u.model.SetDisplay(s.Digits, s.Unit, "", false)
	u.model.PublishRowsReset()
	u.repaintTable()
	u.saveSettingsFile(s)
	u.refreshReading()
	u.refreshCountStats()
	u.setStatus("已恢复默认设置")
}

// liveFilter returns the channel filter currently shown by the filter
// ComboBox. CurrentIndex() issues CB_GETCURSEL against the real control HWND,
// so it reflects the selector's actual display state regardless of whether a
// selection event reached the handler or was later cleared in the cache.
// This is the authoritative source installed into the model as its provider.
func (u *UI) liveFilter() string {
	if u.cmbFilter == nil {
		return ""
	}
	return filterKeyFromIndex(u.cmbFilter.CurrentIndex())
}

// applyFilter is the single point where a channel-filter change becomes
// visible. Two things must happen together:
//  1. Snapshot the live dropdown index into the model's STABLE filter cache.
//     Rendering (RowCount + Value) then reads one consistent value for the
//     whole paint; a transient CB_GETCURSEL wobble can no longer produce a
//     correct row count with mixed channel cells.
//  2. Force the owner-data TableView to REPAINT. walk's RowsReset only calls
//     LVM_SETITEMCOUNT with LVSICF_NOINVALIDATEALL, which updates the row
//     count but leaves previously painted cells untouched. Without this
//     Invalidate the screen keeps showing the old (unfiltered) channel names
//     even though the model data is already correct — exactly the "筛选不准"
//     symptom: the dropdown shows CH2 but the table still mixes all channels.
func (u *UI) applyFilter() {
	if u.model == nil {
		return
	}
	idx := -1
	if u.cmbFilter != nil {
		idx = u.cmbFilter.CurrentIndex()
	}
	if idx < 0 {
		// Transient "no selection" during a selector rebuild: keep the
		// current filter instead of silently clearing it.
		return
	}
	s := u.app.GetSettings()
	key := filterKeyFromIndex(idx)
	u.model.SetDisplay(s.Digits, s.Unit, key, false)
	u.model.PublishRowsReset()
	u.repaintTable()
}

// startFilterWatch is the repaint half of the fix. Reading the live index in
// rows() keeps the DATA correct, but if a selection change does not reach the
// event handler, PublishRowsReset is never called and the TableView keeps
// painting its cached rows. A lightweight watcher polls the control's real
// index on the UI thread and republishes rows whenever the index actually
// changes. The existing onFilterChanged handler stays as the immediate path;
// together they make "what the dropdown shows is what the table filters"
// hold for either failure path (event never fired, or fired then the cached
// filter was cleared).
func (u *UI) startFilterWatch() {
	go func() {
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()
		last := -2 // sentinel: no index observed yet; forces one initial sync
		for range ticker.C {
			if u.main == nil {
				return
			}
			// last is only read/written inside this closure, which walk runs
			// sequentially on the UI thread - no additional locking needed.
			u.main.Synchronize(func() {
				if u.cmbFilter == nil || u.model == nil {
					return
				}
				idx := u.cmbFilter.CurrentIndex()
				// A transient -1 (emitted during a selector model rebuild) is
				// not a user selection; ignore it so a channel rename / save
				// never clears a live filter.
				if idx < 0 || idx == last {
					return
				}
				last = idx
				// Snapshot the confirmed index into the stable model cache and
				// force a full owner-data repaint. PublishRowsReset alone only
				// changes the row count and leaves stale channel cells on screen.
				u.applyFilter()
			})
		}
	}()
}

func currentFilter(u *UI) string {
	// Prefer the control's live index: it is the same source the table uses
	// (via u.liveFilter installed as the model provider), so settings save /
	// channel rename cannot leave the two out of sync. Fall back to the model
	// only before the ComboBox exists (early startup / headless tests).
	if u.cmbFilter != nil {
		return u.liveFilter()
	}
	if u.model != nil {
		return u.model.Filter()
	}
	return ""
}

// refreshAnalysis updates the analysis page labels.
func (u *UI) refreshAnalysis() {
	if u.anTotal == nil {
		return
	}
	st := u.app.Stats()
	u.anTotal.SetText(fmt.Sprintf("%d", st.N))
	u.anOK.SetText(fmt.Sprintf("%d", st.OK))
	u.anRate.SetText(fmt.Sprintf("%d%%", st.OKRate))
	if st.N > 0 {
		u.anMean.SetText(u.fmtMM(int64(st.MeanUM)))
		u.anMax.SetText(u.fmtMM(st.MaxUM))
		u.anMin.SetText(u.fmtMM(st.MinUM))
		u.anSD.SetText(u.fmtMM(int64(st.SDUM)))
	} else {
		u.anMean.SetText("—")
		u.anMax.SetText("—")
		u.anMin.SetText("—")
		u.anSD.SetText("—")
	}
	// Analysis page owns the chart; keep it in sync with the same snapshot.
	u.refreshChart()
}

var _ = walk.Key(0)

// onListHIDDevices is the self-check entry point: it lists keyboard-class HID
// devices Raw Input can see, together with the current channel bindings. This
// is how the operator confirms the fingerprints before relying on auto-filing.
func (u *UI) onListHIDDevices() {
	if u.bridge == nil {
		info(u.main, "HID 设备自检", "硬件桥接未就绪")
		return
	}
	devs := u.bridge.HIDListDevices()
	// Index devices by fingerprint so each listed device can show its bound
	// channel — that is how the operator tells a caliper from a laptop keyboard.
	bound := make(map[string]int)
	for _, bd := range u.app.HIDBindings() {
		bound[bd.Fingerprint] = bd.Channel
	}
	var b strings.Builder
	b.WriteString("Raw Input 检测到的键盘类 HID 设备：\n")
	if len(devs) == 0 {
		b.WriteString("  （无）\n")
		b.WriteString("请确认卡尺已作为键盘设备连接；若开启捕获后仍为空，\n")
		b.WriteString("说明 Raw Input 不可用，程序会回退到普通键盘钩子（无法自动分通道）。\n")
	} else {
		for i, d := range devs {
			mark := ""
			if ch := bound[d.Fingerprint]; ch > 0 {
				mark = fmt.Sprintf("  ← 已绑定 CH%d", ch)
			} else {
				mark = "  （未绑定）"
			}
			b.WriteString(fmt.Sprintf("  %d. %s%s\n", i+1, d.Fingerprint, mark))
		}
	}
	b.WriteString("\n提示：未绑定的卡尺，先在“实时检测”页选中目标通道测一次即完成绑定。\n")
	b.WriteString("绑定后此处会标注 CHn，此后该卡尺读数自动归档到该通道。\n")
	bindings := u.app.HIDBindings()
	b.WriteString("\n已学习的绑定（设备指纹 → 通道）：\n")
	if len(bindings) == 0 {
		b.WriteString("  （无，尚未学习。选中通道后测一次即绑定）\n")
	} else {
		for _, bd := range bindings {
			b.WriteString(fmt.Sprintf("  CH%d ← %s\n", bd.Channel, bd.Fingerprint))
		}
	}
	info(u.main, "HID 设备自检", b.String())
}

// onClearHIDBindings removes all learned device-to-channel bindings.
func (u *UI) onClearHIDBindings() {
	n := len(u.app.HIDBindings())
	if n == 0 {
		info(u.main, "清除绑定", "当前没有 HID 绑定")
		return
	}
	if !confirm(u.main, "清除绑定", fmt.Sprintf("确定清除全部 %d 条 HID 设备绑定吗？\n清除后需重新选中通道测量一次来学习。", n)) {
		return
	}
	for i := 0; i < protocol.MaxChannels; i++ {
		u.app.UnbindHIDChannel(i + 1)
	}
	u.SaveSettingsNow()
	u.refreshHIDBindingLabels()
	u.setStatus("已清除所有 HID 设备绑定")
}
