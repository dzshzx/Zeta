package locales

import (
	"errors"

	"github.com/dzshzx/Zeta/client/hostlink"
	corelocales "github.com/dzshzx/Zeta/helper/locales"
	"github.com/egoist/mygo/i18n"
)

// 出了事要挂在界面上、过一会儿还看得见的话（聊天窗格打不开的原因、账号页的出错、接管没成等），存成出事的数据（err、参数），
// 显示时再出字：换了语言跟着变。这里是存下来的出错怎么说。

// ErrorText 是 err 按界面现在的语言怎么说：核心答的出错（*hostlink.ReplyError，code 是核心文字表的键）按 code 和 args
// 重新出字；别的（包了一层的、主机或 Claude Code 给的原文）是 err.Error()。err 为 nil 时是空。
func ErrorText(err error) string {
	if err == nil {
		return ""
	}
	var refusal *hostlink.ReplyError
	if errors.As(err, &refusal) && err.Error() == refusal.Message && refusal.Code() != "" {
		if _, _, ok := corelocales.Catalog.Message("", refusal.Code()); ok {
			return corelocales.Msg{Key: refusal.Code(), Args: refusal.Args}.In(i18n.Locale())
		}
	}
	return err.Error()
}

// Err 是一句按界面现在的语言说的出错：say 是不带参数的取字函数（如 AccountsBusy），Error() 每次调用时出字。
func Err(say func() string) error { return &saying{say: say} }

// Wrap 是 err 前面加一句话的出错：how 是带一个参数的取字函数（如 AccountsRemoveFailed），Error() 每次调用时按界面现在的
// 语言出字，err 用 ErrorText 说。errors.Is、errors.As 照旧找得到 err。
func Wrap(err error, how func(string) string) error {
	return &saying{say: func() string { return how(ErrorText(err)) }, err: err}
}

// saying 是 Err、Wrap 给的出错；用指针，Err 给的能当哨兵错误用 == 比较。
type saying struct {
	say func() string
	err error
}

func (s *saying) Error() string { return s.say() }
func (s *saying) Unwrap() error { return s.err }

// Now 是存下来的一句话 say 按界面现在的语言怎么说；say 为 nil 时是空。
func Now(say func() string) string {
	if say == nil {
		return ""
	}
	return say()
}
