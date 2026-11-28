// Package coreapi 是核心（zeta-helper core，经 serve 转发）全部请求方法的 Go 调用层：每个方法一个带类型的函数，
// 参数和结果都是结构体，客户端（Windows 桌面版，以及以后撤掉 shared/ 后的手机版）直接调它，不各自拼方法名和参数、
// 不各写一份结果类型。方法的含义、参数、结果以 docs/core.md 为准；TestMethodsMatchTheDocs 核对这里的方法和那几张表一一对上。
//
// 本包只管「发什么、收到什么」：连接、重连、超时、在哪个线程上调都是客户端的事（经 Caller 接进来）。推送（conv.items、
// zeta_sessions 等）不经这里，客户端自己解。只依赖标准库和 helper/sessionproto。
package coreapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"strings"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// Caller 发一个请求、拿回应答的 result。client/hostlink 的 Serve 连接就是一个。
type Caller interface {
	// Call 发 method：params 的各项和 id、method 平级放进请求那一行。成功时返回应答的 result（没有时为 nil）；
	// 应答 ok 为假时返回的错误就是它的 error（给人看的原因），带 code 的经 hostlink.ReplyCode 取。连不上、断了也返回错误。
	Call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error)
}

// Client 是经一条连接调的那台主机的核心。零值不能用，用 New。
type Client struct{ c Caller }

// New 是经 c 调的核心。
func New(c Caller) *Client { return &Client{c: c} }

// Caller 是 New 给的那条连接。
func (cl *Client) Caller() Caller {
	if hc, ok := cl.c.(harnessCaller); ok {
		return hc.c
	}
	return cl.c
}

type harnessCaller struct {
	c       Caller
	harness string
}

func (h harnessCaller) Call(ctx context.Context, method string, p map[string]any) (json.RawMessage, error) {
	if h.harness != "" && h.harness != sessionproto.HarnessClaude &&
		(strings.HasPrefix(method, "config.") || strings.HasPrefix(method, "accounts.") || strings.HasPrefix(method, "quota.")) {
		cp := make(map[string]any, len(p)+1)
		for k, v := range p {
			cp[k] = v
		}
		cp["harness"] = h.harness
		p = cp
	}
	return h.c.Call(ctx, method, p)
}

// WithHarness 返回配置（config.*）、账号（accounts.*）和额度（quota.*）请求针对指定 harness（"claude" 或 "agy"）的 Client。
func (cl *Client) WithHarness(harness string) *Client {
	h := sessionproto.NormalizeHarness(harness)
	base := cl.Caller()
	if h == sessionproto.HarnessClaude {
		return &Client{c: base}
	}
	return &Client{c: harnessCaller{c: base, harness: h}}
}

// params 是一个请求的参数。
type params = map[string]any

// ErrNoResult：方法该有结果，核心却没给（result 缺或为 null）。
var ErrNoResult = errors.New("核心没有给结果")

// call 发请求、不要结果。
func call(ctx context.Context, c Caller, method string, p params) error {
	if p == nil {
		p = params{}
	}
	_, err := c.Call(ctx, method, p)
	return err
}

// get 发请求，把 result 解成 T。result 缺或为 null 时返回 ErrNoResult。
func get[T any](ctx context.Context, c Caller, method string, p params) (T, error) {
	var out T
	raw, err := raw(ctx, c, method, p)
	if err != nil {
		return out, err
	}
	if isNull(raw) {
		return out, ErrNoResult
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return out, errors.New("核心给的结果认不出：" + err.Error())
	}
	return out, nil
}

// maybe 同 get，只是 result 为 null 是正常的（「没有」）：这时返回 nil。
func maybe[T any](ctx context.Context, c Caller, method string, p params) (*T, error) {
	raw, err := raw(ctx, c, method, p)
	if err != nil || isNull(raw) {
		return nil, err
	}
	out := new(T)
	if err := json.Unmarshal(raw, out); err != nil {
		return nil, errors.New("核心给的结果认不出：" + err.Error())
	}
	return out, nil
}

// raw 发请求，result 原样返回（客户端要转交给别的方法、或自己按推送的写法解的）。
func raw(ctx context.Context, c Caller, method string, p params) (json.RawMessage, error) {
	if p == nil {
		p = params{}
	}
	return c.Call(ctx, method, p)
}

func isNull(raw json.RawMessage) bool {
	s := bytes.TrimSpace(raw)
	return len(s) == 0 || string(s) == "null"
}
