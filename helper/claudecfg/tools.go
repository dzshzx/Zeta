package claudecfg

import "github.com/dzshzx/Zeta/helper/locales"

// ToolInfo 是权限页上列出的一个工具：规则里写的名字、一句说明、带参数的写法举例。
// 说明是 Zeta 自己写的，只回给发请求的客户端：PermissionsView 按 Store 的语言（Store.In）填 Label。
type ToolInfo struct {
	Name    string `json:"name"`
	Label   string `json:"label"`
	Example string `json:"example"`

	said locales.Msg // Label 那句话
}

func tool(name string, said locales.Msg, example string) ToolInfo {
	return ToolInfo{Name: name, Example: example, said: said}
}

// in 是说明按语言 lang 填好的这一项。
func (t ToolInfo) in(lang string) ToolInfo {
	t.Label = t.said.In(lang)
	return t
}

// CommonTools 和 OtherTools 是 Claude Code 2.1.327 的内置工具（照 system init 的 tools 核对过）。不在这里的工具也能在
// “规则”里手写；以后 Claude Code 加了新工具，往这里补一行。
var CommonTools = []ToolInfo{
	tool("Bash", locales.M("toolBash"), "Bash(git push:*)"),
	tool("Read", locales.M("toolRead"), "Read(./.env)"),
	// 带路径的 Edit 规则对新建、覆盖文件（Write）同样生效
	tool("Edit", locales.M("toolEdit"), "Edit(src/**)"),
	tool("Write", locales.M("toolWrite"), ""),
	tool("NotebookEdit", locales.M("toolNotebookEdit"), ""),
	tool("WebFetch", locales.M("toolWebFetch"), "WebFetch(domain:github.com)"),
	tool("WebSearch", locales.M("toolWebSearch"), ""),
	tool("Agent", locales.M("toolAgent"), "Agent(Explore)"),
	tool("Skill", locales.M("toolSkill"), "Skill(pdf)"),
	tool("Glob", locales.M("toolGlob"), ""),
	tool("Grep", locales.M("toolGrep"), ""),
}

// OtherTools 是收起来的那些。
var OtherTools = []ToolInfo{
	tool("Monitor", locales.M("toolMonitor"), ""),
	tool("Workflow", locales.M("toolWorkflow"), ""),
	tool("CronCreate", locales.M("toolCronCreate"), ""),
	tool("CronDelete", locales.M("toolCronDelete"), ""),
	tool("CronList", locales.M("toolCronList"), ""),
	tool("ScheduleWakeup", locales.M("toolScheduleWakeup"), ""),
	tool("SendMessage", locales.M("toolSendMessage"), ""),
	tool("ListAgents", locales.M("toolListAgents"), ""),
	tool("TaskStop", locales.M("toolTaskStop"), ""),
	tool("EnterWorktree", locales.M("toolEnterWorktree"), ""),
	tool("ExitWorktree", locales.M("toolExitWorktree"), ""),
	tool("EnterPlanMode", locales.M("toolEnterPlanMode"), ""),
	tool("ExitPlanMode", locales.M("toolExitPlanMode"), ""),
	tool("AskUserQuestion", locales.M("toolAskUserQuestion"), ""),
	tool("ToolSearch", locales.M("toolToolSearch"), ""),
	tool("ReportFindings", locales.M("toolReportFindings"), ""),
	tool("DesignSync", locales.M("toolDesignSync"), ""),
}

// BuiltinGroup 是一键开启、关闭时的“内置工具”一组：常用的加上收起来的。
func BuiltinGroup() PermissionGroup {
	var names []string
	for _, t := range append(append([]ToolInfo{}, CommonTools...), OtherTools...) {
		names = append(names, t.Name)
	}
	return PermissionGroup{Tools: names}
}
