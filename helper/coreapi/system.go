package coreapi

import "context"

// 这一组是 docs/core.md「主机」的 system.* 方法：这台主机的负载、闲置超时和 Claude Code 版本。

// CPU 是这台主机整机的 CPU 负载（helper/cpu.go）。
type CPU struct {
	Percent *int `json:"percent"` // 忙碌百分比（0～100），读不了 /proc/stat 时为 nil
	Cores   int  `json:"cores"`   // 逻辑核数
}

// CPU 读这台主机的 CPU 负载，最多等 0.1 秒（system.cpu）。
func (cl *Client) CPU(ctx context.Context) (CPU, error) {
	return get[CPU](ctx, cl.c, "system.cpu", nil)
}

type idleReply struct {
	Minutes int `json:"minutes"`
}

// IdleMinutes 读这台主机设的闲置超时（分钟，0 是不自动结束；没设时是 10；system.idle）。
func (cl *Client) IdleMinutes(ctx context.Context) (int, error) {
	r, err := get[idleReply](ctx, cl.c, "system.idle", nil)
	return r.Minutes, err
}

// SetIdleMinutes 改这台主机的闲置超时（0～1440），给出改后的值；不合要求时出错、不写（system.set_idle）。
func (cl *Client) SetIdleMinutes(ctx context.Context, minutes int) (int, error) {
	r, err := get[idleReply](ctx, cl.c, "system.set_idle", params{"minutes": minutes})
	return r.Minutes, err
}

// ClaudeVersion 是这台主机上 Claude Code 的版本（claude --version 的版本号），没装或跑不起来时为空串（system.claude）。
func (cl *Client) ClaudeVersion(ctx context.Context) (string, error) {
	r, err := get[struct {
		Version string `json:"version"` // null 解成空串
	}](ctx, cl.c, "system.claude", nil)
	return r.Version, err
}

// ClaudeUpdate 是在主机上跑一次 claude update 的结果。
type ClaudeUpdate struct {
	OK      bool   `json:"ok"`      // claude update 退出码为 0
	Before  string `json:"before"`  // 更新前的版本，没装时为空串
	Version string `json:"version"` // 更新后的版本，没装时为空串
	Output  string `json:"output"`  // 输出的最后 8 行
}

// UpdateClaude 在这台主机上跑 claude update（最多 5 分钟）；同一个核心里已经在跑时出错（system.claude_update）。
func (cl *Client) UpdateClaude(ctx context.Context) (ClaudeUpdate, error) {
	return get[ClaudeUpdate](ctx, cl.c, "system.claude_update", nil)
}
