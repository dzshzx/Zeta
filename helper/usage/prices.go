package usage

import "strings"

// price 是一个模型每百万 token 的美元数。
type price struct{ input, output, cacheRead float64 }

// prices 是 Anthropic API 的标价，用来给没有费用数据的轮次估个数（会话记录里只有 token；Claude Code 实时报的费用以它报的为准）。
// 键是不带日期、不带窗口标记的模型号。照官方价目表手工维护：出了新模型或调了价要改这张表，表里没有的模型算不出来。
//
// 缓存写入按存多久分两档：5 分钟的是输入价的 1.25 倍，1 小时的是 2 倍；usage 里没分档时按 1 小时算（Claude Code 写的是这一档）。
// 快速模式（usage.speed 为 fast）整体按 2 倍。Haiku 5.5 提示词超过 100K 时的加价不算。
var prices = map[string]price{
	"claude-fable-5-2":  {8.0, 40.0, 0.20},
	"claude-mythos-5-2": {8.0, 40.0, 0.20},
	"claude-fable-5-1":  {10.0, 50.0, 0.25},
	"claude-mythos-5-1": {10.0, 50.0, 0.25},
	"claude-fable-5":    {10.0, 50.0, 1.0},
	"claude-mythos-5":   {10.0, 50.0, 1.0},
	"claude-opus-5-5":   {4.0, 20.0, 0.20},
	"claude-opus-5":     {5.0, 25.0, 0.5},
	"claude-opus-4-8":   {5.0, 25.0, 0.5},
	"claude-opus-4-7":   {5.0, 25.0, 0.5},
	"claude-opus-4-6":   {5.0, 25.0, 0.5},
	"claude-sonnet-5-5": {2.0, 10.0, 0.10},
	"claude-sonnet-5":   {2.0, 10.0, 0.2},
	"claude-sonnet-4-6": {3.0, 15.0, 0.3},
	"claude-haiku-5-5":  {0.1, 0.5, 0.01},
	"claude-haiku-4-5":  {1.0, 5.0, 0.1},
}

// priceOf 查模型号的价格：模型号后面至多带日期（-20261001）或方括号里的窗口标记（[1m]）。
func priceOf(model string) (price, bool) {
	name, _, _ := strings.Cut(model, "[")
	if n := len(name); n > 9 && name[n-9] == '-' && allDigits(name[n-8:]) {
		name = name[:n-9]
	}
	p, ok := prices[name]
	return p, ok
}

func allDigits(s string) bool {
	for _, c := range []byte(s) {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

// estimateCost 是模型 model 的一次请求按标价要多少美元。模型不在表里、usage 是空的时 ok 为 false。
func estimateCost(model string, u *apiUsage) (usd float64, ok bool) {
	p, ok := priceOf(model)
	if !ok {
		return 0, false
	}
	t, ok := u.tokens()
	if !ok {
		return 0, false
	}
	var shortLived int64
	if u.CacheCreation != nil {
		shortLived = orZero(u.CacheCreation.Ephemeral5m)
	}
	write5m := min(max(shortLived, 0), t.CacheWrite)
	write1h := t.CacheWrite - write5m
	standard := float64(t.Input)*p.input +
		float64(t.Output)*p.output +
		float64(t.CacheRead)*p.cacheRead +
		float64(write5m)*p.input*1.25 +
		float64(write1h)*p.input*2
	speed := 1.0
	if u.Speed == "fast" {
		speed = 2
	}
	return standard * speed / 1_000_000, true
}
