import Observation
import SwiftUI

/// 工具权限页的状态（同桌面版 permissions.go 的 permState）：每个状态、一键开关会加删的规则都由那台的核心算好，
/// 这里只把操作交过去、换上它答的新页面。在单个服务器页上时，带上那个服务器的工具让核心另算它们的状态。
@MainActor @Observable
final class PermissionsStore {
    let calls: ConfigCalls
    let cwd: String
    var data: PermissionsPage?
    var loadProblem = ""
    var working = false
    var failure: ConfigFailure?
    /// 点进去的那个服务器和它的工具（核心按它们算 serverTools）。
    var server: String?
    var serverTools: [McpTool]?

    init(calls: ConfigCalls, cwd: String) {
        self.calls = calls
        self.cwd = cwd
    }

    private var view: [String: any Sendable] {
        guard let server, let serverTools else { return ["cwd": cwd] }
        return ["cwd": cwd, "server": server, "tools": serverTools.map(\.name)]
    }

    func reload() { apply("config.permissions.read", [:]) }

    func setTool(_ rule: String, _ behavior: String) {
        apply("config.permissions.setTool", ["tool": rule, "behavior": behavior])
    }

    func bulk(server: String?, enable: Bool) {
        let group: [String: any Sendable] = if let server { ["server": server] } else {
            ["tools": data?.builtin.map(\.name) ?? []]
        }
        apply("config.permissions.bulk", ["group": group, "enable": enable])
    }

    func remove(_ rule: PermRule) {
        apply("config.permissions.remove", rule.params)
    }

    /// 加或改一条规则；成败交给 done（失败时页面不变）。
    func save(original: PermRule?, new rule: PermRule, done: @escaping @MainActor (String) -> Void) {
        if let original {
            apply("config.permissions.replace", ["old": original.params, "new": rule.params], done: done)
        } else {
            apply("config.permissions.add", rule.params, done: done)
        }
    }

    /// 在后台改规则（或只读），换上答回来的页面。done 不为 nil 时把出错的原因（成了是空串）交给它，为 nil 时出错弹出来。
    private func apply(_ method: String, _ params: [String: any Sendable], done: (@MainActor (String) -> Void)? = nil) {
        var all = view
        all.merge(params) { _, new in new }
        working = true
        Task {
            defer { working = false }
            do {
                data = try await calls.get(method, all, as: PermissionsPage.self)
                loadProblem = ""
                done?("")
            } catch {
                if let done {
                    done(error.localizedDescription)
                } else if data == nil {
                    loadProblem = error.localizedDescription
                } else {
                    failure = ConfigFailure(String(localized: "修改失败"), error)
                }
            }
        }
    }

    func serverView(_ name: String) -> ServerView? {
        if let focus = data?.focus, focus.name == name { return focus }
        return data?.servers.first { $0.name == name }
    }

    func serverTool(_ name: String) -> ToolView? {
        data?.serverTools.first { $0.name == name }
    }
}

enum PermLabels {
    static let choices = [""] + ConfigLabels.behaviors

    /// 一行的说明：有继承来的设置、带参数的规则时写这些，没有时写 fallback。
    static func summary(_ fallback: String, _ state: ToolState, fromServer: Bool) -> String {
        var parts: [String] = []
        if state.inherited, !state.behavior.isEmpty {
            let label = ConfigLabels.behavior(state.behavior)
            parts.append(fromServer ? String(localized: "跟随整个服务器：\(label)") : String(localized: "跟随：\(label)"))
        }
        if !state.specific.isEmpty {
            parts.append(String(localized: "另有 \(String(state.specific.count)) 条带参数的规则"))
        }
        return parts.isEmpty ? fallback : parts.joined(separator: " · ")
    }

    static func bulkTitle(server: String?, enable: Bool) -> String {
        switch (server, enable) {
        case (nil, true): String(localized: "全部开启内置工具")
        case (nil, false): String(localized: "全部关闭内置工具")
        case let (name?, true): String(localized: "全部开启 \(name) 的工具")
        case let (name?, false): String(localized: "全部关闭 \(name) 的工具")
        }
    }

    /// 确认框里的说明：按表列出会加、会删的规则；关掉全部内置工具时另外说清后果。
    static func bulkMessage(_ change: BulkChange, warn: Bool) -> String {
        var lines: [String] = []
        let separator = String(localized: "、")
        for behavior in ConfigLabels.behaviors {
            let rules = change.add.filter { $0.behavior == behavior }.map(\.rule)
            if !rules.isEmpty {
                let table = ConfigLabels.behavior(behavior)
                let count = String(rules.count), joined = rules.joined(separator: separator)
                lines.append(String(localized: "添加到「\(table)」（\(count) 条）：\(joined)"))
            }
        }
        for behavior in ConfigLabels.behaviors {
            let rules = change.remove.filter { $0.behavior == behavior }.map(\.rule)
            if !rules.isEmpty {
                let table = ConfigLabels.behavior(behavior)
                let count = String(rules.count), joined = rules.joined(separator: separator)
                lines.append(String(localized: "从「\(table)」移除（\(count) 条）：\(joined)"))
            }
        }
        if warn {
            lines.append("\n" + String(localized: "关闭后，Claude 将无法读写文件或执行命令，仅能对话与使用 MCP 工具。"))
        }
        return lines.joined(separator: "\n")
    }
}

/// 一个工具（或整个服务器）一行，右边选 未设置、允许、询问、拒绝；显示的是它自己的规则。
struct ToolPermissionRow: View {
    let store: PermissionsStore
    let title: String
    let summary: String
    let rule: String
    let own: String

    var body: some View {
        Picker(selection: Binding { own } set: { if $0 != own { store.setTool(rule, $0) } }) {
            ForEach(PermLabels.choices, id: \.self) { Text(ConfigLabels.behavior($0)).tag($0) }
        } label: {
            TitledRow(title: title, summary: summary)
        }
        .disabled(store.data == nil || store.working)
    }
}

/// 「全部开启」「全部关闭」一对按钮，点了先确认；已经全是那个状态时置灰。
struct BulkButtons: View {
    let store: PermissionsStore
    let server: String?
    let pair: BulkPair
    @State private var asking: Bool?

    var body: some View {
        HStack {
            Button("全部开启") { asking = true }
                .disabled(store.working || pair.enable.isEmpty)
            Spacer()
            Button("全部关闭", role: .destructive) { asking = false }
                .disabled(store.working || pair.disable.isEmpty)
        }
        .buttonStyle(.bordered)
        .confirmationDialog(
            PermLabels.bulkTitle(server: server, enable: asking ?? true) + String(localized: "？"),
            isPresented: Binding { asking != nil } set: { if !$0 { asking = nil } },
            titleVisibility: .visible, presenting: asking
        ) { enable in
            Button(enable ? String(localized: "全部开启") : String(localized: "全部关闭"), role: enable ? nil : .destructive) {
                store.bulk(server: server, enable: enable)
            }
        } message: { enable in
            Text(PermLabels.bulkMessage(enable ? pair.enable : pair.disable, warn: !enable && server == nil))
        }
    }
}
