package main

import (
	"encoding/json"
	"errors"

	"github.com/dzshzx/Zeta/helper/core"
)

// endElsewhereHandler 做 sessions.end_elsewhere {session}：结束 Zeta 之外占着这个会话的 Claude Code 进程（同 zeta-helper end，
// 见 end.go 的 endElsewhere：先 SIGTERM，不退再 SIGKILL），不管那边闲不闲；只接管闲着的是 sessions.handoff。
// 在 corehandlers.go 里单独登记，比 "sessions." 长，Mux 先分给它。最多十几秒，挂在 HandleAsync 上。
type endElsewhereHandler struct{ e *env }

func (h endElsewhereHandler) Request(_ *core.Client, r core.Request) (any, error) {
	var p struct {
		Session string `json:"session"`
	}
	if err := json.Unmarshal(r.Raw, &p); err != nil {
		return nil, errors.New("bad params: " + err.Error())
	}
	if !isSessionID(p.Session) {
		return nil, errors.New("bad session id")
	}
	return endedResult{Ended: endElsewhere(h.e, p.Session)}, nil
}

func (endElsewhereHandler) Gone(*core.Client) {}
func (endElsewhereHandler) Idle() bool        { return true }
