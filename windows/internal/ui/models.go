package ui

import (
	"context"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/claudecfg"
)

// modelSource 对应一台设备的一次模型来源。连接或账号设置变动后，旧请求只能写回旧对象。
type modelSource struct {
	remote   *hub.Remote
	revision uint64
	ready    bool
	pending  bool
	catalog  coreapi.ModelCatalog
	harness  *harnessModels
	probe    bool
	readAt   time.Time
}

// 同一来源定期重查时保留 Claude Code 的清单；发现中转、换连接或换账号则不沿用。
type harnessModels struct{ list []chat.ModelOption }

const (
	modelsTimeout = time.Minute
	modelsTTL     = 5 * time.Minute
	modelsRetry   = 30 * time.Second
)

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
	rev := r.Accounts.ModelRevision()
	prev := a.modelLists[id]
	sameDeviceSource := prev != nil && prev.remote == r && prev.revision == rev
	if sameDeviceSource && prev.reuse(a.now(), reopen) {
		return prev
	}
	harness := &harnessModels{}
	if sameDeviceSource && prev.catalog.Relay == "" {
		harness = prev.harness
	}
	s := &modelSource{remote: r, revision: rev, pending: true, harness: harness}
	if a.modelLists == nil {
		a.modelLists = map[string]*modelSource{}
	}
	a.modelLists[id] = s
	a.async(func() func() {
		ctx, cancel := context.WithTimeout(context.Background(), modelsTimeout)
		defer cancel()
		catalog, err := r.Core.ModelCatalog(ctx)
		return func() {
			s.catalog = catalog
			s.ready, s.pending, s.readAt = err == nil || catalog.Relay != "", false, a.now()
		}
	})
	return s
}

// useConversation 只在这份对话状态与当前模型来源相符时使用 Claude Code 报的清单。
func (s *modelSource) useConversation(p *chatPane) {
	if p.modelSource == nil {
		p.modelSource = s
	}
	if s != nil && s.ready && s.catalog.Relay == "" && p.modelSource.harness == s.harness && len(p.state.Models) > 0 {
		s.harness.list = p.state.Models
	}
}

func (s *modelSource) available() bool {
	return s != nil && s.ready && s.remote.Accounts.Working() == ""
}

func (s *modelSource) choices() (latest, older []chat.ModelOption) {
	if s == nil || !s.ready {
		return nil, nil
	}
	if s.catalog.Relay == "" {
		return chat.SplitModels(s.harness.list)
	}
	for _, m := range s.catalog.Models {
		latest = append(latest, chat.ModelOption{Value: m.ID, DisplayName: m.DisplayName})
	}
	return latest, nil
}

func deviceModelChoices(s *modelSource) []claudecfg.Model {
	latest, older := s.choices()
	var out []claudecfg.Model
	for _, m := range latest {
		if m.Value != chat.DefaultModel {
			out = append(out, claudecfg.Model{Value: m.Value, Label: optionName(m)})
		}
	}
	for _, m := range older {
		out = append(out, claudecfg.Model{Value: m.Value, Label: optionName(m), Older: true})
	}
	return out
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
	if !s.available() {
		a.cfg.SetModelChoices(nil, false, s != nil && s.pending, false)
		return
	}
	if p := a.pane; p != nil && p.src.Backend == x.ID {
		s.useConversation(p)
	}
	a.askHarnessModels(x, s)
	a.cfg.SetModelChoices(deviceModelChoices(s), s.catalog.Relay == "", s.probe, true)
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
				s.harness.list = list
			}
		}
	})
}
