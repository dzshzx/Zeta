package chat

import (
	"encoding/json"
	"log"

	"github.com/dzshzx/Zeta/helper/tasks"
)

// 后台命令的输出（任务详情页）：看着时核心每秒问一次 Claude Code，输出变了才推 conv.task_output，客户端不定时拉。

// TaskOutput 是看着的后台任务（OutputTarget）最近推来的输出（最后至多 8 KiB，原样含终端转义，显示前用 tasks.PlainTerminalText）；
// 还没推来或没在看着时 ok 为假。
func (c *Conversation) TaskOutput(task string) (out tasks.Output, ok bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if p, _ := watchedAs[tasks.Output](c, OutputTarget(task)); p != nil {
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
	target := OutputTarget(msg.Task)
	if _, ok := c.watched[target]; !ok {
		return false
	}
	c.watched[target] = &msg.Output
	return true
}
