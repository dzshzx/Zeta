import Foundation

/// 对话工具（Claude Code 或 Antigravity CLI）。
enum Harness {
    static let claude = "claude"
    static let agy = "agy"
    static let all = [claude, agy]

    static func name(_ value: String) -> String {
        value == agy ? "Antigravity" : "Claude Code"
    }
}

/// 工具调用的显示名、SF Symbols 图标和参数摘要（覆盖 Claude Code 与 Antigravity CLI 内置工具及 MCP 工具）。
enum ToolLabels {
    static func isSubagent(_ name: String) -> Bool {
        name == "Agent" || name == "Task" || name == "invoke_subagent"
    }

    static func title(_ name: String) -> String {
        if let label = agyTitles[name] { return label }
        if name.hasPrefix("mcp__") {
            let parts = name.dropFirst(5).split(separator: "__", maxSplits: 1)
            if parts.count == 2 { return "\(parts[0]) · \(parts[1])" }
        }
        return name
    }

    static func icon(_ name: String) -> String {
        if let symbol = symbols[name] { return symbol }
        if name.hasPrefix("mcp__") { return "puzzlepiece.extension" }
        return "wrench.and.screwdriver"
    }

    static func command(_ input: JSONValue?) -> String? {
        nonEmpty(input?["command"]?.string) ?? nonEmpty(input?["CommandLine"]?.string)
    }

    static func summary(name: String, input: JSONValue?) -> String {
        guard let input else { return "" }
        if let special = specialSummary(name: name, input: input) { return special }
        return input.summary ?? ""
    }

    private static func specialSummary(name: String, input: JSONValue) -> String? {
        switch name {
        case "read_file":
            return readFileSummary(input)
        case "grep_search":
            return joined([input["Query"]?.string, input["SearchPath"]?.string])
        case "find_by_name":
            return joined([input["Pattern"]?.string, input["SearchDirectory"]?.string])
        case "invoke_subagent":
            return joined([input["TypeName"]?.string, input["Role"]?.string]) ?? nonEmpty(input["Prompt"]?.string)
        case "manage_task", "manage_subagents":
            return joined([input["Action"]?.string, input["TaskId"]?.string])
        case "call_mcp_tool", "list_resources", "read_resource":
            return joined([input["ServerName"]?.string, input["ToolName"]?.string, input["Uri"]?.string])
        case "ask_question", "AskUserQuestion":
            return firstQuestion(input)
        case "update_steps":
            return stepsSummary(input)
        default:
            return nil
        }
    }

    private static func readFileSummary(_ input: JSONValue) -> String? {
        guard let path = nonEmpty(input["Path"]?.string) else { return nil }
        if let start = input["StartLine"]?.int, let end = input["EndLine"]?.int {
            return "\(path)#L\(start)-\(end)"
        }
        if let start = input["StartLine"]?.int {
            return "\(path)#L\(start)"
        }
        return path
    }

    private static func firstQuestion(_ input: JSONValue) -> String? {
        guard let list = input["questions"]?.array, let first = list.first else { return nil }
        return nonEmpty(first["question"]?.string)
    }

    private static func stepsSummary(_ input: JSONValue) -> String? {
        guard let steps = input["steps"]?.array, !steps.isEmpty else { return nil }
        if let active = steps.first(where: { $0["status"]?.string == "in_progress" }),
            let title = nonEmpty(active["title"]?.string) {
            return title
        }
        return String(localized: "\(steps.count) 个步骤")
    }

    private static func nonEmpty(_ text: String?) -> String? {
        guard let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return trimmed
    }

    private static func joined(_ parts: [String?]) -> String? {
        let items = parts.compactMap(nonEmpty)
        return items.isEmpty ? nil : items.joined(separator: " · ")
    }

    private static var agyTitles: [String: String] {
        [
            "run_command": String(localized: "运行命令"),
            "read_file": String(localized: "读取文件"),
            "view_file": String(localized: "读取文件"),
            "view_file_outline": String(localized: "查看大纲"),
            "view_code_item": String(localized: "查看符号"),
            "edit_file": String(localized: "编辑文件"),
            "replace_file_content": String(localized: "编辑文件"),
            "multi_replace_file_content": String(localized: "编辑文件"),
            "write_file": String(localized: "写入文件"),
            "write_to_file": String(localized: "写入文件"),
            "grep_search": String(localized: "搜索内容"),
            "find_by_name": String(localized: "查找文件"),
            "list_dir": String(localized: "列出目录"),
            "search_web": String(localized: "网页搜索"),
            "read_url_content": String(localized: "读取网页"),
            "invoke_subagent": String(localized: "调用子代理"),
            "define_subagent": String(localized: "定义子代理"),
            "manage_subagents": String(localized: "管理子代理"),
            "send_message": String(localized: "发送消息"),
            "ask_question": String(localized: "提问"),
            "exit_plan_mode": String(localized: "提交计划"),
            "swarm": String(localized: "多代理协作"),
            "update_steps": String(localized: "更新步骤"),
            "manage_task": String(localized: "管理任务"),
            "command_status": String(localized: "命令状态"),
            "schedule": String(localized: "定时计划"),
            "run_workflow": String(localized: "运行工作流"),
            "call_mcp_tool": String(localized: "MCP 工具"),
            "list_resources": String(localized: "列出资源"),
            "read_resource": String(localized: "读取资源")
        ]
    }

    private static let symbols: [String: String] = [
        "run_command": "terminal",
        "Bash": "terminal",
        "read_file": "doc.text",
        "view_file": "doc.text",
        "view_file_outline": "list.bullet.indent",
        "view_code_item": "doc.text.magnifyingglass",
        "Read": "doc.text",
        "edit_file": "pencil",
        "replace_file_content": "pencil",
        "multi_replace_file_content": "pencil",
        "Edit": "pencil",
        "NotebookEdit": "pencil",
        "write_file": "square.and.pencil",
        "write_to_file": "square.and.pencil",
        "Write": "square.and.pencil",
        "grep_search": "text.magnifyingglass",
        "Grep": "text.magnifyingglass",
        "find_by_name": "folder.badge.magnifyingglass",
        "list_dir": "folder",
        "Glob": "folder.badge.magnifyingglass",
        "search_web": "globe",
        "read_url_content": "globe",
        "WebSearch": "globe",
        "WebFetch": "globe",
        "invoke_subagent": "person.2",
        "define_subagent": "person.badge.plus",
        "manage_subagents": "person.2.badge.gearshape",
        "Agent": "person.2",
        "Task": "person.2",
        "send_message": "paperplane",
        "SendMessage": "paperplane",
        "ask_question": "questionmark.bubble",
        "AskUserQuestion": "questionmark.bubble",
        "exit_plan_mode": "checklist",
        "swarm": "person.3",
        "update_steps": "checklist",
        "TodoWrite": "checklist",
        "manage_task": "timer",
        "command_status": "timer",
        "Monitor": "timer",
        "TaskOutput": "timer",
        "TaskStop": "stop.circle",
        "schedule": "calendar.badge.clock",
        "CronCreate": "calendar.badge.clock",
        "CronDelete": "calendar.badge.minus",
        "CronList": "calendar",
        "run_workflow": "point.3.connected.trianglepath.dotted",
        "Skill": "sparkles",
        "call_mcp_tool": "puzzlepiece.extension",
        "list_resources": "puzzlepiece.extension",
        "read_resource": "puzzlepiece.extension"
    ]
}
