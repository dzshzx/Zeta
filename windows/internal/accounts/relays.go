package accounts

import (
	"context"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// 中转：claude-switch 的非账号目标，Claude Code 经它用 API 密钥访问模型（docs/core.md「账号与额度」）。列表随 accounts.list
// 一起来（AccountList.Relays）；换过去同换号（SwitchAccount），添加、修改、删除和拉模型列表是 accounts.relay_*。
// 地址、名字、密钥的规矩都由核心核对、说原因，这里只管草稿里哪些必须先填。密钥只在草稿里、只交给核心，不进日志和出错。

// RelayDraft 是添加或编辑一个中转时页面上的草稿（同 Eta 的 ProviderConfigDraft：先改草稿，保存时一次交出去），和向中转
// 要来的模型列表。字段由界面直接绑到输入框；方法和读状态都在界面线程上。
type RelayDraft struct {
	// Create 为真是添加，否则是编辑 Name 那个（名字不能改）。
	Create               bool
	Name, BaseURL, Model string
	// Key 是新填的密钥：编辑时空着是不改。KeyEditable 为假（用户自己放的、密钥不是从文件读的中转）时不交。
	Key string
	// HasKey、KeyEditable、Current 照编辑的那个中转；添加时 KeyEditable 为真。
	HasKey, KeyEditable, Current bool

	models    []coreapi.RelayModel
	fetched   bool
	loading   bool
	modelsErr error
}

// NewRelayDraft 是添加中转的空草稿。
func NewRelayDraft() *RelayDraft {
	return &RelayDraft{Create: true, KeyEditable: true}
}

// EditRelayDraft 是编辑 r 的草稿：密钥空着（不改）。
func EditRelayDraft(r coreapi.Relay) *RelayDraft {
	return &RelayDraft{Name: r.Name, BaseURL: r.BaseURL, Model: r.Model, HasKey: r.HasKey,
		KeyEditable: r.KeyEditable, Current: r.Current}
}

// Ready 报告能不能保存：名字（添加时）和地址填了，添加时密钥也填了。填得对不对由核心说。
func (d *RelayDraft) Ready() bool {
	if strings.TrimSpace(d.Name) == "" || strings.TrimSpace(d.BaseURL) == "" {
		return false
	}
	return !d.Create || strings.TrimSpace(d.Key) != ""
}

// CanFetch 报告能不能拉模型列表：地址填了，有密钥（新填的，或编辑时已存着的）。
func (d *RelayDraft) CanFetch() bool {
	if strings.TrimSpace(d.BaseURL) == "" || d.loading {
		return false
	}
	return strings.TrimSpace(d.key()) != "" || (!d.Create && d.HasKey)
}

// key 是要交给核心的密钥：Zeta 改不了的中转不交。
func (d *RelayDraft) key() string {
	if !d.KeyEditable {
		return ""
	}
	return strings.TrimSpace(d.Key)
}

// Input 是保存时交给核心的参数。
func (d *RelayDraft) Input() coreapi.RelayInput {
	return coreapi.RelayInput{Name: strings.TrimSpace(d.Name), BaseURL: strings.TrimSpace(d.BaseURL), Key: d.key(),
		Model: strings.TrimSpace(d.Model), Create: d.Create}
}

// Models 是拉来的模型列表；fetched 为假是还没拉过（或正在拉第一次）。
func (d *RelayDraft) Models() (models []coreapi.RelayModel, fetched bool) { return d.models, d.fetched }

// Loading 报告是不是正在拉模型列表。
func (d *RelayDraft) Loading() bool { return d.loading }

// ModelsProblem 是上一次拉模型列表为什么没成，没有时为空。按界面现在的语言出字。
func (d *RelayDraft) ModelsProblem() string { return locales.ErrorText(d.modelsErr) }

// SwitchRelay 换到中转 r：和换号同一套（先看别处挡不挡、收掉本客户端闲着的对话进程）。
func (s *Service) SwitchRelay(r coreapi.Relay) {
	s.change(func() string { return locales.AccountsSwitching(r.Name) }, func(ctx context.Context, program string) error {
		return s.cl.core.SwitchAccount(ctx, program, r.Name)
	})
}

// SaveRelay 添加或修改草稿 d 那个中转。只改设置文件、不动登录状态，所以不看对话忙不忙；改的是在用的中转时，之后起的
// 对话进程才用上。保存成了，重读账号后在界面线程上调 saved（可以为 nil）。
func (s *Service) SaveRelay(d *RelayDraft, saved func()) {
	in := d.Input()
	done := func() {
		s.modelRevision++
		if saved != nil {
			saved()
		}
	}
	s.relayOp(func() string { return locales.AccountsRelaySaving(in.Name) }, locales.AccountsRelaySaveFailed, done,
		func(ctx context.Context, program string) error { return s.cl.core.SaveRelay(ctx, program, in) })
}

// RemoveRelay 删掉中转 name（在用的核心会拒绝，说清原因）。删成了，重读账号后在界面线程上调 removed（可以为 nil）。
func (s *Service) RemoveRelay(name string, removed func()) {
	s.relayOp(func() string { return locales.AccountsRelayRemoving(name) }, locales.AccountsRelayRemoveFailed, removed,
		func(ctx context.Context, program string) error { return s.cl.core.RemoveRelay(ctx, program, name) })
}

// relayOp 做一件改中转设置的事：同一时刻只做一件，出错时前面加 how 那句。
func (s *Service) relayOp(working func() string, how func(string) string, done func(),
	op func(ctx context.Context, program string) error) {
	if s.working != nil {
		s.problem = errBusy
		return
	}
	s.problem, s.working = nil, working
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		defer cancel()
		err := s.cl.do(ctx, op)
		if err != nil {
			err = locales.Wrap(err, how)
		}
		s.finish(ctx, err, false, done)
	})
}

// FetchRelayModels 用草稿里的地址和密钥向中转要模型列表（编辑时密钥空着就用存着的）。结果记在草稿里，
// 不存。正在拉时不再拉（CanFetch 为假），所以不会有晚到的旧结果。
func (s *Service) FetchRelayModels(d *RelayDraft) {
	if !d.CanFetch() {
		return
	}
	d.loading, d.modelsErr = true, nil
	name, baseURL, key := strings.TrimSpace(d.Name), strings.TrimSpace(d.BaseURL), d.key()
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		defer cancel()
		var models []coreapi.RelayModel
		err := s.cl.do(ctx, func(ctx context.Context, program string) (err error) {
			models, err = s.cl.core.RelayModels(ctx, program, name, baseURL, key)
			return err
		})
		s.loop.Post(func() {
			d.loading = false
			if err != nil {
				d.modelsErr = locales.Wrap(err, locales.AccountsRelayModelsFailed)
				return
			}
			d.models, d.fetched = models, true
		})
	})
}

// FilterModels 是 models 里 id 或显示名含 query（不分大小写）的那些；query 空时是全部。
func FilterModels(models []coreapi.RelayModel, query string) []coreapi.RelayModel {
	q := strings.ToLower(strings.TrimSpace(query))
	if q == "" {
		return models
	}
	var out []coreapi.RelayModel
	for _, m := range models {
		if strings.Contains(strings.ToLower(m.ID), q) || strings.Contains(strings.ToLower(m.DisplayName), q) {
			out = append(out, m)
		}
	}
	return out
}
