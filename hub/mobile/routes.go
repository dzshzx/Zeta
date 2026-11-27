package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/locales"
)

// 方法表：每个领域在自己的文件里用 init 登记自己的方法（会话 sessions.go、设置 settings_*.go……），参数各自解，
// 不往一个共用的 switch 和参数结构里加。方法名不能重复，重了在 init 时 panic，测试一跑就报。
//
// 三种跑法：
//   - onLoop：在核心 goroutine 上跑（读写中枢都在这里），很快返回。
//   - inChat：对聊天页里的对话做的事，在后台做，没成的原因放进下一次 state 的 chat.failed。
//   - blocking：在调用方的线程上跑，可以等网络（Swift 在后台线程上调）；要碰中枢时自己 c.Do。
var routes = map[string]route{}

type route struct {
	loop     func(c *Core, raw json.RawMessage) (any, error)
	chat     func(raw json.RawMessage) (func(ctx context.Context, conv *chat.Conversation) error, error)
	blocking func(c *Core, raw json.RawMessage) (any, error)
	quiet    bool // 只读：跑完不算有变化（见 job.quiet）
}

func register(name string, r route) {
	if _, dup := routes[name]; dup {
		panic("mobile: method registered twice: " + name)
	}
	routes[name] = r
}

// onLoop 登记一个在核心 goroutine 上跑的方法，参数解成 P。
func onLoop[P any](name string, f func(c *Core, p P) (any, error)) {
	register(name, route{loop: func(c *Core, raw json.RawMessage) (any, error) {
		p, err := decode[P](raw)
		if err != nil {
			return nil, err
		}
		return f(c, p)
	}})
}

// reading 同 onLoop，只是只读、不算有变化。
func reading[P any](name string, f func(c *Core, p P) (any, error)) {
	onLoop(name, f)
	r := routes[name]
	r.quiet = true
	routes[name] = r
}

// inChat 登记一个对聊天页里的对话做的事：f 由参数算出要做的事，返回 nil 是什么都不做（比如空消息）。
func inChat[P any](name string, f func(p P) (func(ctx context.Context, conv *chat.Conversation) error, error)) {
	register(name, route{chat: func(raw json.RawMessage) (func(ctx context.Context, conv *chat.Conversation) error, error) {
		p, err := decode[P](raw)
		if err != nil {
			return nil, err
		}
		return f(p)
	}})
}

// blocking 登记一个在调用方线程上跑、可以等的方法。
func blocking[P any](name string, f func(c *Core, p P) (any, error)) {
	register(name, route{blocking: func(c *Core, raw json.RawMessage) (any, error) {
		p, err := decode[P](raw)
		if err != nil {
			return nil, err
		}
		return f(c, p)
	}})
}

func decode[P any](raw json.RawMessage) (P, error) {
	var p P
	if err := json.Unmarshal(raw, &p); err != nil {
		return p, fmt.Errorf("params are not a JSON object: %w", err)
	}
	return p, nil
}

// none 是没有参数的方法的参数。
type none struct{}

// dispatch 按方法表跑 method。
func (c *Core) dispatch(method string, raw json.RawMessage) (any, error) {
	r, found := routes[method]
	if !found {
		return nil, fmt.Errorf("%w: %s", errUnknown, method)
	}
	switch {
	case r.blocking != nil:
		return r.blocking(c, raw)
	case r.chat != nil:
		do, err := r.chat(raw)
		if err != nil || do == nil {
			return ok, err
		}
		var result any = ok
		c.wait(func() { result, err = c.runChat(do) }, false)
		return result, err
	}
	var result any
	var err error
	c.wait(func() { result, err = r.loop(c, raw) }, r.quiet)
	return result, err
}

// runChat 在后台对聊天页里的对话做 do（核心 goroutine 上调）。
func (c *Core) runChat(do func(ctx context.Context, conv *chat.Conversation) error) (any, error) {
	if c.hub.Chat() == nil || c.hub.Chat().Conv == nil {
		return nil, locales.Err(locales.MobileChatNotOpen)
	}
	c.problem = ""
	c.hub.RunChat(do, func(err error) { c.problem = locales.ErrorText(err) })
	return ok, nil
}

// 转给一台设备核心的方法（docs/core.md 的 config.*、diag.*、system.*）：结果原样交给界面，界面自己解。
// 只放这几组：对话、会话列表、账号这些有中枢自己的状态，要经中枢的方法，不从这里绕过去。
var forwarded = []string{"config.", "diag.", "system."}

// coreTimeout 是转发一个请求最多等多久（同桌面版设置页的请求）。
const coreTimeout = 60 * time.Second

type coreParams struct {
	Backend string         `json:"backend"`
	Method  string         `json:"method"`
	Params  map[string]any `json:"params"`
}

func init() {
	blocking("core", func(c *Core, p coreParams) (any, error) {
		if !forwardable(p.Method) {
			return nil, fmt.Errorf("%w: %s", errUnknown, p.Method)
		}
		var r coreapi.Caller
		var name string
		c.Do(func() {
			name = c.hub.Name(p.Backend)
			if remote := c.hub.Remote(p.Backend); remote != nil {
				r = remote.Core.Caller()
			}
		})
		if r == nil {
			return nil, locales.Err(func() string { return locales.HubOffline(name) })
		}
		ctx, cancel := context.WithTimeout(context.Background(), coreTimeout)
		defer cancel()
		if p.Params == nil {
			p.Params = map[string]any{}
		}
		raw, err := r.Call(ctx, p.Method, p.Params)
		if err != nil {
			return nil, err
		}
		if len(raw) == 0 {
			return json.RawMessage("null"), nil
		}
		return raw, nil
	})
}

func forwardable(method string) bool {
	for _, prefix := range forwarded {
		if strings.HasPrefix(method, prefix) {
			return true
		}
	}
	return false
}
