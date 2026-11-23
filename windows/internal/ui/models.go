package ui

import (
	"context"
	"log"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/claudecfg"
)

// modelLists 是各台设备上 Claude Code 报过的模型清单，给设置里默认模型的下拉用（同手机版按主机记的 hub.models）：聊天窗格开着
// 那台的对话、对话报了清单时记下它；还没有时打开那台的设备页去要一次（hub.Remote.Models，同手机版没有清单时调 conv.prepare）。
// 只记在内存里，各台的 Claude Code 升级后清单会变。只在主线程上用。
type modelLists struct {
	known  map[string][]chat.ModelOption
	asking map[string]bool
}

// modelsTimeout 是要一次模型清单最多等多久（那边要起一个 Claude Code 进程）。
const modelsTimeout = time.Minute

// models 是设备 id 的模型清单，排成默认模型下拉的样子（chat.SplitModels：旧版本标上 Older，「默认」不列，下拉自己有不设那一项）；
// 还没有时是空的，claudecfg 先列常用的别名。
func (a *App) models(id string) []claudecfg.Model {
	l := &a.modelLists
	if p := a.pane; p != nil && p.src.Backend == id && len(p.state.Models) > 0 {
		if l.known == nil {
			l.known = map[string][]chat.ModelOption{}
		}
		l.known[id] = p.state.Models
	}
	latest, older := chat.SplitModels(l.known[id])
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

// askModels 在设备 x 还没有模型清单时去要一次（r 是它的核心，「连接」关着时为 nil，不要）；要到了记下，设备页还开着那台时换上，
// 没要到时记进运行日志、下拉照旧列常用的别名，下次打开设备页再要。同一台同时只要一次。
func (a *App) askModels(x sshlink.Host, r *hub.Remote) {
	l := &a.modelLists
	if r == nil || len(l.known[x.ID]) > 0 || l.asking[x.ID] {
		return
	}
	if l.asking == nil {
		l.asking = map[string]bool{}
	}
	l.asking[x.ID] = true
	a.async(func() func() {
		ctx, cancel := context.WithTimeout(context.Background(), modelsTimeout)
		defer cancel()
		list, err := r.Models(ctx, x.DefaultCwd)
		return func() {
			delete(l.asking, x.ID)
			if err != nil {
				log.Printf("host: 没要到「%s」上 Claude Code 的模型清单：%v", x.Name(), err)
			} else if len(list) > 0 {
				if l.known == nil {
					l.known = map[string][]chat.ModelOption{}
				}
				l.known[x.ID] = list
			}
			if a.cfg != nil && a.page == pageDevice && a.device == x.ID {
				a.cfg.SetModels(a.models(x.ID))
			}
		}
	})
}
