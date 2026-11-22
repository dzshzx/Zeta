package claudecfg

// ToolInfo 是权限页上列出的一个工具：规则里写的名字、一句中文说明、带参数的写法举例。
type ToolInfo struct {
	Name    string `json:"name"`
	Label   string `json:"label"`
	Example string `json:"example"`
}

// CommonTools 和 OtherTools 是 Claude Code 2.1.327 的内置工具（照 system init 的 tools 核对过）。不在这里的工具也能在
// “规则”里手写；以后 Claude Code 加了新工具，往这里补一行。
var CommonTools = []ToolInfo{
	{"Bash", "运行命令", "Bash(git push:*)"},
	{"Read", "读文件，也管按名字找文件和搜内容", "Read(./.env)"},
	// 带路径的 Edit 规则对新建、覆盖文件（Write）同样生效
	{"Edit", "修改文件；带路径的规则也管 Write", "Edit(src/**)"},
	{"Write", "新建或覆盖文件", ""},
	{"NotebookEdit", "修改 Jupyter 笔记本", ""},
	{"WebFetch", "抓取网页", "WebFetch(domain:github.com)"},
	{"WebSearch", "网页搜索", ""},
	{"Agent", "派出 Subagent（旧名 Task）", "Agent(Explore)"},
	{"Skill", "调用 Skill", "Skill(pdf)"},
	{"Glob", "按文件名找文件（Bash 被拒时才有）", ""},
	{"Grep", "在文件里搜内容（Bash 被拒时才有）", ""},
}

// OtherTools 是收起来的那些。
var OtherTools = []ToolInfo{
	{"Monitor", "盯着后台命令的输出", ""},
	{"Workflow", "运行编排多个 Subagent 的工作流脚本", ""},
	{"CronCreate", "新建定时任务", ""},
	{"CronDelete", "删除定时任务", ""},
	{"CronList", "列出定时任务", ""},
	{"ScheduleWakeup", "/loop 里过一会儿再接着做", ""},
	{"SendMessage", "给 Subagent 或别的会话发消息", ""},
	{"ListAgents", "列出能发消息的 Subagent 和会话", ""},
	{"TaskStop", "停掉后台任务", ""},
	{"EnterWorktree", "进入 git worktree", ""},
	{"ExitWorktree", "离开 git worktree", ""},
	{"EnterPlanMode", "进入计划模式", ""},
	{"ExitPlanMode", "交出计划、退出计划模式", ""},
	{"AskUserQuestion", "向你提选择题", ""},
	{"ToolSearch", "按需加载其余工具，拒绝后都用不了", ""},
	{"ReportFindings", "按格式汇报代码审查的发现", ""},
	{"DesignSync", "读写 Claude Design 里的项目文件", ""},
}

// BuiltinGroup 是一键开启、关闭时的“内置工具”一组：常用的加上收起来的。
func BuiltinGroup() PermissionGroup {
	var names []string
	for _, t := range append(append([]ToolInfo{}, CommonTools...), OtherTools...) {
		names = append(names, t.Name)
	}
	return PermissionGroup{Tools: names}
}
