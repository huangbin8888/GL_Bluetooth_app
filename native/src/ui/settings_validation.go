//go:build windows

package ui

import (
	"fmt"
	"strconv"
	"strings"
)

// inputIssues collects problems found while reading the settings form. The
// caller surfaces them to the user and refuses to save, so an invalid value
// can no longer be silently replaced while reporting "已保存并生效".
type inputIssues struct {
	list []string
}

func (v *inputIssues) add(format string, a ...interface{}) {
	v.list = append(v.list, fmt.Sprintf(format, a...))
}

func (v *inputIssues) any() bool { return len(v.list) > 0 }

func (v *inputIssues) text() string { return strings.Join(v.list, "\n") }

// parseFloatChecked / parseIntChecked treat a blank field as "keep the
// current value" (documented behaviour) but flag non-blank text that cannot
// be parsed, so the user is told instead of being misled.
func parseFloatChecked(s string, def float64, issues *inputIssues, name string) float64 {
	t := strings.TrimSpace(s)
	if t == "" {
		return def
	}
	x, err := strconv.ParseFloat(t, 64)
	if err != nil {
		issues.add("%s：%q 不是有效数字", name, s)
		return def
	}
	return x
}

func parseIntChecked(s string, def int, issues *inputIssues, name string) int {
	t := strings.TrimSpace(s)
	if t == "" {
		return def
	}
	x, err := strconv.Atoi(t)
	if err != nil {
		issues.add("%s：%q 不是有效整数", name, s)
		return def
	}
	return x
}
