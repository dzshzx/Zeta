package main

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/accounts"
)

// 核心的账号与额度：accounts.* 和 quota.* 方法都从 handleAccounts 进（挂在核心上，见 corehandlers.go），方法与参数见 docs/core.md「账号与额度」。
//
// 没有共享状态，可以放进 goroutine 并发调用：accounts.* 要跑 claude-switch，改登录状态的还要先等开着的
// Claude Code 退出（最多约 8 秒），慢；quota.* 只读写家目录下的小文件，快。改登录状态的几个由 accounts 包一个一个来。

// accountsTimeout 是一个账号请求最多做多久。
const accountsTimeout = 2 * time.Minute

// accountsParams 是请求里和 id、method 平级的参数；整行交来也行，多的键不管。
type accountsParams struct {
	// Name 是 accounts.switch、accounts.remove 的账号名。
	Name string `json:"name"`
	// Program 是 claude-switch 相对家目录的路径（客户端带的版本，同 HelperProgram.relativePath）；不给时在 ~/.cache/<包>/bin 下找。
	Program string `json:"program"`
	// Response 是 quota.usage 的 get_usage 应答（control_response 的 response.response），Info 是 quota.event 的 rate_limit_info。
	Response json.RawMessage `json:"response"`
	Info     json.RawMessage `json:"info"`
	// Code 是 accounts.login_code 交的授权码。
	Code string `json:"code"`
}

// codedError 是带代号的出错：核心回 ok:false 时把代号放进 reply 的 code，客户端按它认出要做什么（比如先装 claude-switch）。
type codedError struct {
	code string
	err  error
}

func (e codedError) Error() string { return e.err.Error() }
func (e codedError) Code() string  { return e.code }
func (e codedError) Unwrap() error { return e.err }

// handleAccounts 处理一个 accounts.* 或 quota.* 请求（e 是家目录和包），返回放进 reply 的 result。
func handleAccounts(ctx context.Context, e *env, method string, params json.RawMessage) (any, error) {
	var p accountsParams
	if len(params) > 0 {
		if err := json.Unmarshal(params, &p); err != nil {
			return nil, errors.New("bad params: " + err.Error())
		}
	}
	if strings.HasPrefix(method, "quota.") {
		return handleQuota(e, method, p)
	}
	if !strings.HasPrefix(method, "accounts.") {
		return nil, errors.New("unknown method: " + method)
	}
	if method == "accounts.login_cancel" { // 不用 claude-switch：这台上没装它时也要能取消
		logins.cancel()
		return struct{}{}, nil
	}
	ctx, cancel := context.WithTimeout(ctx, accountsTimeout)
	defer cancel()
	program, err := accounts.Find(e.home, e.name, p.Program)
	if err != nil {
		return nil, accountsError(err)
	}
	h := &accounts.Host{Home: e.home, Package: e.name, Program: program, EndIdle: func() { endIdleHosted(e) }}
	var result any
	switch method {
	case "accounts.list":
		result, err = h.List(ctx)
	case "accounts.switch":
		err = h.Switch(ctx, p.Name)
		result = struct{}{}
	case "accounts.save":
		var name string
		name, err = h.Save(ctx)
		result = map[string]string{"name": name}
	case "accounts.prepare_login":
		var name string
		name, err = h.PrepareLogin(ctx)
		result = map[string]string{"name": name}
	case "accounts.login_start":
		result, err = logins.start(ctx, e, h)
	case "accounts.login_code":
		result, err = logins.submit(ctx, h, p.Code)
	case "accounts.remove":
		err = h.Remove(ctx, p.Name)
		result = struct{}{}
	default:
		return nil, errors.New("unknown method: " + method)
	}
	if err != nil {
		return nil, accountsError(err)
	}
	return result, nil
}

// accountsError 给认得出的出错加上代号。
func accountsError(err error) error {
	var busy *accounts.BusyError
	switch {
	case errors.Is(err, accounts.ErrNoProgram):
		return codedError{"claude_switch_missing", err}
	case errors.As(err, &busy):
		return codedError{"busy", err}
	case errors.Is(err, accounts.ErrMissing):
		return codedError{"no_account", err}
	}
	return err
}

func handleQuota(e *env, method string, p accountsParams) (any, error) {
	store := accounts.QuotaStore{Home: e.home}
	now := time.Now()
	switch method {
	case "quota.get":
		snap, err := store.Get()
		return map[string]any{"snapshot": snap}, err
	case "quota.usage":
		snap, fresh, err := store.Usage(p.Response, now)
		if err != nil {
			return nil, err
		}
		return map[string]any{"snapshot": snap, "fresh": fresh}, nil
	case "quota.event":
		snap, changed, err := store.Event(p.Info, now)
		if err != nil {
			return nil, err
		}
		return map[string]any{"snapshot": snap, "changed": changed}, nil
	}
	return nil, errors.New("unknown method: " + method)
}

// autoQuotaEvery 是起对话进程时顺便问额度的最短间隔，这台主机上的对话共用：Claude Code 取额度的接口会限流。
const autoQuotaEvery = 5 * time.Minute

// quotaSink 是对话交来的额度的去处（conv.Deps.Quota）：输出里的 rate_limit_event、起进程时顺便问的 get_usage，
// 和客户端经 quota.event、quota.usage 交来的一样存进额度存储，quota.get 读到的就是它们。
type quotaSink struct {
	store   accounts.QuotaStore
	now     func() time.Time
	changed func() // 存了以后调（推给订阅的客户端，见 quotaWatch）；可以是 nil

	mu        sync.Mutex
	lastAsked time.Time // 上次起进程时顺便问的时刻
}

func newQuotaSink(e *env, changed func()) *quotaSink {
	return &quotaSink{store: accounts.QuotaStore{Home: e.home}, now: time.Now, changed: changed}
}

func (q *quotaSink) stored() {
	if q.changed != nil {
		q.changed()
	}
}

func (q *quotaSink) RateLimit(info json.RawMessage) {
	if _, changed, err := q.store.Event(info, q.now()); err != nil {
		log.Print("存额度（rate_limit_event）：", err)
	} else if changed {
		q.stored()
	}
}

func (q *quotaSink) ClaimQuery() bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	now := q.now()
	if !q.lastAsked.IsZero() && now.Sub(q.lastAsked) < autoQuotaEvery {
		return false
	}
	q.lastAsked = now
	return true
}

func (q *quotaSink) Usage(response json.RawMessage) {
	if _, _, err := q.store.Usage(response, q.now()); err != nil {
		log.Print("存额度（get_usage）：", err)
	} else {
		q.stored()
	}
}
