package chat

import (
	"cmp"
	"context"
	"log"
	"maps"
	"slices"
)

// 看着的东西：子代理的记录（conv.subagent）和后台命令的输出（conv.task_output）。看着时核心先推一份、之后变了再推；
// 断线重连后重新打开对话时一起重新看上（rewatch）。两样记在同一个集合里（Conversation.watched），按方法名和 id。

// Target 是能看着的一样东西：Method 是请求和推送的方法名，ID 是子代理（发起它的 Agent 调用）或后台任务。
type Target struct {
	Method, ID string
}

// SubagentTarget 是 Agent 调用 toolUseID 发起的子代理，看着时用 Subagent 取它的记录。
func SubagentTarget(toolUseID string) Target { return Target{"conv.subagent", toolUseID} }

// OutputTarget 是后台任务 task 的输出，看着时用 TaskOutput 取。
func OutputTarget(task string) Target { return Target{"conv.task_output", task} }

// idParams 是各方法里放 id 的参数名。
var idParams = map[string]string{"conv.subagent": "tool_use_id", "conv.task_output": "task"}

func (t Target) params(watch bool) map[string]any {
	return map[string]any{idParams[t.Method]: t.ID, "watch": watch}
}

// Watch 开始（watch 为真）或停止看着 t。
func (c *Conversation) Watch(ctx context.Context, t Target, watch bool) error {
	c.mu.Lock()
	if _, ok := c.watched[t]; watch && !ok {
		c.watched[t] = nil
	} else if !watch {
		delete(c.watched, t)
	}
	c.mu.Unlock()
	return c.call(ctx, t.Method, t.params(watch))
}

// watchedAs 是看着的 t 最近推来的样子（*T）：没在看着时 ok 为假，看着但还没推来时 v 为 nil。调用方持着 c.mu。
func watchedAs[T any](c *Conversation, t Target) (v *T, ok bool) {
	got, ok := c.watched[t]
	v, _ = got.(*T)
	return v, ok
}

// rewatch 在重新打开对话以后把看着的都重新看上（核心从整份推起）。
func (c *Conversation) rewatch(ctx context.Context) {
	c.mu.Lock()
	targets := slices.SortedFunc(maps.Keys(c.watched), func(a, b Target) int {
		return cmp.Or(cmp.Compare(a.Method, b.Method), cmp.Compare(a.ID, b.ID))
	})
	c.mu.Unlock()
	for _, t := range targets {
		params := t.params(true)
		params["conv"] = c.name
		if _, err := c.client.link.Call(ctx, t.Method, params); err != nil {
			log.Printf("chat: %s 重新看 %s %s 没成：%v", c.name, t.Method, t.ID, err)
		}
	}
}
