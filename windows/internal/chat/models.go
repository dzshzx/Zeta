package chat

import (
	"regexp"
	"slices"
	"strconv"
	"strings"
)

// SplitModels 是模型菜单怎么分两层（同手机版 ChatModels.kt 的 modelMenu，用例同 ModelMenuTest）：和 Claude 应用一样只直接列
// 各系列最新的。「默认」和 Claude Code 报的别名（opus、fable、sonnet、haiku 等，总是指向那个系列最新的）照它给的顺序放上层
// latest；钉死版本的完整 ID（claude-opus-4-6）是旧版本，收进 older（菜单里的「更多模型」），按系列（上层的顺序）、版本从新到旧排。
// 没有别名的系列，最新的那个完整 ID 也放上层；和别名指向同一个模型的完整 ID 不重复列。
func SplitModels(models []ModelOption) (latest, older []ModelOption) {
	info := parseModels(models)
	newest := newestUncovered(info)
	var order []string
	var rest []int
	for i, m := range models {
		j, uncovered := newest[info[i].series]
		switch {
		case !info[i].full || uncovered && j == i:
			latest = append(latest, m)
			if s := info[i].series; s != "" && !slices.Contains(order, s) {
				order = append(order, s)
			}
		case !info[i].aliased:
			rest = append(rest, i)
		}
	}
	slices.SortStableFunc(rest, func(a, b int) int {
		if d := slices.Index(order, info[a].series) - slices.Index(order, info[b].series); d != 0 {
			return d
		}
		return compareVersions(info[b].version, info[a].version)
	})
	for _, i := range rest {
		older = append(older, models[i])
	}
	return latest, older
}

// modelInfo 是清单里一项的样子：full 是钉死版本的完整 ID（不是别名）；series 是系列（完整 ID 看它自己，别名看它指向的完整 ID，
// 没报就按别名本身，opus 就是 opus 系列；「默认」没报指向谁时是空）；aliased：有别名指向这个完整 ID。
type modelInfo struct {
	full    bool
	series  string
	version []int
	aliased bool
}

func parseModels(models []ModelOption) []modelInfo {
	info := make([]modelInfo, len(models))
	targets := map[string]bool{}
	for i, m := range models {
		s, v, ok := modelSeries(m.Value)
		info[i] = modelInfo{full: ok, series: s, version: v}
		if ok {
			continue
		}
		if m.ResolvedModel != "" {
			targets[m.ResolvedModel] = true
		}
		if s, _, ok := modelSeries(m.ResolvedModel); ok {
			info[i].series = s
		} else if m.Value != DefaultModel {
			info[i].series = m.Value
		}
	}
	for i, m := range models {
		info[i].aliased = info[i].full && targets[m.Value]
	}
	return info
}

// newestUncovered 是没有别名的各系列里版本最新的那一项：系列 → 下标。
func newestUncovered(info []modelInfo) map[string]int {
	covered := map[string]bool{}
	for _, x := range info {
		if !x.full && x.series != "" {
			covered[x.series] = true
		}
	}
	newest := map[string]int{}
	for i, x := range info {
		if !x.full || covered[x.series] {
			continue
		}
		if j, seen := newest[x.series]; !seen || compareVersions(x.version, info[j].version) > 0 {
			newest[x.series] = i
		}
	}
	return newest
}

var fullModelID = regexp.MustCompile(`^claude-([a-z]+)-(\d+(?:-\d{1,2})*)(?:-\d{8})?(\[1m\])?$`)

// modelSeries 是完整模型 ID 的系列和版本：claude-opus-4-6 是 opus、[4 6]，claude-opus-5-5[1m] 是 opus[1m]、[5 5]；
// 别名（opus、default）和认不出的 ok 为假。
func modelSeries(id string) (series string, version []int, ok bool) {
	m := fullModelID.FindStringSubmatch(id)
	if m == nil {
		return "", nil, false
	}
	for _, part := range strings.Split(m[2], "-") {
		n, _ := strconv.Atoi(part)
		version = append(version, n)
	}
	return m[1] + m[3], version, true
}

// compareVersions 逐段比版本号，前面都一样时段数多的大。
func compareVersions(a, b []int) int {
	for i := range min(len(a), len(b)) {
		if a[i] != b[i] {
			return a[i] - b[i]
		}
	}
	return len(a) - len(b)
}
