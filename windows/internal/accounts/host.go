// Package accounts 是桌面版这边一台主机的账号与额度：调主机上 zeta-helper serve 的 accounts.*、quota.* 方法（逻辑都在
// helper/accounts，方法见 docs/core.md「账号与额度」），把结果整理成界面要的状态。这里不碰主机上的文件和令牌。
//
// Service 和 Quota 的状态只在界面线程上读写：要等主机的事经构造时给的 Loop.Run 放到后台，做完经 Loop.Post（窗口的 Update）改状态。
package accounts

import (
	"context"
	"encoding/json"
	"errors"
)

// Loop 是界面线程的两头：Run 把要等主机的事放到后台，Post 把函数交回界面线程跑。测试里可以都就地执行。
type Loop struct {
	Run, Post func(func())
}

// Caller 向一台主机上的 serve 发一个请求：params 的各个键和 id、method 平级放进请求那一行，返回 reply 的 result 原文。
// serve 回 ok:false 时 err 的 Error() 是它的 error；reply 带 code 时 err 还要有方法 Code() string 返回它（hostlink.ReplyCode 认得出）。
// 由 internal/host 实现；阻塞，ctx 到期时放弃。
type Caller interface {
	Call(ctx context.Context, method string, params any) (json.RawMessage, error)
}

// Program 是桌面版带到主机上的 claude-switch（按内容哈希装，同 zeta-helper），由 internal/host 实现。
type Program interface {
	// Path 是它装好以后相对那边家目录的路径（.cache/zeta/bin/claude-switch-<版本>），随请求带给 serve。
	Path() string
	// Install 把它装上：serve 回 code claude_switch_missing 时装一次再试。
	Install(ctx context.Context) error
}

// Conversations 是本客户端在这台主机上的对话。登录状态那台上两个 Zeta 和终端里的 claude 共用一份，换号、记下账号前
// 本客户端的对话要先放手。由中枢实现，方法在界面线程上调用。
type Conversations interface {
	// Busy 报告有没有对话在进行（在回答、等确认或有后台任务）。
	Busy() bool
	// CloseIdle 结束闲着的对话进程（内容不丢，下次发消息时接上）；有对话在进行时什么都不做，返回 false。
	CloseIdle() bool
}

// UsageSource 向那台主机上的 Claude Code 要一次额度（serve 的 conv.quota：借开着的对话进程问，没有时返回 ErrNoProcess），
// 返回 get_usage 应答的原文（control_response 的 response.response）。由对话核心那边实现；没有时 Quota 只读主机上存着的数。
type UsageSource interface {
	Usage(ctx context.Context) (json.RawMessage, error)
}

// ErrNoProcess 是 UsageSource 在那台上没有开着的 Claude Code 进程可借时返回的：Quota 这时只读主机上存着的数。
var ErrNoProcess = errors.New("没有开着的 Claude Code 进程")
