//go:build windows

package ui

import "testing"

func TestInputIssuesValidation(t *testing.T) {
	var issues inputIssues
	if got := parseFloatChecked(" 10.5 ", 1, &issues, "公称值"); got != 10.5 {
		t.Errorf("valid float = %v, want 10.5", got)
	}
	if got := parseFloatChecked("", 7, &issues, "公称值"); got != 7 {
		t.Errorf("blank float = %v, want default 7", got)
	}
	if got := parseFloatChecked("abc", 7, &issues, "公称值"); got != 7 {
		t.Errorf("invalid float = %v, want default 7", got)
	}
	if got := parseIntChecked("12", 3, &issues, "重试"); got != 12 {
		t.Errorf("valid int = %v, want 12", got)
	}
	if got := parseIntChecked("x", 3, &issues, "重试"); got != 3 {
		t.Errorf("invalid int = %v, want default 3", got)
	}
	if !issues.any() {
		t.Fatal("expected issues for the two invalid inputs")
	}
	if n := len(issues.list); n != 2 {
		t.Errorf("issue count = %d, want 2: %s", n, issues.text())
	}
}

func TestInputIssuesAllValid(t *testing.T) {
	var issues inputIssues
	_ = parseFloatChecked("10", 1, &issues, "a")
	_ = parseIntChecked("", 2, &issues, "b")
	if issues.any() {
		t.Errorf("unexpected issues: %s", issues.text())
	}
}
