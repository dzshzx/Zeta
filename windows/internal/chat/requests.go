package chat

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"maps"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/helper/tasks"
)

// 下面是用户对一个对话的操作，各发一个 conv.* 请求（参数见 docs/core.md「对话」）。
// 返回的错误是没发成或核心拒绝的原因；成了以后的效果经推送回来。

// Outgoing 是要发的一条消息。
type Outgoing struct {
	Text   string
	Images []Image
	// Files 是主机上的路径：本地的文件要先传到主机上。
	Files []string
}

// Send 发一条消息；回答进行中时核心让它排队。
func (c *Conversation) Send(ctx context.Context, m Outgoing) error {
	params := map[string]any{"text": m.Text}
	if len(m.Images) > 0 {
		params["images"] = m.Images
	}
	if len(m.Files) > 0 {
		params["files"] = m.Files
	}
	return c.call(ctx, "conv.send", params)
}

// Interrupt 停止这一轮，排队的消息撤回。
func (c *Conversation) Interrupt(ctx context.Context) error {
	return c.call(ctx, "conv.interrupt", nil)
}

// Steer 立即插话（终端里的 send now）：把排队的第一条马上送进这一轮。
func (c *Conversation) Steer(ctx context.Context) error {
	return c.call(ctx, "conv.steer", nil)
}

// Cancel 撤回一条排队的消息；recall 时放回输入框（State.Recalled）。
func (c *Conversation) Cancel(ctx context.Context, uuid string, recall bool) error {
	return c.call(ctx, "conv.cancel", map[string]any{"uuid": uuid, "recall": recall})
}

// TakeRecalled 取走撤回放回输入框的那条，输入框把它填进去。没有时返回 nil。
func (c *Conversation) TakeRecalled(ctx context.Context) (*Message, error) {
	result, err := c.callResult(ctx, "conv.take_recalled", nil)
	if err != nil || isNull(result) {
		return nil, err
	}
	var m Message
	if err := json.Unmarshal(result, &m); err != nil {
		return nil, fmt.Errorf("撤回的消息解不开：%w", err)
	}
	return &m, nil
}

// Answer 回应一个工具确认、计划审批或提问。
func (c *Conversation) Answer(ctx context.Context, requestID string, r prompt.Reply) error {
	return c.call(ctx, "conv.answer", map[string]any{"request_id": requestID, "reply": r})
}

// FormError 是 MCP 表单填得不对：键 → 那一项的问题。表单留着，改了再交。
type FormError map[string]string

func (e FormError) Error() string {
	keys := slices.Sorted(maps.Keys(e))
	parts := make([]string, len(keys))
	for i, k := range keys {
		parts[i] = k + "：" + e[k]
	}
	return "表单有几项不对：" + strings.Join(parts, "；")
}

// Elicit 回应 MCP 表单或网址。values 是表单里填的：键 → 输入框里的字，多选是选中的各值，开关是 "true"/"false"。
// 填得不对时返回 FormError。
func (c *Conversation) Elicit(ctx context.Context, requestID string, action prompt.ElicitAction, values map[string][]string) error {
	params := map[string]any{"request_id": requestID, "action": action}
	if values != nil {
		params["values"] = values
	}
	result, err := c.callResult(ctx, "conv.elicit", params)
	var problems struct {
		Errors FormError `json:"errors"`
	}
	if len(result) > 0 && json.Unmarshal(result, &problems) == nil && len(problems.Errors) > 0 {
		return problems.Errors
	}
	return err
}

// SetModel、SetEffort、SetMode 只管这个对话的模型、思考强度、权限模式；nil 回到默认。
func (c *Conversation) SetModel(ctx context.Context, v *string) error {
	return c.call(ctx, "conv.model", map[string]any{"value": v})
}

// SetEffort 见 SetModel。
func (c *Conversation) SetEffort(ctx context.Context, v *string) error {
	return c.call(ctx, "conv.effort", map[string]any{"value": v})
}

// SetMode 见 SetModel。
func (c *Conversation) SetMode(ctx context.Context, v *string) error {
	return c.call(ctx, "conv.mode", map[string]any{"value": v})
}

// SetOptions 改起进程用的设置，下次起进程时生效。
func (c *Conversation) SetOptions(ctx context.Context, o Options) error {
	c.mu.Lock()
	c.spec.Options = o
	c.mu.Unlock()
	return c.call(ctx, "conv.options", map[string]any{"options": o})
}

// Preset 给这个对话预设模型、思考强度，hint 追加进 --append-system-prompt，下次起进程时生效；空的那项照旧。
// 定时任务这类后台起的对话用它。
func (c *Conversation) Preset(ctx context.Context, model, effort, hint string) error {
	return c.call(ctx, "conv.preset", map[string]any{"model": model, "effort": effort, "hint": hint})
}

// Compact 压缩上下文。
func (c *Conversation) Compact(ctx context.Context) error {
	return c.call(ctx, "conv.compact", nil)
}

// StopTask 停一个后台任务。
func (c *Conversation) StopTask(ctx context.Context, task string) error {
	return c.call(ctx, "conv.stop_task", map[string]any{"task": task})
}

// ReadTaskOutput 读一个后台命令输出的最后至多 8 KiB，原样（含终端转义，显示前用 tasks.PlainTerminalText）；
// Error 不为空时是没读成的原因。
func (c *Conversation) ReadTaskOutput(ctx context.Context, task string) (tasks.Output, error) {
	var out tasks.Output
	result, err := c.callResult(ctx, "conv.task_output", map[string]any{"task": task})
	if err != nil {
		return out, err
	}
	if err := json.Unmarshal(result, &out); err != nil {
		return out, fmt.Errorf("后台任务的输出解不开：%w", err)
	}
	return out, nil
}

// End 结束 Claude Code 进程（等于终端里的 /exit）；对话内容留着，下次发消息时接上。
func (c *Conversation) End(ctx context.Context) error {
	return c.call(ctx, "conv.end", nil)
}

// Close 放开这个对话：核心里没人拿着、又闲着时会断开进程。之后这个 Conversation 不再收到推送，重连后也不再打开。
// 连接断着时不发：那条连接一断，核心那边就放开了。
func (c *Conversation) Close(ctx context.Context) error {
	c.client.forget(c)
	c.mu.Lock()
	connected := c.connected
	c.connected, c.opened = false, false
	c.mu.Unlock()
	if !connected {
		return nil
	}
	_, err := c.client.link.Call(ctx, "conv.close", map[string]any{"conv": c.name})
	return err
}

// errNotConnected：对话现在没连上（断线等重连、已关掉），请求不发。
var errNotConnected = errors.New("和主机的连接断了，正在重连")

func (c *Conversation) call(ctx context.Context, method string, params map[string]any) error {
	_, err := c.callResult(ctx, method, params)
	return err
}

func (c *Conversation) callResult(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
	c.mu.Lock()
	connected := c.connected
	c.mu.Unlock()
	if !connected {
		return nil, errNotConnected
	}
	if params == nil {
		params = map[string]any{}
	}
	params["conv"] = c.name
	return c.client.link.Call(ctx, method, params)
}

func isNull(v json.RawMessage) bool {
	s := strings.TrimSpace(string(v))
	return s == "" || s == "null"
}
