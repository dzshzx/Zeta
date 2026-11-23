package chat

import (
	"context"
	"encoding/json"
	"maps"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// 下面是用户对一个对话的操作，各经 coreapi 的对话句柄发一个 conv.* 请求（参数见 docs/core.md「对话」）。
// 这里只加客户端自己的事：连接断着时不发（errNotConnected），关掉时忘掉这个对话。
// 返回的错误是没发成或核心拒绝的原因；成了以后的效果经推送回来。

// Send 发一条消息；回答进行中时核心让它排队。
func (c *Conversation) Send(ctx context.Context, m coreapi.Outgoing) error {
	return c.do(func(v coreapi.Conv) error { return v.Send(ctx, m) })
}

// Interrupt 停止这一轮，排队的消息撤回。
func (c *Conversation) Interrupt(ctx context.Context) error {
	return c.do(func(v coreapi.Conv) error { return v.Interrupt(ctx) })
}

// Steer 立即插话（终端里的 send now）：把排队的第一条马上送进这一轮。
func (c *Conversation) Steer(ctx context.Context) error {
	return c.do(func(v coreapi.Conv) error { return v.Steer(ctx) })
}

// Cancel 撤回一条排队的消息；recall 时放回输入框（State.Recalled）。
func (c *Conversation) Cancel(ctx context.Context, uuid string, recall bool) error {
	return c.do(func(v coreapi.Conv) error { return v.Cancel(ctx, uuid, recall) })
}

// TakeRecalled 取走撤回放回输入框的那条，输入框把它填进去。没有时返回 nil。
func (c *Conversation) TakeRecalled(ctx context.Context) (*coreapi.Message, error) {
	if err := c.ready(); err != nil {
		return nil, err
	}
	m, err := c.api.TakeRecalled(ctx)
	return m, err
}

// Answer 回应一个工具确认、计划审批或提问。
func (c *Conversation) Answer(ctx context.Context, requestID string, r prompt.Reply) error {
	reply := coreapi.Reply{Kind: coreapi.ReplyKind(r.Kind), Feedback: r.Feedback, Mode: r.Mode, Answers: r.Answers, Notes: r.Notes}
	return c.do(func(v coreapi.Conv) error { return v.Answer(ctx, requestID, reply) })
}

// FormError 是 MCP 表单填得不对：键 → 那一项的问题。表单留着，改了再交。
type FormError map[string]string

func (e FormError) Error() string {
	keys := slices.Sorted(maps.Keys(e))
	parts := make([]string, len(keys))
	for i, k := range keys {
		parts[i] = locales.ChatFormProblem(k, e[k])
	}
	return locales.ChatFormInvalid(strings.Join(parts, locales.ChatFormProblemSeparator()))
}

// Elicit 回应 MCP 表单或网址。values 是表单里填的：键 → 输入框里的字，多选是选中的各值，开关是 "true"/"false"。
// 填得不对时返回 FormError。
func (c *Conversation) Elicit(ctx context.Context, requestID string, action prompt.ElicitAction, values map[string][]string) error {
	if err := c.ready(); err != nil {
		return err
	}
	problems, err := c.api.Elicit(ctx, requestID, coreapi.ElicitAction(action), values)
	if len(problems) > 0 {
		return FormError(problems)
	}
	return err
}

// SetModel、SetEffort、SetMode 只管这个对话的模型、思考强度、权限模式；nil 回到默认。
func (c *Conversation) SetModel(ctx context.Context, value *string) error {
	return c.do(func(v coreapi.Conv) error { return v.SetModel(ctx, value) })
}

// SetEffort 见 SetModel。
func (c *Conversation) SetEffort(ctx context.Context, value *string) error {
	return c.do(func(v coreapi.Conv) error { return v.SetEffort(ctx, value) })
}

// SetMode 见 SetModel。
func (c *Conversation) SetMode(ctx context.Context, value *string) error {
	return c.do(func(v coreapi.Conv) error { return v.SetMode(ctx, value) })
}

// SetOptions 改起进程用的设置，下次起进程时生效；重连后重新打开时也用它。
func (c *Conversation) SetOptions(ctx context.Context, o coreapi.Options) error {
	c.mu.Lock()
	c.spec.Options = o
	c.mu.Unlock()
	return c.do(func(v coreapi.Conv) error { return v.SetOptions(ctx, o) })
}

// Prepare 只起好 Claude Code 进程、不发消息（conv.prepare；打 / 时、设置要设备的模型清单时）：起好后斜杠命令和模型清单随 conv.state 推来。
// 已经接着进程时核心什么也不做。
func (c *Conversation) Prepare(ctx context.Context) error {
	return c.do(func(v coreapi.Conv) error { return v.Prepare(ctx) })
}

// Compact 压缩上下文。
func (c *Conversation) Compact(ctx context.Context) error {
	return c.do(func(v coreapi.Conv) error { return v.Compact(ctx) })
}

// StopTask 停一个后台任务。
func (c *Conversation) StopTask(ctx context.Context, task string) error {
	return c.do(func(v coreapi.Conv) error { return v.StopTask(ctx, task) })
}

// End 结束 Claude Code 进程（等于终端里的 /exit）；对话内容留着，下次发消息时接上。
func (c *Conversation) End(ctx context.Context) error {
	return c.do(func(v coreapi.Conv) error { return v.End(ctx) })
}

// Close 放开这个对话：核心里没人拿着、又闲着时会断开进程。之后这个 Conversation 不再收到推送，重连后也不再打开。
// 连接断着时不发：那条连接一断，核心那边就放开了。
func (c *Conversation) Close(ctx context.Context) error {
	c.client.forget(c)
	c.mu.Lock()
	connected := c.connected
	c.connected, c.opened = false, false
	c.mu.Unlock()
	if !connected {
		return nil
	}
	return c.api.Close(ctx)
}

// errNotConnected：对话现在没连上（断线等重连、已关掉），请求不发。说法按显示时界面的语言出，errors.Is 照样认得。
var errNotConnected error = notConnected{}

type notConnected struct{}

func (notConnected) Error() string { return locales.ChatNotConnected() }

// ready 是对话现在能不能发请求：没连上时返回 errNotConnected。
func (c *Conversation) ready() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.connected {
		return errNotConnected
	}
	return nil
}

// do 在连着时经对话句柄发一个请求。
func (c *Conversation) do(send func(v coreapi.Conv) error) error {
	if err := c.ready(); err != nil {
		return err
	}
	return send(c.api)
}

// LoadEarlier 载入更早的一页接到最前面（rest 时载完剩下的全部）：新的一页经 conv.items 推来（从 0 起插入），读完才返回。
// 会话记录在别处改过时的错误 IsEarlierStale 认得出，重试没用，要重新打开对话。
func (c *Conversation) LoadEarlier(ctx context.Context, rest bool) error {
	return c.do(func(v coreapi.Conv) error { return v.LoadEarlier(ctx, rest) })
}

// IsEarlierStale：err 是 conv.earlier 因为会话记录在别处改过而没读成（核心答的 code 是 history_changed），重试没用。
func IsEarlierStale(err error) bool {
	code, _ := hostlink.ReplyCode(err)
	return code == sessionproto.CodeHistoryChanged
}

// Quota 借这个对话开着的 Claude Code 进程问一次订阅额度（conv.quota），返回 get_usage 应答的原文，交给 quota.usage 读。
// 没有进程时核心答出错。
func (c *Conversation) Quota(ctx context.Context) (json.RawMessage, error) {
	if err := c.ready(); err != nil {
		return nil, err
	}
	return c.api.Quota(ctx)
}
