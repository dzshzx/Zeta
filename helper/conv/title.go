package conv

import (
	"encoding/json"
	"regexp"
	"strings"

	"github.com/dzshzx/Zeta/helper/locales"
	"github.com/dzshzx/Zeta/helper/transcript"
)

// autoTitle 是新对话的自动标题，用 Claude Code 自己的标题功能：控制请求 generate_session_title 带 persist 时，
// 它用自己的快速小模型照它的提示词起一个短标题，追加一行 ai-title 到会话记录，并在应答里带回 title。
// 已经有自定义标题（改过名、分支）时直接返回那个。给它的内容不到 10 个字、或是斜杠命令时它返回空：等下一轮内容多了再试，最多试 3 轮。
//
// 只给 Zeta 里新开的对话起：第一次发消息时从第一句话截出临时标题（arm），之后标题被改过就不再起。出错、没起成都不提示，保留原来的标题。
type autoTitle struct {
	placeholder string // 临时标题；空是这个对话不再自动起标题
	attempts    int
	pending     bool
}

const (
	titleAttempts       = 3
	titleMaxDescription = 1500 // 交给 Claude Code 的内容最多这么多字（它自己也只看开头 2000 字）
	titleMinDescription = 10   // 少于 10 个字 Claude Code 不起标题
)

func (t *autoTitle) arm(placeholder string) { *t = autoTitle{placeholder: placeholder} }

// next：一轮正常结束时调用，该请求就返回要发的请求（request 一项）。标题已经不是临时标题了（改了名、Claude Code 报了新标题）就不再起。
func (t *autoTitle) next(enabled bool, title string, items []*transcript.Item) map[string]any {
	if t.placeholder == "" {
		return nil
	}
	if !enabled || title != t.placeholder || t.attempts >= titleAttempts {
		*t = autoTitle{}
		return nil
	}
	description := describe(items)
	if t.pending || len([]rune(description)) < titleMinDescription {
		return nil
	}
	t.attempts++
	t.pending = true
	return control("generate_session_title", "description", description, "persist", true)
}

// answered 处理应答，返回要换成的标题（不换时为空）。应答回来时标题已经被改了就不用它。
func (t *autoTitle) answered(r controlResponse, current string) string {
	t.pending = false
	expected := t.placeholder
	if !r.ok() || expected == "" {
		*t = autoTitle{}
		return ""
	}
	var body struct {
		Title *string `json:"title"`
	}
	_ = json.Unmarshal(r.Response, &body)
	if body.Title == nil || strings.TrimSpace(*body.Title) == "" {
		return ""
	}
	*t = autoTitle{}
	title := strings.TrimSpace(*body.Title)
	if current != expected || title == current {
		return ""
	}
	return title
}

// describe 是给起标题用的文字：提问和回答依次接起来，附件行、斜杠命令、思考和工具调用不要。
func describe(items []*transcript.Item) string {
	var parts []string
	for _, it := range items {
		var text string
		switch it.Kind {
		case transcript.KindUser:
			var kept []string
			for line := range strings.SplitSeq(it.Text, "\n") {
				if !strings.HasPrefix(line, "[Attached file: ") {
					kept = append(kept, line)
				}
			}
			// 斜杠命令开头的消息 Claude Code 不拿来起标题，整条跳过
			if text = strings.TrimSpace(strings.Join(kept, "\n")); strings.HasPrefix(text, "/") {
				text = ""
			}
		case transcript.KindAnswer:
			text = strings.TrimSpace(it.Text)
		}
		if text != "" {
			parts = append(parts, text)
		}
	}
	text := []rune(strings.Join(parts, "\n\n"))
	if len(text) <= titleMaxDescription {
		return string(text)
	}
	return strings.TrimSpace(string(text[:titleMaxDescription]))
}

// requestTitle：一轮正常结束后，新对话请 Claude Code 起标题。
func (c *Conversation) requestTitle() {
	if c.isAgy() {
		return
	}
	p := c.proc
	if p == nil {
		return
	}
	body := c.autoName.next(c.launchOptions().AutoTitle, c.title, c.r.Items)
	if body == nil {
		return
	}
	p.request(body, func(r controlResponse) {
		if title := c.autoName.answered(r, c.title); title != "" {
			c.title = title
			c.changed()
		}
	})
}

var (
	errRenameNoSession = locales.Err("errRenameNoSession")
	errRenameNoFile    = locales.Err("errRenameNoFile")
)

// rename 是用户给这个对话改名（conv.title、sessions.rename）：照 zeta-helper rename 往会话记录里追加一行 custom-title，
// 写成了才换标题，返回实际写下的标题。这是用户起的名字，之后不再自动起标题。还没有会话号的（新对话没发过消息、分支还没发出第一条）
// 没有会话记录可写，答出错，同 Kotlin 版只在会话列表里改名。
//
// 进程开着时另请 Claude Code 自己也改名（rename_session）：它把名字记在内存里，之后补写会话记录的元数据、
// 起标题的应答都用它记着的那个，不跟着改就可能把旧名字又写回去。
func (c *Conversation) rename(title string) (string, error) {
	if c.session == "" {
		return "", errRenameNoSession
	}
	var file string
	if c.deps.SessionFile != nil {
		file = c.deps.SessionFile(c.session)
	}
	if file == "" || c.deps.Rename == nil {
		return "", errRenameNoFile
	}
	written, err := c.deps.Rename(file, title)
	if err != nil {
		return "", err
	}
	c.title = written
	c.autoName = autoTitle{}
	if p := c.proc; p != nil {
		p.request(control("rename_session", "title", written), nil)
	}
	c.changed()
	return written, nil
}

var modelName = regexp.MustCompile(`^([A-Z][a-z]+) (\d+(?:\.\d{1,2})*)$`)

// modelIDOfName 把人看的模型名（Opus 5.5）换成完整的模型 ID（claude-opus-5-5）；认不出时为空。
func modelIDOfName(name string) string {
	m := modelName.FindStringSubmatch(name)
	if m == nil {
		return ""
	}
	return "claude-" + strings.ToLower(m[1]) + "-" + strings.ReplaceAll(m[2], ".", "-")
}
