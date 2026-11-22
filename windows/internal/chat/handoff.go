package chat

import (
	"context"
	"encoding/json"
)

// 会话正被 Zeta 之外的进程占着时（State.Blocked）：查那边闲不闲、把会话接过来（conv.occupant、conv.handoff，见 docs/core.md）。
// 判断能不能交只在核心里做，这边照它说的画。

// Occupant 是 Zeta 之外占着会话的那个 Claude Code 进程的样子（helper/conv/handoff.go 的 Occupancy）。
type Occupant struct {
	// Status 是它登记的忙闲：idle、waiting（在等确认）、busy、shell（在跑 ! 命令）；看不出时是 unknown。没人占着时为空。
	Status string `json:"status"`
	// WaitingFor 是在等确认时等的是什么。
	WaitingFor string `json:"waiting_for"`
	// HandsOver：现在能交给 Zeta（两轮之间）。
	HandsOver bool `json:"hands_over"`
}

// 占着的进程的几种忙闲（Occupant.Status）。
const (
	OccupantIdle    = "idle"
	OccupantWaiting = "waiting"
	OccupantShell   = "shell"
	OccupantUnknown = "unknown"
)

// Handoff 是一次接管的结果：Handed 是交出来了（本来就没人占着也算），否则 Status 是那边的样子，发了中断还不退的是 HandoffStuck。
type Handoff struct {
	Handed bool   `json:"handed"`
	Status string `json:"status"`
}

// HandoffStuck 是发了中断、那边过了一会儿还没退。
const HandoffStuck = "stuck"

// Occupant 查占着这个会话的进程；没人占着时 Status 为空。
func (c *Conversation) Occupant(ctx context.Context) (Occupant, error) {
	var o Occupant
	raw, err := c.callResult(ctx, "conv.occupant", nil)
	if err == nil && !isNull(raw) {
		err = json.Unmarshal(raw, &o)
	}
	return o, err
}

// Handoff 把会话从别处接过来（那边闲着或在等确认时让它退出，正忙时不动）：交出来了核心解开 Blocked。最多等十几秒。
func (c *Conversation) Handoff(ctx context.Context) (Handoff, error) {
	var h Handoff
	raw, err := c.callResult(ctx, "conv.handoff", nil)
	if err == nil && !isNull(raw) {
		err = json.Unmarshal(raw, &h)
	}
	return h, err
}

// Retitle 给对话改名（conv.title）：写进会话记录，核心推来新的标题。还没有会话号时出错。
func (c *Conversation) Retitle(ctx context.Context, title string) error {
	return c.call(ctx, "conv.title", map[string]any{"title": title})
}
