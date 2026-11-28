package mobile

import (
	"cmp"
	"context"
	"fmt"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/hub/chat"
	cfg "github.com/dzshzx/Zeta/hub/claudecfg"
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
	Harness string `json:"harness"`
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
	blocking("claudecfg.models", func(c *Core, p backendParams) (any, error) { return c.modelChoices(p.Backend, p.Harness) })
}

// modelChoices 照桌面版（models.go 的 choices、deviceModelChoices）：有生效中转时是中转报的具体 ID；没有时是对应 harness 报的
// 清单分好的两层（聊天页开着这台的同 harness 对话、报过清单就用它，不然开一个不发消息的对话去要），要不到时是后备清单。
func (c *Core) modelChoices(backend, harness string) (modelChoices, error) {
	h := sessionproto.NormalizeHarness(harness)
	r, err := c.remote(backend)
	if err != nil {
		return modelChoices{}, err
	}
	unknown := modelChoices{Source: "unknown", Models: []ModelChoice{}}
	ctx, cancel := context.WithTimeout(context.Background(), modelsTimeout)
	defer cancel()
	catalog, err := r.CoreFor(h).ModelCatalog(ctx)
	if err != nil && catalog.Relay == "" {
		return unknown, nil
	}
	var working bool
	var reported []chat.ModelOption
	var cwd string
	c.Do(func() {
		working = r.AccountsFor(h).Working() != ""
		if ch := c.hub.Chat(); ch != nil && ch.Backend == backend && ch.Conv != nil {
			st := ch.Conv.State()
			if sessionproto.NormalizeHarness(st.Harness) == h {
				reported = st.Models
			}
		}
		if x, ok := c.hub.Host(backend); ok {
			cwd = cmp.Or(x.DefaultCwd, x.Home)
		}
	})
	if working {
		return unknown, nil
	}
	if catalog.Relay != "" {
		return relayChoices(catalog, h), nil
	}
	if h == sessionproto.HarnessAgy && len(reported) == 0 && len(catalog.Models) > 0 {
		for _, m := range catalog.Models {
			reported = append(reported, chat.ModelOption{Value: m.ID, DisplayName: m.DisplayName, ResolvedModel: m.ID})
		}
	}
	if len(reported) == 0 {
		if list, err := r.ModelsFor(ctx, cwd, h); err == nil {
			reported = list
		}
	}
	latest, older := modelOptions(catalog, reported, h)
	return modelChoices{Source: "harness", Models: harnessChoices(latest, older)}, nil
}

func relayChoices(catalog coreapi.ModelCatalog, h string) modelChoices {
	out := modelChoices{Source: "relay", Models: []ModelChoice{}}
	latest, _ := modelOptions(catalog, nil, h)
	for _, o := range latest {
		out.Models = append(out.Models, ModelChoice{Value: o.Value, Label: o.Name(), ID: o.FullID()})
	}
	return out
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
