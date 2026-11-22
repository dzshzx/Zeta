package chat

import (
	"context"
	"encoding/json"
	"log"

	"github.com/dzshzx/Zeta/helper/tasks"
)

// 后台命令的输出（任务详情页）：看着时核心每秒问一次 Claude Code，输出变了才推 conv.task_output，客户端不定时拉。

// WatchTaskOutput 开始（watch 为真）或停止看着后台任务 task 的输出：看着时核心先推一份、之后变了再推，用 TaskOutput 取。
// 断线重连后重新打开对话时，看着的会重新看上。
func (c *Conversation) WatchTaskOutput(ctx context.Context, task string, watch bool) error {
	c.mu.Lock()
	if watch {
		if c.outputs == nil {
			c.outputs = map[string]*tasks.Output{}
		}
		if _, ok := c.outputs[task]; !ok {
			c.outputs[task] = nil
		}
	} else {
		delete(c.outputs, task)
	}
	c.mu.Unlock()
	return c.call(ctx, "conv.task_output", map[string]any{"task": task, "watch": watch})
}

// TaskOutput 是看着的后台任务最近推来的输出（最后至多 8 KiB，原样含终端转义，显示前用 tasks.PlainTerminalText）；
// 还没推来或没在看着时 ok 为假。
func (c *Conversation) TaskOutput(task string) (out tasks.Output, ok bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if p := c.outputs[task]; p != nil {
		return *p, true
	}
	return tasks.Output{}, false
}

// applyTaskOutput 收下一条 conv.task_output 推送（整份），有变化时返回 true。没在看着的任务的推送不要。
func (c *Conversation) applyTaskOutput(line []byte) bool {
	var msg struct {
		Task string `json:"task"`
		tasks.Output
	}
	if err := json.Unmarshal(line, &msg); err != nil {
		log.Printf("chat: %s 的 conv.task_output 解不开：%v", c.name, err)
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.outputs[msg.Task]; !ok {
		return false
	}
	c.outputs[msg.Task] = &msg.Output
	return true
}
