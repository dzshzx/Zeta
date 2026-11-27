package mobile

import (
	"cmp"
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/dzshzx/Zeta/hub"
	"github.com/dzshzx/Zeta/hub/chat"
	cfg "github.com/dzshzx/Zeta/hub/claudecfg"
	"github.com/dzshzx/Zeta/hub/locales"
)

// 设置里一台设备的「Claude Code 配置」（同桌面版 windows/internal/ui/claudecfg）。那台主机上的配置（config.*、system.*）
// 界面经 core 直接转给核心；这里只有中枢自己管的：客户端本地存的自动压缩、压缩时机、自动标题（hub/claudecfg），
// 默认模型下拉的清单（同桌面版 models.go 的分法），和要等得比 core 的 60 秒久的几个慢方法。

// claudeLocal 是方法 claudecfg.local 的结果。
type claudeLocal struct {
	AutoCompact     bool   `json:"autoCompact"`
	CompactPercent  int    `json:"compactPercent"` // 0 是 Claude Code 自己的（快满时）
	CompactPercents []int  `json:"compactPercents"`
	AutoTitle       bool   `json:"autoTitle"`
	Cwd             string `json:"cwd"` // 这台的默认工作目录：记忆、MCP、权限页按它找
}

type backendParams struct {
	Backend string `json:"backend"`
}

type setLocalParams struct {
	Backend        string `json:"backend"`
	AutoCompact    *bool  `json:"autoCompact"`
	CompactPercent *int   `json:"compactPercent"`
	AutoTitle      *bool  `json:"autoTitle"`
}

// ModelChoice 是默认模型下拉的一项：Value 写进 settings.json，Label 给人看，ID 是思考强度按它设的完整模型 ID（不知道时为空），
// Older 的收进「更多模型」。
type ModelChoice struct {
	Value string `json:"value"`
	Label string `json:"label"`
	ID    string `json:"id"`
	Older bool   `json:"older"`
}

// modelChoices 是 claudecfg.models 的结果。Source：harness（Claude Code，「默认」是它内置的）、relay（中转报的具体 ID）、
// unknown（读不到、账号正在切换：默认模型不能改）。
type modelChoices struct {
	Source string        `json:"source"`
	Models []ModelChoice `json:"models"`
}

type slowParams struct {
	Backend string         `json:"backend"`
	Method  string         `json:"method"`
	Params  map[string]any `json:"params"`
}

// slowMethods 是要跑 claude 或连 MCP 服务器的方法（docs/core.md 标「慢」的，和 claude update），和各自最多等多久
// （同桌面版配置页的 3 分钟；claude update 核心自己最多 5 分钟）。
var slowMethods = map[string]time.Duration{
	"config.mcp.status":    3 * time.Minute,
	"config.mcp.tools":     3 * time.Minute,
	"config.mcp.addHttp":   3 * time.Minute,
	"config.mcp.addStdio":  3 * time.Minute,
	"config.mcp.remove":    3 * time.Minute,
	"config.mcp.setToken":  3 * time.Minute,
	"system.claude_update": 6 * time.Minute,
}

// modelsTimeout 是要模型清单最多等多久（同桌面版）。
const modelsTimeout = time.Minute

func (c *Core) claudeLocal(backend string) (claudeLocal, error) {
	x, found := c.hub.Host(backend)
	if !found {
		return claudeLocal{}, fmt.Errorf("no such device: %s", backend)
	}
	d := cfg.DeviceSettings(c.hub.Settings().Store(), backend)
	return claudeLocal{AutoCompact: d.AutoCompact(), CompactPercent: d.CompactPercent(), CompactPercents: cfg.CompactPercents,
		AutoTitle: d.AutoTitle(), Cwd: cmp.Or(x.DefaultCwd, x.Home)}, nil
}

func init() {
	reading("claudecfg.local", func(c *Core, p backendParams) (any, error) { return c.claudeLocal(p.Backend) })
	onLoop("claudecfg.setlocal", func(c *Core, p setLocalParams) (any, error) {
		if _, err := c.claudeLocal(p.Backend); err != nil {
			return nil, err
		}
		d := cfg.DeviceSettings(c.hub.Settings().Store(), p.Backend)
		var err error
		if p.AutoCompact != nil {
			err = d.SetAutoCompact(*p.AutoCompact)
		}
		if p.CompactPercent != nil && err == nil {
			err = d.SetCompactPercent(*p.CompactPercent)
		}
		if p.AutoTitle != nil && err == nil {
			err = d.SetAutoTitle(*p.AutoTitle)
		}
		if err != nil {
			return nil, err
		}
		return c.claudeLocal(p.Backend)
	})
	blocking("claudecfg.models", func(c *Core, p backendParams) (any, error) { return c.modelChoices(p.Backend) })
	blocking("claudecfg.slow", func(c *Core, p slowParams) (any, error) {
		timeout, found := slowMethods[p.Method]
		if !found {
			return nil, fmt.Errorf("%w: %s", errUnknown, p.Method)
		}
		return c.forward(p.Backend, p.Method, p.Params, timeout)
	})
}

// remote 是设备 backend 的核心请求和那几样（核心 goroutine 上取），连不上时出错。
func (c *Core) remote(backend string) (*hub.Remote, error) {
	var r *hub.Remote
	var name string
	c.Do(func() {
		name = c.hub.Name(backend)
		r = c.hub.Remote(backend)
	})
	if r == nil {
		return nil, locales.Err(func() string { return locales.HubOffline(name) })
	}
	return r, nil
}

// forward 同 core，只是时限由调用方给。
func (c *Core) forward(backend, method string, params map[string]any, timeout time.Duration) (any, error) {
	r, err := c.remote(backend)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	if params == nil {
		params = map[string]any{}
	}
	raw, err := r.Core.Caller().Call(ctx, method, params)
	if err != nil {
		return nil, err
	}
	if len(raw) == 0 {
		return json.RawMessage("null"), nil
	}
	return raw, nil
}

// modelChoices 照桌面版（models.go 的 choices、deviceModelChoices）：有生效中转时是中转报的具体 ID；没有时是 Claude Code 报的
// 清单分好的两层（聊天页开着这台的对话、报过清单就用它，不然开一个不发消息的对话去要），要不到时是常用的别名。
func (c *Core) modelChoices(backend string) (modelChoices, error) {
	r, err := c.remote(backend)
	if err != nil {
		return modelChoices{}, err
	}
	unknown := modelChoices{Source: "unknown", Models: []ModelChoice{}}
	ctx, cancel := context.WithTimeout(context.Background(), modelsTimeout)
	defer cancel()
	catalog, err := r.Core.ModelCatalog(ctx)
	if err != nil && catalog.Relay == "" {
		return unknown, nil
	}
	var working bool
	var reported []chat.ModelOption
	var cwd string
	c.Do(func() {
		working = r.Accounts.Working() != ""
		if ch := c.hub.Chat(); ch != nil && ch.Backend == backend && ch.Conv != nil {
			reported = ch.Conv.State().Models
		}
		if x, ok := c.hub.Host(backend); ok {
			cwd = cmp.Or(x.DefaultCwd, x.Home)
		}
	})
	if working {
		return unknown, nil
	}
	if catalog.Relay != "" {
		out := modelChoices{Source: "relay", Models: []ModelChoice{}}
		for _, m := range catalog.Models {
			o := chat.ModelOption{Value: m.ID, DisplayName: m.DisplayName, ResolvedModel: m.ID}
			out.Models = append(out.Models, ModelChoice{Value: o.Value, Label: o.Name(), ID: o.FullID()})
		}
		return out, nil
	}
	if len(reported) == 0 {
		if list, err := r.Models(ctx, cwd); err == nil {
			reported = list
		}
	}
	latest, older := chat.FallbackModels, []chat.ModelOption(nil)
	if len(reported) > 0 {
		latest, older = chat.SplitModels(reported)
	}
	return modelChoices{Source: "harness", Models: harnessChoices(latest, older)}, nil
}

func harnessChoices(latest, older []chat.ModelOption) []ModelChoice {
	out := []ModelChoice{}
	add := func(m chat.ModelOption, old bool) {
		out = append(out, ModelChoice{Value: m.Value, Label: m.Name(), ID: m.FullID(), Older: old})
	}
	for _, m := range latest {
		if m.Value != chat.DefaultModel {
			add(m, false)
		}
	}
	for _, m := range older {
		add(m, true)
	}
	return out
}
