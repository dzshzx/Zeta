package chat

import (
	"context"
	"encoding/json"
	"log"
	"slices"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// Subagent 是子代理查看页看着的一个子代理：conv.subagent 推送合并到现在的样子（字段见 docs/core.md「对话」）。
type Subagent struct {
	// Meta 是它记录旁边的说明，还没读到时为 nil。
	Meta *SubagentMeta `json:"meta"`
	// Outcome 是结局：completed、failed、stopped，还没结束时为空。
	Outcome string `json:"outcome"`
	// Prompt 是交给它的任务。
	Prompt   string `json:"prompt"`
	ToolUses int    `json:"tool_uses"`
	Running  bool   `json:"running"`
	// Loaded：读过它的记录；Missing：记录文件还没有；Failure 是读失败的原因。
	Loaded  bool   `json:"loaded"`
	Missing bool   `json:"missing"`
	Failure string `json:"failure"`
	File    string `json:"file"`
	// Skipped 是只留最近一段时前面略去的条数。
	Skipped int `json:"skipped"`
	// Items 是它的记录，和聊天条目同格式。每次推送换成新的切片，拿到的不会被后来的推送改掉；只读。
	Items []items.Item `json:"-"`
	// Rev 每收到一次推送加一：界面据此知道要不要换条目（ChangesSince）。
	Rev int `json:"-"`
	// changes 是最近几次推送带来的条目变化，最后一项把条目带到 Rev 这一版；整份推来时从它重记。
	changes []ItemsChange
}

// keptChanges 是 Subagent 最多记着最近几次的条目变化。
const keptChanges = 64

// ChangesSince 是从 rev 那一版到现在的条目变化，依次套上（Full 的整份换，别的 Splice）就是 Items；
// ok 为假时差得太多、已经没记着了，调用方整份换成 Items。
func (s Subagent) ChangesSince(rev int) (changes []ItemsChange, ok bool) {
	behind := s.Rev - rev
	if behind < 0 || behind > len(s.changes) {
		return nil, false
	}
	return s.changes[len(s.changes)-behind:], true
}

// SubagentMeta 是子代理记录旁边的说明（Claude Code 写的 meta）。
type SubagentMeta struct {
	AgentID     string `json:"agent_id"`
	AgentType   string `json:"agent_type"`
	Description string `json:"description"`
	Model       string `json:"model"`
	// Finished：做完了；Stopped：被停下了。
	Finished   bool  `json:"finished"`
	Stopped    bool  `json:"stopped"`
	Background *bool `json:"background"`
}

// WatchSubagent 开始（watch 为真）或停止看着 Agent 调用 toolUseID 发起的子代理：看着时核心推来它的记录，用 Subagent 取。
// 断线重连后重新打开对话时，看着的会重新看上。
func (c *Conversation) WatchSubagent(ctx context.Context, toolUseID string, watch bool) error {
	c.mu.Lock()
	if c.subagents == nil {
		c.subagents = map[string]*Subagent{}
	}
	if watch {
		if c.subagents[toolUseID] == nil {
			c.subagents[toolUseID] = &Subagent{}
		}
	} else {
		delete(c.subagents, toolUseID)
	}
	c.mu.Unlock()
	return c.call(ctx, "conv.subagent", map[string]any{"tool_use_id": toolUseID, "watch": watch})
}

// Subagent 是看着的子代理此刻的样子；没在看着时 ok 为假。
func (c *Conversation) Subagent(toolUseID string) (s Subagent, ok bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if p := c.subagents[toolUseID]; p != nil {
		return *p, true
	}
	return Subagent{}, false
}

// rewatch 在重新打开对话以后把看着的子代理重新看上（核心从整份推起）。
func (c *Conversation) rewatch(ctx context.Context) {
	c.mu.Lock()
	ids := make([]string, 0, len(c.subagents))
	for id := range c.subagents {
		ids = append(ids, id)
	}
	c.mu.Unlock()
	slices.Sort(ids)
	for _, id := range ids {
		params := map[string]any{"conv": c.name, "tool_use_id": id, "watch": true}
		if _, err := c.client.link.Call(ctx, "conv.subagent", params); err != nil {
			log.Printf("chat: %s 重新看子代理 %s 没成：%v", c.name, id, err)
		}
	}
}

// applySubagent 合并一条 conv.subagent 推送，有变化时返回 true。没在看着的子代理的推送不要。
func (c *Conversation) applySubagent(line []byte) bool {
	var msg struct {
		ToolUseID string `json:"tool_use_id"`
		ItemsChange
		Subagent
	}
	if err := json.Unmarshal(line, &msg); err != nil {
		log.Printf("chat: %s 的 conv.subagent 解不开：%v", c.name, err)
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	cur := c.subagents[msg.ToolUseID]
	if cur == nil {
		return false
	}
	list := msg.ItemsChange.Items
	if !msg.Full {
		next, err := items.Splice(cur.Items, msg.At, msg.Remove, list)
		if err != nil {
			// 和核心那份对不上了（不该发生）：再看一次，核心从整份推起
			log.Printf("chat: %s 的子代理 %s：%v，重新看", c.name, msg.ToolUseID, err)
			go func() {
				ctx, cancel := context.WithTimeout(context.Background(), OpenTimeout)
				defer cancel()
				_ = c.call(ctx, "conv.subagent", map[string]any{"tool_use_id": msg.ToolUseID, "watch": true})
			}()
			return false
		}
		list = next
	}
	// 记下这次的变化，好让界面照着改它那份（拿到的旧切片不受影响：只往后加、从前面截）
	kept := append(cur.changes, msg.ItemsChange)
	if msg.Full {
		kept = []ItemsChange{msg.ItemsChange}
	}
	next := msg.Subagent
	next.Items, next.Rev, next.changes = list, cur.Rev+1, kept[max(0, len(kept)-keptChanges):]
	*cur = next
	return true
}
