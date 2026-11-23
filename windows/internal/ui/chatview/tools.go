package chatview

import (
	"encoding/json"
	"strings"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/egoist/mygo/ui"
)

// toolLook 是一次工具调用在列表里的样子：图标、一行标题、展开后先显示的参数说明（结果接在它后面）。
type toolLook struct {
	icon   *ui.SVG
	title  string
	detail string
}

// maxResultRunes：展开后结果最多显示这么多字，再多的截掉。
const maxResultRunes = 3000

// lookOf 照工具名挑标题和说明，和手机版列表里的说法一样。
func lookOf(s items.Step) toolLook {
	switch s.Name {
	case "Bash":
		title := s.Str("description")
		if strings.TrimSpace(title) == "" {
			title = orDefault(firstLine(s.Str("command"), 80), locales.ChatToolRunCommand())
		}
		return toolLook{iconTerminal, title, s.Str("command")}
	case "Read":
		return toolLook{iconFile, locales.ChatToolRead(baseName(s.Str("file_path"))), s.Str("file_path")}
	case "Write":
		return toolLook{iconEdit, locales.ChatToolWrite(baseName(s.Str("file_path"))), s.Str("file_path") + "\n\n" + s.Str("content")}
	case "Edit", "MultiEdit", "NotebookEdit":
		return editLook(s)
	case "Grep":
		return toolLook{iconSearch, locales.ChatToolGrep(firstLine(s.Str("pattern"), 40)), inputJSON(s)}
	case "Glob":
		return toolLook{iconSearch, locales.ChatToolGlob(firstLine(s.Str("pattern"), 40)), inputJSON(s)}
	case "WebSearch":
		return toolLook{iconGlobe, locales.ChatToolWebSearch(firstLine(s.Str("query"), 50)), s.Str("query")}
	case "WebFetch":
		return toolLook{iconGlobe, locales.ChatToolWebFetch(hostOf(s.Str("url"))), s.Str("url") + "\n\n" + s.Str("prompt")}
	case "Task", "Agent":
		return toolLook{iconAgent, locales.ChatToolAgent(firstLine(orDefault(s.Str("description"), s.Str("prompt")), 50)), s.Str("prompt")}
	case "TodoWrite", "TaskCreate", "TaskUpdate", "TaskList", "TaskGet":
		return toolLook{iconChecklist, todoTitle(s), inputJSON(s)}
	case "AskUserQuestion":
		return toolLook{iconQuestion, locales.ChatToolAsk(), inputJSON(s)}
	case "ExitPlanMode":
		return toolLook{iconChecklist, locales.ChatToolPlan(), orDefault(s.Str("plan"), inputJSON(s))}
	case "Skill":
		return toolLook{iconSparkle, locales.ChatToolSkill(orDefault(s.Str("skill"), s.Str("command"))), inputJSON(s)}
	}
	return mcpLook(s)
}

func editLook(s items.Step) toolLook {
	path := orDefault(s.Str("file_path"), s.Str("notebook_path"))
	detail := path
	if old, ok := s.Input["old_string"].(string); ok {
		detail += "\n\n- " + old + "\n+ " + s.Str("new_string")
	}
	return toolLook{iconEdit, locales.ChatToolEdit(baseName(path)), detail}
}

// mcpLook：MCP 服务器的工具写成「服务器 · 工具」，手机工具（mcp__phone__…，手机上起的对话里会有）写成「手机 · 工具」，
// 别的照工具名。
func mcpLook(s items.Step) toolLook {
	if tool, ok := strings.CutPrefix(s.Name, "mcp__phone__"); ok {
		if tool == "shell" {
			return toolLook{iconTerminal, locales.ChatToolPhoneShell(), s.Str("command")}
		}
		return toolLook{iconPhone, locales.ChatToolPhone(tool), inputJSON(s)}
	}
	if rest, ok := strings.CutPrefix(s.Name, "mcp__"); ok {
		return toolLook{iconPlug, strings.Join(strings.SplitN(rest, "__", 2), " · "), inputJSON(s)}
	}
	return toolLook{iconWrench, orDefault(s.Name, locales.ChatToolGeneric()), inputJSON(s)}
}

// todoTitle：任务清单类工具的标题。
func todoTitle(s items.Step) string {
	id := orDefault(s.Str("taskId"), s.Str("task_id"))
	switch s.Name {
	case "TaskCreate":
		return locales.ChatTodoCreate(orDefault(s.Str("subject"), s.Str("title")))
	case "TaskUpdate":
		switch s.Str("status") {
		case "in_progress":
			return locales.ChatTodoStart(id)
		case "completed":
			return locales.ChatTodoComplete(id)
		case "deleted":
			return locales.ChatTodoDelete(id)
		}
		return locales.ChatTodoUpdate(id)
	case "TaskList":
		return locales.ChatTodoList()
	case "TaskGet":
		return locales.ChatTodoGet(id)
	}
	if todos, ok := s.Input["todos"].([]any); ok {
		return locales.ChatTodoWriteCount(len(todos))
	}
	return locales.ChatTodoWrite()
}

// stepDetail 是一步工具调用展开后的文字：参数说明，空一行接结果（太长的截掉）。
func stepDetail(look toolLook, result string) string {
	var b strings.Builder
	b.WriteString(strings.TrimSpace(look.detail))
	if r := strings.TrimSpace(result); r != "" {
		if b.Len() > 0 {
			b.WriteString("\n\n")
		}
		if utf8.RuneCountInString(r) > maxResultRunes {
			r = string([]rune(r)[:maxResultRunes]) + "\n…"
		}
		b.WriteString(r)
	}
	return b.String()
}

func inputJSON(s items.Step) string {
	if s.Input == nil {
		return ""
	}
	out, err := json.MarshalIndent(s.Input, "", "  ")
	if err != nil {
		return ""
	}
	return string(out)
}

// firstLine 是第一行不空的文字，超过 limit 个字时截断加省略号。
func firstLine(text string, limit int) string {
	for line := range strings.Lines(text) {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if utf8.RuneCountInString(line) > limit {
			return string([]rune(line)[:limit]) + "…"
		}
		return line
	}
	return ""
}

// baseName 是路径的最后一段（结尾的 / 不算）。
func baseName(path string) string {
	path = strings.TrimRight(path, "/")
	return path[strings.LastIndexByte(path, '/')+1:]
}

func hostOf(url string) string {
	if _, rest, ok := strings.Cut(url, "://"); ok {
		url = rest
	}
	host, _, _ := strings.Cut(url, "/")
	return host
}

func orDefault(s, def string) string {
	if strings.TrimSpace(s) == "" {
		return def
	}
	return s
}

// DescribeTool 是一次工具调用（工具名 name、参数 input）的一行标题和说明，和列表里的说法一样；工具确认用。
func DescribeTool(name string, input map[string]any) (title, detail string) {
	look := lookOf(items.Step{Kind: items.Tool, Name: name, Input: input})
	return look.title, look.detail
}
