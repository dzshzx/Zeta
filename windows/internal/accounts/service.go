package accounts

import (
	"context"
	"encoding/json"
	"errors"
	"time"
)

// opTimeout 是一件事（读账号、换号、记下、移除）最多等多久：换号前主机那边还要等进程退出（约 8 秒）。
const opTimeout = 2 * time.Minute

// ownBusy 是本客户端还有对话在进行、不能动登录状态。
const ownBusy = "这里还有对话在进行"

// client 调 serve 的方法：带上 claude-switch 的路径；主机上还没装时装上再试一次。
type client struct {
	caller  Caller
	program Program
}

func (cl client) call(ctx context.Context, method string, params map[string]any, out any) error {
	if params == nil {
		params = map[string]any{}
	}
	if cl.program != nil && cl.program.Path() != "" {
		params["program"] = cl.program.Path()
	}
	raw, err := cl.caller.Call(ctx, method, params)
	var c coded
	if errors.As(err, &c) && c.Code() == "claude_switch_missing" && cl.program != nil {
		if err := cl.program.Install(ctx); err != nil {
			return errors.New("装不上 claude-switch：" + err.Error())
		}
		raw, err = cl.caller.Call(ctx, method, params)
	}
	if err != nil || out == nil {
		return err
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return errors.New("读不懂主机的回答")
	}
	return nil
}

func (cl client) list(ctx context.Context) (State, error) {
	var st State
	err := cl.call(ctx, "accounts.list", nil, &st)
	return st, err
}

// Service 是一台主机的账号页背后的状态：那台上 claude-switch 管着哪些账号、当前是哪个、正在做什么、上一件事为什么没做成。
// 换号和记下账号都改那台上 Linux 用户的 Claude Code 登录状态，两个 Zeta 和终端里的 claude 共用这一份，所以只在没有
// 别的 Claude Code 进程开着时做（accounts.list 的 blocker）。
//
// 方法和读状态都在界面线程上；后台的事（loop.Run）做完经 loop.Post 回到界面线程改状态。
type Service struct {
	cl    client
	convs Conversations
	quota *Quota
	loop  Loop

	state   *State
	working string
	problem string
}

// NewService 返回一台主机的账号状态。convs 是本客户端在那台上的对话；quota 是那台的额度（换号后重取，可以为 nil）；
// loop 是界面线程的两头（见 Loop）。
func NewService(caller Caller, program Program, convs Conversations, quota *Quota, loop Loop) *Service {
	return &Service{cl: client{caller, program}, convs: convs, quota: quota, loop: loop}
}

// State 是读到的账号；还没读到时 ok 为 false。
func (s *Service) State() (st State, ok bool) {
	if s.state == nil {
		return State{}, false
	}
	return *s.state, true
}

// Working 是正在做的事（「正在切换到 …」），没有时为空。
func (s *Service) Working() string { return s.working }

// Problem 是上一件事为什么没做成，没有时为空。
func (s *Service) Problem() string { return s.problem }

// DismissProblem 清掉 Problem（离开页面时）。
func (s *Service) DismissProblem() { s.problem = "" }

// Refresh 重读一遍账号。
func (s *Service) Refresh() {
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		defer cancel()
		st, err := s.cl.list(ctx)
		s.loop.Post(func() {
			if err == nil {
				s.state = &st
			} else if s.working == "" {
				s.problem = "读不了账号：" + err.Error()
			}
		})
	})
}

// Switch 换成存着的 target：先看有没有别的 Claude Code 进程开着，再收掉本客户端闲着的对话进程，然后在主机上换。
func (s *Service) Switch(target Account) {
	s.change("正在切换到 "+target.Title(), "accounts.switch", map[string]any{"name": target.Name})
}

// Save 给还没记过的在用登录起个名字记下（在终端里 /login 了一个新号之后），之后就能在这里切换。
func (s *Service) Save() { s.change("正在记下", "accounts.save", nil) }

// Remove 删掉一个不在用的号的存档与记录（只删这台主机上的一份，那个账号在服务端仍是登录状态）。
func (s *Service) Remove(target Account) {
	if s.working != "" {
		s.problem = "上一件事还没做完"
		return
	}
	s.problem, s.working = "", "正在移除 "+target.Title()
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		defer cancel()
		err := s.cl.call(ctx, "accounts.remove", map[string]any{"name": target.Name}, nil)
		if err != nil {
			err = errors.New("没有移除：" + err.Error())
		}
		s.finish(ctx, err)
	})
}

// change 做一件改登录状态的事：本客户端有对话在进行、别的 Claude Code 开着时不做，说清原因；能做时先收掉本客户端闲着的
// 对话进程，再请主机去做。做成了账号和额度都重读。
func (s *Service) change(working, method string, params map[string]any) {
	switch {
	case s.working != "":
		s.problem = "上一件事还没做完"
		return
	case s.convs.Busy():
		s.problem = ownBusy
		return
	}
	s.problem, s.working = "", working
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		st, err := s.cl.list(ctx)
		cancel()
		switch {
		case err != nil:
			s.loop.Post(func() { s.working, s.problem = "", "读不了账号："+err.Error() })
		case st.Blocker != "":
			s.loop.Post(func() { s.working, s.problem, s.state = "", st.Blocker, &st })
		default:
			s.loop.Post(func() { s.closeAndChange(method, params) })
		}
	})
}

// closeAndChange 在界面线程上收掉本客户端闲着的对话进程，收得掉就请主机去改。
func (s *Service) closeAndChange(method string, params map[string]any) {
	// 这一刻再看一次：查进程的这会儿可能有对话开始了
	if !s.convs.CloseIdle() {
		s.working, s.problem = "", ownBusy
		return
	}
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		defer cancel()
		err := s.cl.call(ctx, method, params, nil)
		if err == nil && s.quota != nil {
			s.loop.Post(s.quota.switched)
		}
		s.finish(ctx, err)
	})
}

// finish 收下一件事的结果（err 为 nil 是做成了），重读账号。
func (s *Service) finish(ctx context.Context, err error) {
	st, listErr := s.cl.list(ctx)
	s.loop.Post(func() {
		s.working = ""
		if listErr == nil {
			s.state = &st
		}
		s.problem = ""
		if err != nil {
			s.problem = err.Error()
		}
	})
}
