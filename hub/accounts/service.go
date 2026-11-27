package accounts

import (
	"context"
	"errors"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/hub/locales"
)

// opTimeout 是一件事（读账号、换号、记下、移除）最多等多久：换号前主机那边还要等进程退出（约 8 秒）。
const opTimeout = 2 * time.Minute

// errOwnBusy 是本客户端还有对话在进行、不能动登录状态。
var errOwnBusy = locales.Err(locales.AccountsOwnBusy)

// errBusy 是上一件事还没做完。
var errBusy = locales.Err(locales.AccountsBusy)

// client 调核心的 accounts.*：带上 claude-switch 的路径；主机上还没装时装上再试一次。
type client struct {
	core    *coreapi.Client
	program Program
}

// do 跑 op（一个 accounts.* 请求，program 是要带的 claude-switch 路径）；核心回 claude_switch_missing 时装上再跑一次。
func (cl client) do(ctx context.Context, op func(ctx context.Context, program string) error) error {
	var path string
	if cl.program != nil {
		path = cl.program.Path()
	}
	err := op(ctx, path)
	if code, _ := hostlink.ReplyCode(err); code == coreapi.CodeClaudeSwitchMissing && cl.program != nil {
		if err := cl.program.Install(ctx); err != nil {
			return locales.Wrap(err, locales.AccountsInstallFailed)
		}
		err = op(ctx, path)
	}
	return err
}

func (cl client) list(ctx context.Context) (st coreapi.AccountList, err error) {
	err = cl.do(ctx, func(ctx context.Context, program string) error {
		st, err = cl.core.ListAccounts(ctx, program)
		return err
	})
	return st, err
}

// Service 是一台主机的账号页背后的状态：那台上 claude-switch 管着哪些账号和中转（relays.go）、当前是哪个、正在做什么、
// 上一件事为什么没做成。
// 换号和记下账号都改那台上 Linux 用户的 Claude Code 登录状态，两个 Zeta 和终端里的 claude 共用这一份，所以只在没有
// 别的 Claude Code 进程开着时做（accounts.list 的 blocker）。
//
// 方法和读状态都在界面线程上；后台的事（loop.Run）做完经 loop.Post 回到界面线程改状态。
type Service struct {
	cl    client
	convs Conversations
	quota *Quota
	loop  Loop

	state         *coreapi.AccountList
	login         *Login // 正在进行的登录，见 login.go
	modelRevision uint64 // 影响有效模型来源的操作成功后递增
	// working 是正在做的事怎么说，没有时为 nil；problem 是上一件事为什么没做成。都在显示时按界面的语言出字。
	working func() string
	problem error
}

// NewService 返回一台主机的账号状态。convs 是本客户端在那台上的对话；quota 是那台的额度（换号后重取，可以为 nil，
// 读到的在用中转也告诉它）；loop 是界面线程的两头（见 Loop）。
func NewService(core *coreapi.Client, program Program, convs Conversations, quota *Quota, loop Loop) *Service {
	return &Service{cl: client{core, program}, convs: convs, quota: quota, loop: loop}
}

// setState 收下读到的账号（界面线程上），告诉额度在用的是不是中转。
func (s *Service) setState(st coreapi.AccountList) {
	s.state = &st
	if s.quota != nil {
		name := ""
		if r := st.CurrentRelay(); r != nil {
			name = r.Name
		}
		s.quota.follow(name)
	}
}

// State 是读到的账号；还没读到时 ok 为 false。
func (s *Service) State() (st coreapi.AccountList, ok bool) {
	if s.state == nil {
		return coreapi.AccountList{}, false
	}
	return *s.state, true
}

// ModelRevision 标识这台设备的有效模型来源。切换来源或保存中转后，客户端用它作废旧清单。
func (s *Service) ModelRevision() uint64 { return s.modelRevision }

// Working 是正在做的事（「正在切换到 …」），没有时为空。
func (s *Service) Working() string {
	if s.working == nil {
		return ""
	}
	return s.working()
}

// Problem 是上一件事为什么没做成，没有时为空。
func (s *Service) Problem() string {
	if s.problem == nil {
		return ""
	}
	return locales.ErrorText(s.problem)
}

// DismissProblem 清掉 Problem（离开页面时）。
func (s *Service) DismissProblem() { s.problem = nil }

// Refresh 重读一遍账号。
func (s *Service) Refresh() {
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		defer cancel()
		st, err := s.cl.list(ctx)
		s.loop.Post(func() {
			switch {
			case err == nil:
				s.setState(st)
			case s.working == nil:
				s.problem = locales.Wrap(err, locales.AccountsReadFailed)
			}
		})
	})
}

// Switch 换成存着的 target：先看有没有别的 Claude Code 进程开着，再收掉本客户端闲着的对话进程，然后在主机上换。
func (s *Service) Switch(target coreapi.Account) {
	s.change(func() string { return locales.AccountsSwitching(target.Title()) }, func(ctx context.Context, program string) error {
		return s.cl.core.SwitchAccount(ctx, program, target.Name)
	})
}

// Save 给还没记过的在用登录起个名字记下（在终端里 /login 了一个新号之后），之后就能在这里切换。
func (s *Service) Save() {
	s.change(locales.AccountsSaving, func(ctx context.Context, program string) error {
		_, err := s.cl.core.SaveAccount(ctx, program)
		return err
	})
}

// Remove 删掉一个不在用的号的存档与记录（只删这台主机上的一份，那个账号在服务端仍是登录状态）。
func (s *Service) Remove(target coreapi.Account) {
	if s.working != nil {
		s.problem = errBusy
		return
	}
	s.problem, s.working = nil, func() string { return locales.AccountsRemoving(target.Title()) }
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		defer cancel()
		err := s.cl.do(ctx, func(ctx context.Context, program string) error {
			return s.cl.core.RemoveAccount(ctx, program, target.Name)
		})
		if err != nil {
			err = locales.Wrap(err, locales.AccountsRemoveFailed)
		}
		s.finish(ctx, err, false, nil)
	})
}

// change 做一件改登录状态的事：本客户端有对话在进行、别的 Claude Code 开着时不做，说清原因；能做时先收掉本客户端闲着的
// 对话进程，再请主机去做。做成了账号和额度都重读。
func (s *Service) change(working func() string, op func(ctx context.Context, program string) error) {
	switch {
	case s.working != nil:
		s.problem = errBusy
		return
	case s.convs.Busy():
		s.problem = errOwnBusy
		return
	}
	s.problem, s.working = nil, working
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		st, err := s.cl.list(ctx)
		cancel()
		switch {
		case err != nil:
			s.loop.Post(func() { s.working, s.problem = nil, locales.Wrap(err, locales.AccountsReadFailed) })
		case st.Blocker != "":
			s.loop.Post(func() {
				s.working, s.problem = nil, errors.New(st.Blocker)
				s.setState(st)
			})
		default:
			s.loop.Post(func() { s.closeAndChange(op) })
		}
	})
}

// closeAndChange 在界面线程上收掉本客户端闲着的对话进程，收得掉就请主机去改。
func (s *Service) closeAndChange(op func(ctx context.Context, program string) error) {
	// 这一刻再看一次：查进程的这会儿可能有对话开始了
	if !s.convs.CloseIdle() {
		s.working, s.problem = nil, errOwnBusy
		return
	}
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		defer cancel()
		err := s.cl.do(ctx, op)
		s.finish(ctx, err, err == nil, nil)
	})
}

// finish 收下一件事的结果（err 为 nil 是做成了），重读账号。switched：登录状态换了，额度在读完账号（知道在用的是不是
// 中转）以后重取；done 在做成时最后调（可以为 nil）。
func (s *Service) finish(ctx context.Context, err error, switched bool, done func()) {
	st, listErr := s.cl.list(ctx)
	s.loop.Post(func() {
		s.working = nil
		if listErr == nil {
			s.setState(st)
		}
		s.problem = err
		if switched {
			s.modelRevision++
			if s.quota != nil {
				s.quota.switched()
			}
		}
		if err == nil && done != nil {
			done()
		}
	})
}
