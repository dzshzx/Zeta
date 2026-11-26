package ui

import (
	"context"
	"fmt"
	"slices"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/claudecfg"
)

// modelSource 对应一台设备的一个模型来源：那台的连接和账号设置的一个版本（current）。同一来源到期重查时原地换上新清单，
// 换了连接或账号设置就换新对象，旧请求只能写回旧对象。只在主线程上读写，后台读到的经 a.async 交回主线程再写。
type modelSource struct {
	remote   *hub.Remote
	revision uint64
	key      string // 输入栏模型菜单的键
	ready    bool
	pending  bool
	catalog  coreapi.ModelCatalog
	relay    []chat.ModelOption // catalog 里中转报的模型，读到时换好
	harness  *harnessModels
	probe    bool
	readAt   time.Time
	gen      uint64 // 可选的模型每变一次加一，设备页据此判断要不要重新交给配置页
}

// harnessModels 是 Claude Code 报的模型清单和分好的两层（chat.SplitModels）。同一来源定期重查时保留；
// 查到过中转后换新的，之后回到 Claude Code 不沿用以前对话报的。
type harnessModels struct{ list, latest, older []chat.ModelOption }

const (
	modelsTimeout = time.Minute
	modelsTTL     = 5 * time.Minute
	modelsRetry   = 30 * time.Second
)

// current 是 s 还对应 r 现在的连接和账号设置。
func (s *modelSource) current(r *hub.Remote) bool {
	return s != nil && s.remote == r && s.revision == r.Accounts.ModelRevision()
}

func (s *modelSource) reuse(now time.Time, reopen bool) bool {
	if s.pending {
		return true
	}
	if reopen && (!s.ready || s.catalog.Relay != "") {
		return false
	}
	ttl := modelsTTL
	if !s.ready || s.catalog.Relay != "" && len(s.catalog.Models) == 0 {
		ttl = modelsRetry
	}
	return now.Sub(s.readAt) < ttl
}

func (a *App) modelSource(id string, r *hub.Remote, reopen bool) *modelSource {
	if r == nil {
		delete(a.modelLists, id)
		return nil
	}
	s := a.modelLists[id]
	switch {
	case !s.current(r):
		s = &modelSource{remote: r, revision: r.Accounts.ModelRevision(), harness: &harnessModels{}}
		s.key = fmt.Sprintf("model/%s/%p", id, s)
		if a.modelLists == nil {
			a.modelLists = map[string]*modelSource{}
		}
		a.modelLists[id] = s
	case s.reuse(a.now(), reopen):
		return s
	case s.catalog.Relay != "":
		s.harness = &harnessModels{}
	}
	a.readCatalog(s)
	return s
}

// readCatalog 在后台重读 s 的模型来源；读的期间照旧用上次读到的。
func (a *App) readCatalog(s *modelSource) {
	s.pending = true
	core := s.remote.Core
	a.async(func() func() {
		ctx, cancel := context.WithTimeout(context.Background(), modelsTimeout)
		defer cancel()
		catalog, err := core.ModelCatalog(ctx)
		return func() { s.setCatalog(catalog, err, a.now()) }
	})
}

func (s *modelSource) setCatalog(catalog coreapi.ModelCatalog, err error, at time.Time) {
	s.catalog = catalog
	s.ready, s.pending, s.readAt = err == nil || catalog.Relay != "", false, at
	s.relay = nil
	for _, m := range catalog.Models {
		s.relay = append(s.relay, chat.ModelOption{Value: m.ID, DisplayName: m.DisplayName})
	}
	s.gen++
}

// setHarness 换上 Claude Code 报的清单，清单变了才重新分层。
func (s *modelSource) setHarness(list []chat.ModelOption) {
	h := s.harness
	if slices.Equal(h.list, list) {
		return
	}
	h.list = list
	h.latest, h.older = chat.SplitModels(list)
	s.gen++
}

// useConversation 只在这份对话状态与当前模型来源相符时使用 Claude Code 报的清单。
func (s *modelSource) useConversation(p *chatPane) {
	if s == nil {
		return
	}
	if p.harness == nil {
		p.harness = s.harness
	}
	if s.ready && s.catalog.Relay == "" && p.harness == s.harness && len(p.state.Models) > 0 {
		s.setHarness(p.state.Models)
	}
}

func (s *modelSource) available() bool {
	return s != nil && s.ready && s.remote.Accounts.Working() == ""
}

// choices 是可选的模型，分两层：中转只有它报的具体 ID；Claude Code 是它报的清单分好的两层，还没报时是常用的别名。
func (s *modelSource) choices() (latest, older []chat.ModelOption) {
	switch {
	case s == nil || !s.ready:
		return nil, nil
	case s.catalog.Relay != "":
		return s.relay, nil
	case len(s.harness.list) == 0:
		return chat.FallbackModels, nil
	}
	return s.harness.latest, s.harness.older
}

func deviceModelChoices(s *modelSource) []claudecfg.Model {
	latest, older := s.choices()
	var out []claudecfg.Model
	for _, m := range latest {
		if m.Value != chat.DefaultModel {
			out = append(out, claudecfg.Model{Value: m.Value, Label: m.Name(), Resolved: m.ResolvedModel})
		}
	}
	for _, m := range older {
		out = append(out, claudecfg.Model{Value: m.Value, Label: m.Name(), Resolved: m.ResolvedModel, Older: true})
	}
	return out
}

// modelFeed 是设备页上次交给配置页的模型来源；没变就不再交。
type modelFeed struct {
	source                    *modelSource
	gen                       uint64
	available, pending, probe bool
}

func (a *App) syncDeviceModels(x sshlink.Host) {
	if a.cfg == nil {
		return
	}
	s := a.modelSource(x.ID, a.edit.remote, a.edit.models == nil)
	if prev := a.edit.models; prev != nil && prev != s {
		a.cfg.RefreshDefaults()
	}
	a.edit.models = s
	available := s.available()
	if available {
		if p := a.pane; p != nil && p.src.Backend == x.ID {
			s.useConversation(p)
		}
		a.askHarnessModels(x, s)
	}
	a.feedDeviceModels(s, available)
}

// feedDeviceModels 把 s 的模型交给配置页，和上次交的一样时不交。
func (a *App) feedDeviceModels(s *modelSource, available bool) {
	feed := modelFeed{source: s, available: available}
	if s != nil {
		feed.gen, feed.pending, feed.probe = s.gen, s.pending, s.probe
	}
	if a.edit.fed != nil && *a.edit.fed == feed {
		return
	}
	a.edit.fed = &feed
	switch {
	case !available:
		a.cfg.SetModelChoices(nil, claudecfg.ModelSourceUnknown, feed.pending)
	case s.catalog.Relay != "":
		a.cfg.SetModelChoices(deviceModelChoices(s), claudecfg.ModelSourceRelay, s.probe)
	default:
		a.cfg.SetModelChoices(deviceModelChoices(s), claudecfg.ModelSourceHarness, s.probe)
	}
}

func (a *App) askHarnessModels(x sshlink.Host, s *modelSource) {
	if s.catalog.Relay != "" || len(s.harness.list) > 0 || a.edit.probed == s {
		return
	}
	a.edit.probed = s
	if s.probe {
		return
	}
	s.probe = true
	a.async(func() func() {
		ctx, cancel := context.WithTimeout(context.Background(), modelsTimeout)
		defer cancel()
		list, err := s.remote.Models(ctx, x.DefaultCwd)
		return func() {
			s.probe = false
			if err == nil && len(s.harness.list) == 0 {
				s.setHarness(list)
			}
		}
	})
}
