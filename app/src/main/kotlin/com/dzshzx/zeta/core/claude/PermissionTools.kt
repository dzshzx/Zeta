package com.dzshzx.zeta.core.claude

/** 权限页上列出的一个工具：规则里写的名字、一句中文说明、带参数的写法举例。 */
data class ToolInfo(val name: String, val label: String, val example: String? = null)

/**
 * Claude Code 2.1.327 的内置工具（照 `system init` 的 tools 和发给模型的工具清单核对过）。
 * 不在这里的工具也能在“规则”里手写；以后 Claude Code 加了新工具，往这里补一行。
 */
object BuiltinTools {
    val common = listOf(
        ToolInfo("Bash", "运行命令", "Bash(git push:*)"),
        ToolInfo("Read", "读文件，也管按名字找文件和搜内容", "Read(./.env)"),
        ToolInfo("Edit", "修改文件；带路径的规则对新建、覆盖文件同样生效", "Edit(src/**)"),
        ToolInfo("Write", "新建或覆盖文件"),
        ToolInfo("NotebookEdit", "修改 Jupyter 笔记本"),
        ToolInfo("WebFetch", "抓取网页", "WebFetch(domain:github.com)"),
        ToolInfo("WebSearch", "网页搜索"),
        ToolInfo("Agent", "派出 Subagent（旧名 Task）", "Agent(Explore)"),
        ToolInfo("Skill", "调用 Skill", "Skill(pdf)"),
        ToolInfo("Glob", "按文件名找文件（Bash 被拒绝时才有）"),
        ToolInfo("Grep", "在文件里搜内容（Bash 被拒绝时才有）"),
    )

    val other = listOf(
        ToolInfo("Monitor", "盯着后台命令的输出"),
        ToolInfo("Workflow", "运行编排多个 Subagent 的工作流脚本"),
        ToolInfo("CronCreate", "新建定时任务"),
        ToolInfo("CronDelete", "删除定时任务"),
        ToolInfo("CronList", "列出定时任务"),
        ToolInfo("ScheduleWakeup", "/loop 里过一会儿再接着做"),
        ToolInfo("SendMessage", "给 Subagent 或别的会话发消息"),
        ToolInfo("ListAgents", "列出能发消息的 Subagent 和会话"),
        ToolInfo("TaskStop", "停掉后台任务"),
        ToolInfo("EnterWorktree", "进入 git worktree"),
        ToolInfo("ExitWorktree", "离开 git worktree"),
        ToolInfo("EnterPlanMode", "进入计划模式"),
        ToolInfo("ExitPlanMode", "交出计划、退出计划模式"),
        ToolInfo("AskUserQuestion", "向你提选择题"),
        ToolInfo("ToolSearch", "按需加载其余工具；拒绝它，延后加载的工具都用不了"),
        ToolInfo("ReportFindings", "按格式汇报代码审查的发现"),
        ToolInfo("DesignSync", "读写 Claude Design 里的项目文件"),
    )

    /** 一键开启、关闭时的“内置工具”一组：常用的加上收起来的。 */
    val group: PermissionGroup get() = PermissionGroup.Tools((common + other).map { it.name })
}

/**
 * Zeta 手机工具（MCP 服务器 phone）的中文说明；没写到的显示英文说明的第一句。
 * 加了新的手机工具就在这里补一行，PermissionsTest 核对 PhoneTools.catalog 里每个工具都有。
 */
object PhoneToolLabels {
    private val labels = mapOf(
        "screen" to "读屏：前台应用和可点的控件，可带截图",
        "tap" to "点按",
        "long_press" to "长按",
        "swipe" to "滑动",
        "scroll" to "滚动列表",
        "type_text" to "往输入框里输入文字",
        "press_key" to "按系统键（返回、主页等）",
        "wait" to "等一会儿、等某个控件出现或某个应用到前台，再读屏",
        "open_app" to "打开应用、链接或设置页，默认在后台屏上",
        "show_to_user" to "把后台屏上的应用挪到主屏给你看",
        "find_apps" to "查找已装的应用",
        "shell" to "以 root 在 Android 一侧执行命令",
        "device_state" to "查看或切换手机开关和档位（WLAN、蓝牙、亮度等）",
        "media" to "音量、响铃模式、音频输出，控制正在播放的媒体",
        "clock" to "时钟应用的闹钟和倒计时",
        "system_setting" to "读写 Android 系统设置（settings 的 system、secure、global 表）",
        "app_control" to "已装应用的信息、强行停止、冻结与解冻、使用时长",
        "device_info" to "电池、温度、内存、存储、网络、位置等手机状况",
        "logcat" to "读最近的 Android 日志",
        "search_personal" to "搜索日历、通知、验证码、短信、通话、通讯录、相册（只读）",
        "view_media" to "查看照片、截图或视频",
        "edit_calendar" to "新建、修改、删除日程",
        "clipboard" to "读写剪贴板",
        "create_scheduled_task" to "新建定时任务：到点让 Claude 在后台做一件事",
        "list_scheduled_tasks" to "列出定时任务",
        "delete_scheduled_task" to "删除定时任务",
        "browser" to "Zeta 内置浏览器：在屏幕外打开网页、点按、输入、读内容",
    )

    fun label(name: String, description: String): String =
        labels[name] ?: description.substringBefore(". ").substringBefore('\n').trim().take(80)
}
