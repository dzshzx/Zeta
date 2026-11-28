package chatview

import (
	"encoding/json"
	"strings"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/hub/chat/items"
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
	if look, ok := agyLook(s); ok {
		return look
	}
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

func agyLook(s items.Step) (toolLook, bool) {
	switch s.Name {
	case "run_command":
		cmd := orDefault(s.Str("CommandLine"), s.Str("command"))
		title := firstNonBlank(s.Str("toolSummary"), s.Str("description"), firstLine(cmd, 80), locales.ChatToolRunCommand())
		return toolLook{iconTerminal, title, cmd}, true
	case "read_file", "view_file_outline", "view_code_item", "list_dir":
		path := firstNonBlank(s.Str("Path"), s.Str("DirectoryPath"), s.Str("File"), s.Str("path"), s.Str("file_path"))
		return toolLook{iconFile, locales.ChatToolRead(baseName(path)), orDefault(path, inputJSON(s))}, true
	case "write_file":
		path := firstNonBlank(s.Str("Path"), s.Str("path"), s.Str("file_path"))
		content := orDefault(s.Str("Content"), s.Str("content"))
		return toolLook{iconEdit, locales.ChatToolWrite(baseName(path)), path + "\n\n" + content}, true
	case "edit_file":
		return editLook(s), true
	case "grep_search":
		q := firstNonBlank(s.Str("Query"), s.Str("query"), s.Str("pattern"))
		return toolLook{iconSearch, locales.ChatToolGrep(firstLine(q, 40)), inputJSON(s)}, true
	case "find_by_name":
		p := firstNonBlank(s.Str("Pattern"), s.Str("pattern"))
		return toolLook{iconSearch, locales.ChatToolGlob(firstLine(p, 40)), inputJSON(s)}, true
	case "search_web":
		q := firstNonBlank(s.Str("query"), s.Str("Query"))
		return toolLook{iconGlobe, locales.ChatToolWebSearch(firstLine(q, 50)), q}, true
	case "read_url_content":
		url := firstNonBlank(s.Str("Url"), s.Str("url"))
		return toolLook{iconGlobe, locales.ChatToolWebFetch(hostOf(url)), url}, true
	case "invoke_subagent":
		task := firstNonBlank(s.Str("Role"), s.Str("description"), s.Str("TypeName"), s.Str("Prompt"), s.Str("prompt"))
		return toolLook{iconAgent, locales.ChatToolAgent(firstLine(task, 50)), orDefault(s.Str("Prompt"), s.Str("prompt"))}, true
	}
	return agyAuxLook(s)
}

func agyAuxLook(s items.Step) (toolLook, bool) {
	switch s.Name {
	case "ask_question":
		return toolLook{iconQuestion, locales.ChatToolAsk(), inputJSON(s)}, true
	case "update_steps":
		if steps, ok := s.Input["steps"].([]any); ok {
			return toolLook{iconChecklist, locales.ChatTodoWriteCount(len(steps)), inputJSON(s)}, true
		}
		return toolLook{iconChecklist, locales.ChatTodoWrite(), inputJSON(s)}, true
	case "manage_task":
		return toolLook{iconTerminal, locales.ChatToolManageTask(), inputJSON(s)}, true
	case "schedule":
		return toolLook{iconChecklist, locales.ChatToolSchedule(), inputJSON(s)}, true
	case "send_message":
		return toolLook{iconAgent, locales.ChatToolSendMessage(), inputJSON(s)}, true
	case "run_workflow":
		return toolLook{iconSparkle, locales.ChatToolWorkflow(), orDefault(s.Str("Description"), inputJSON(s))}, true
	case "call_mcp_tool":
		srv, tool := s.Str("ServerName"), s.Str("ToolName")
		if srv == "phone" {
			return mcpLook(items.Step{Name: "mcp__phone__" + tool, Input: s.Input}), true
		}
		if srv != "" && tool != "" {
			return toolLook{iconPlug, srv + " · " + tool, inputJSON(s)}, true
		}
		return toolLook{iconPlug, firstNonBlank(tool, srv, locales.ChatToolGeneric()), inputJSON(s)}, true
	}
	return toolLook{}, false
}

func firstNonBlank(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func editLook(s items.Step) toolLook {
	path := firstNonBlank(s.Str("file_path"), s.Str("notebook_path"), s.Str("Path"), s.Str("path"))
	detail := path
	if old, nw, ok := editPairOf(s); ok {
		detail += "\n\n- " + old + "\n+ " + nw
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
