package accounts

import (
	"context"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/hub/locales"
)

// 添加账号：在那台主机上登录一个新号。登录命令由核心跑（accounts.login_start、accounts.login_code，见 docs/core.md
// 「账号与额度」），这里只管拿到授权网址给用户打开、把用户贴回来的授权码交回去。手机版走的是同一组方法。

// Login 是一次正在进行的登录：授权网址，和上一次交的授权码为什么被退回（没有时为 nil；显示时用 locales.ErrorText 说）。
type Login struct {
	URL    string
	Notice error
}

// errIncompleteCode 是授权码少了 # 和后半段时说的话（核心对同样的码也这么说）。
var errIncompleteCode = locales.Err(locales.AccountsCodeIncomplete)

// Login 是正在进行的登录；没有时 ok 为 false。
func (s *Service) Login() (l Login, ok bool) {
	if s.login == nil {
		return Login{}, false
	}
	return *s.login, true
}

// StartLogin 开始登录一个新号：和换号一样先看能不能动登录状态、收掉本客户端闲着的对话进程，再请主机把在用的号存档、
// 起登录命令。拿到授权网址后 Login 有值。
func (s *Service) StartLogin() {
	s.change(locales.AccountsPreparingLogin, func(ctx context.Context, program string) error {
		start, err := s.cl.core.LoginStart(ctx, program)
		if err == nil {
			s.loop.Post(func() { s.login = &Login{URL: start.URL} })
		}
		return err
	})
}

// SubmitCode 把授权码交给主机上在等的登录命令。授权码不完整时退回，登录接着等；登录成功或彻底失败时登录结束，
// 账号和额度重读。
func (s *Service) SubmitCode(code string) {
	l := s.login
	if l == nil || s.working != nil {
		return
	}
	code = strings.TrimSpace(code)
	if code == "" || (s.requireHash && !strings.Contains(code, "#")) {
		l.Notice = errIncompleteCode
		return
	}
	l.Notice, s.problem, s.working = nil, nil, locales.AccountsLoggingIn
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		defer cancel()
		err := s.cl.do(ctx, func(ctx context.Context, program string) error {
			_, err := s.cl.core.LoginCode(ctx, program, code)
			return err
		})
		if code, _ := hostlink.ReplyCode(err); code == coreapi.CodeLoginIncomplete {
			s.loop.Post(func() { s.working, l.Notice = nil, err })
			return
		}
		s.loop.Post(func() {
			if s.login == l {
				s.login = nil
			}
		})
		switched := err == nil
		if err != nil {
			err = locales.Wrap(err, locales.AccountsLoginFailed)
		}
		s.finish(ctx, err, switched, nil)
	})
}

// CancelLogin 结束正在进行的登录。在用的号开始登录时已经存档，切换能换回去。
func (s *Service) CancelLogin() {
	if s.login == nil {
		return
	}
	s.login = nil
	s.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), opTimeout)
		defer cancel()
		_ = s.cl.core.LoginCancel(ctx)
		s.finish(ctx, nil, false, nil)
	})
}
