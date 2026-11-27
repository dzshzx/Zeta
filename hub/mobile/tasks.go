package mobile

import (
	"context"
	"strings"

	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/hub/locales"
	"github.com/egoist/mygo/i18n"
)

// 聊天页里对话的后台任务、子代理和任务清单（同桌面版 taskpages.go、subagent.go、todostrip.go）：任务和清单跟着 state 交，
// 任务的输出、子代理的记录要先 watch 看着，核心推来后用 taskoutput、subagent 取（界面每次状态变了取一次），不看了再 watch 关掉。

// Todo 是任务清单的一项，字已按界面语言出好；Status 是 pending、in_progress、completed。
type Todo struct {
	Key        string `json:"key"`
	Content    string `json:"content"`
	ActiveForm string `json:"active_form"`
	Status     string `json:"status"`
}

// chatExtras 补上聊天页的任务、清单和这期间答完的各轮（chatState 里调）。
func (c *Core) chatExtras(out *Chat, u chat.Update, locale string) {
	s := u.State
	out.Process = s.Process
	out.Tasks = s.Tasks
	if out.Tasks == nil {
		out.Tasks = []tasks.Task{}
	}
	out.Todos = []Todo{}
	for _, t := range s.Todos {
		out.Todos = append(out.Todos, Todo{Key: t.Key, Content: t.ContentIn(locale), ActiveForm: t.ActiveFormIn(locale), Status: t.Status})
	}
	out.Finished = []string{}
	for _, t := range u.Turns {
		if t.Phase == chat.TurnFinished {
			out.Finished = append(out.Finished, t.Said(locale))
		}
	}
}

type (
	taskParams struct {
		Task string `json:"task"`
	}
	watchParams struct {
		// Kind 是 output（后台命令的输出，ID 是任务）或 subagent（ID 是发起它的 tool_use_id）。
		Kind string `json:"kind"`
		ID   string `json:"id"`
		On   bool   `json:"on"`
	}
	agentParams struct {
		ToolUseID string `json:"tool_use_id"`
	}
)

// TaskOutput 是 taskoutput 答的：Got 为假是还没推来（或没在看着）；Text 已去掉终端转义。
type TaskOutput struct {
	Got        bool   `json:"got"`
	Text       string `json:"text"`
	TotalBytes int64  `json:"total_bytes"`
	Truncated  bool   `json:"truncated"`
	Error      string `json:"error"`
}

// SubagentPage 是 subagent 答的：chat.Subagent 的字段，条目整份（Rev 没变时界面不用换）。
type SubagentPage struct {
	chat.Subagent
	Items   []items.Item `json:"items"`
	Rev     int          `json:"rev"`
	Watched bool         `json:"watched"`
}

func init() {
	inChat("stoptask", func(p taskParams) (func(ctx context.Context, conv *chat.Conversation) error, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.StopTask(ctx, p.Task) }, nil
	})
	inChat("watch", func(p watchParams) (func(ctx context.Context, conv *chat.Conversation) error, error) {
		var target chat.Target
		switch p.Kind {
		case "output":
			target = chat.OutputTarget(p.ID)
		case "subagent":
			target = chat.SubagentTarget(p.ID)
		default:
			return nil, errUnknown
		}
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.Watch(ctx, target, p.On) }, nil
	})
	reading("taskoutput", func(c *Core, p taskParams) (any, error) {
		conv, err := c.conv()
		if err != nil {
			return nil, err
		}
		out, got := conv.TaskOutput(p.Task)
		return TaskOutput{Got: got, Text: strings.TrimRight(tasks.PlainTerminalText(out.Text), " \t\r\n"), TotalBytes: out.TotalBytes,
			Truncated: out.Truncated, Error: out.ErrorIn(i18n.Locale())}, nil
	})
	reading("subagent", func(c *Core, p agentParams) (any, error) {
		conv, err := c.conv()
		if err != nil {
			return nil, err
		}
		s, watched := conv.Subagent(p.ToolUseID)
		list := s.Items
		if list == nil {
			list = []items.Item{}
		}
		return SubagentPage{Subagent: s, Items: said(list, i18n.Locale()), Rev: s.Rev, Watched: watched}, nil
	})
}

// conv 是聊天页里的对话。
func (c *Core) conv() (*chat.Conversation, error) {
	if ch := c.hub.Chat(); ch != nil && ch.Conv != nil {
		return ch.Conv, nil
	}
	return nil, locales.Err(locales.MobileChatNotOpen)
}
