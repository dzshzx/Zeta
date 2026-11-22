package main

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
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
	ctx, cancel := context.WithTimeout(ctx, accountsTimeout)
	defer cancel()
	program, err := accounts.Find(e.home, e.name, p.Program)
	if err != nil {
		return nil, accountsError(err)
	}
	h := &accounts.Host{Home: e.home, Package: e.name, Program: program}
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
