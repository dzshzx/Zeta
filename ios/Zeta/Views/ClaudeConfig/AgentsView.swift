import SwiftUI

/// Subagent（同桌面版 agents.go）：~/.claude/agents 下每个 .md 是一个，前言写名称、什么时候用、模型、工具，正文是系统提示词。
struct AgentsView: View {
    let calls: ConfigCalls
    @State private var list: [Subagent] = []
    @State private var loaded = false
    @State private var failure: ConfigFailure?

    var body: some View {
        Form {
            Section {
                if !loaded {
                    LoadingRow()
                } else if list.isEmpty {
                    TitledRow(
                        title: String(localized: "还没有自定义 Subagent"),
                        summary: String(localized: "让 Claude 写一个，或点右上角的“新建”"))
                }
                ForEach(list, id: \.file) { agent in
                    NavigationLink {
                        AgentEditView(calls: calls, original: agent, changed: reload)
                    } label: {
                        LabeledContent {
                            Text(AgentDraft.modelLabel(agent.model))
                        } label: {
                            TitledRow(
                                title: agent.name.isEmpty ? agent.file : agent.name,
                                summary: ConfigLabels.clip(agent.description, 90))
                        }
                    }
                }
            } header: {
                Text("自定义 Subagent · \(String(list.count))")
            } footer: {
                Text("存在 ~/.claude/agents，所有项目都能用。Claude 按“什么时候用”自己决定交给哪个，你也可以在对话里点名。")
            }
        }
        .navigationTitle("Subagent")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink {
                    AgentEditView(calls: calls, original: nil, changed: reload)
                } label: {
                    Label("新建 Subagent", systemImage: "plus")
                }
            }
        }
        .task { await load() }
        .refreshable { await load() }
        .configFailure($failure)
    }

    private func reload() { Task { await load() } }

    private func load() async {
        do {
            list = try await calls.get("config.agents.list", as: ListOf<Subagent>.self).items
            loaded = true
        } catch {
            failure = ConfigFailure(String(localized: "读取 Subagent 失败"), error)
        }
    }
}

/// 编辑页上的内容；tools 只在 allTools 为假时用。
struct AgentDraft: Equatable {
    var name = ""
    var description = ""
    var model = ""
    var prompt = ""
    var allTools = true
    var checked: Set<String> = []
    var others = ""

    /// 模型的选项：不写时跟随主对话的模型。
    static let models = ["", "inherit", "sonnet", "opus", "haiku", "fable"]

    /// 常用的内置工具；别的（比如 MCP 工具）写进「其他工具」。
    static let commonTools = [
        "Read", "Edit", "Write", "Glob", "Grep", "Bash", "WebFetch", "WebSearch", "NotebookEdit", "Skill"
    ]

    init() {}

    init(_ agent: Subagent) {
        name = agent.name
        description = agent.description
        model = agent.model
        prompt = agent.prompt
        allTools = agent.tools == nil
        let tools = agent.tools ?? []
        checked = Set(tools.filter(Self.commonTools.contains))
        others = tools.filter { !Self.commonTools.contains($0) }.joined(separator: ", ")
    }

    /// 要写进文件的工具：勾选的常用工具按固定顺序，再加「其他工具」里逗号分开的；nil 是全部工具。
    var tools: [String]? {
        guard !allTools else { return nil }
        let extra = others.split { $0 == "," || $0 == "，" }.map { $0.trimmingCharacters(in: .whitespaces) }
        return Self.commonTools.filter(checked.contains) + extra.filter { !$0.isEmpty }
    }

    /// 保存前还差什么；不差时为空。
    var missing: String {
        if name.trimmingCharacters(in: .whitespaces).wholeMatch(of: /[a-z0-9][a-z0-9-]{0,63}/) == nil {
            return String(localized: "名称只能用小写字母、数字和连字符，并以字母或数字开头")
        }
        if description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return String(localized: "写上什么时候用它才能保存")
        }
        return ""
    }

    func same(as other: AgentDraft) -> Bool {
        name == other.name && description == other.description && model == other.model && prompt == other.prompt
            && tools == other.tools
    }

    var params: [String: any Sendable] {
        var out: [String: any Sendable] = [
            "name": name.trimmingCharacters(in: .whitespaces),
            "description": description.trimmingCharacters(in: .whitespacesAndNewlines),
            "model": model, "prompt": prompt
        ]
        if let tools { out["tools"] = tools }
        return out
    }

    static func modelLabel(_ model: String) -> String {
        switch model.lowercased() {
        case "": String(localized: "默认")
        case "inherit": String(localized: "跟随主对话")
        case "sonnet": "Sonnet"
        case "opus": "Opus"
        case "haiku": "Haiku"
        case "fable": "Fable"
        default: model
        }
    }

    static func toolLabel(_ tool: String) -> String {
        let labels = [
            "Read": String(localized: "读文件"),
            "Edit": String(localized: "修改文件"),
            "Write": String(localized: "新建、覆盖文件"),
            "Glob": String(localized: "按文件名查找"),
            "Grep": String(localized: "在文件里搜索"),
            "Bash": String(localized: "执行命令"),
            "WebFetch": String(localized: "抓取网页"),
            "WebSearch": String(localized: "网络搜索"),
            "NotebookEdit": String(localized: "编辑 Jupyter 笔记本"),
            "Skill": String(localized: "使用 Skill")
        ]
        return labels[tool] ?? tool
    }
}

/// 新建或编辑一个 Subagent；original 为 nil 是新建。
struct AgentEditView: View {
    let calls: ConfigCalls
    let original: Subagent?
    let changed: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var draft = AgentDraft()
    @State private var started = false
    @State private var saving = false
    @State private var problem = ""
    @State private var leaving = false
    @State private var deleting = false
    @State private var failure: ConfigFailure?

    private var dirty: Bool {
        guard let original else { return !draft.name.isEmpty || !draft.description.isEmpty || !draft.prompt.isEmpty }
        return !draft.same(as: AgentDraft(original))
    }

    var body: some View {
        Form {
            Section {
                TextField(text: $draft.name, prompt: Text(verbatim: "code-reviewer")) { Text("名称") }
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text("名称")
            } footer: {
                Text("小写字母、数字和连字符，比如 code-reviewer。")
            }
            Section {
                TextField("比如：改完代码后用它审查改动，找出 bug 和不清楚的地方", text: $draft.description, axis: .vertical)
                    .lineLimit(2...6)
            } header: {
                Text("什么时候用")
            } footer: {
                Text("Claude 靠这段话决定要不要交给它，写清楚它擅长什么。")
            }
            Section {
                Picker("模型", selection: $draft.model) {
                    ForEach(modelValues, id: \.self) { Text(AgentDraft.modelLabel($0)).tag($0) }
                }
            } footer: {
                Text("默认时跟随主对话的模型。")
            }
            toolsSection
            Section {
                TextField("你是一名代码审查员……", text: $draft.prompt, axis: .vertical)
                    .lineLimit(8...24)
            } header: {
                Text("系统提示词")
            } footer: {
                Text("写它的角色和做事方式。它看不到主对话，只看到这段话和交给它的任务。")
            }
            notes
        }
        .disabled(saving)
        .navigationTitle(original == nil ? "新建 Subagent" : "编辑 Subagent")
        .navigationBarBackButtonHidden(dirty)
        .toolbar { toolbar }
        .onAppear {
            if !started, let original { draft = AgentDraft(original) }
            started = true
        }
        .confirmationDialog("放弃未保存的更改？", isPresented: $leaving, titleVisibility: .visible) {
            Button("放弃", role: .destructive) { dismiss() }
        }
        .confirmationDialog("删除 Subagent", isPresented: $deleting, titleVisibility: .visible) {
            Button("删除", role: .destructive) { delete() }
        } message: {
            Text("删除“\(originalName)”？删了就找不回来。")
        }
        .configFailure($failure)
    }

    private var originalName: String { original?.name ?? "" }

    private var modelValues: [String] {
        AgentDraft.models.contains { $0.caseInsensitiveCompare(draft.model) == .orderedSame } ? AgentDraft.models
            : AgentDraft.models + [draft.model]
    }

    private var toolsSection: some View {
        Section {
            Toggle(isOn: $draft.allTools) {
                TitledRow(title: String(localized: "全部工具"), summary: String(localized: "包括 MCP 工具"))
            }
            if !draft.allTools {
                ForEach(AgentDraft.commonTools, id: \.self) { tool in
                    Button {
                        draft.checked.formSymmetricDifference([tool])
                    } label: {
                        HStack {
                            TitledRow(title: AgentDraft.toolLabel(tool), summary: tool)
                            Spacer()
                            if draft.checked.contains(tool) { Image(systemName: "checkmark").foregroundStyle(.tint) }
                        }
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
                TextField(text: $draft.others, prompt: Text(verbatim: "mcp__github__create_issue, …")) { Text("其他工具") }
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
        } header: {
            Text("工具")
        } footer: {
            Text("关掉“全部工具”后只能用勾选的。MCP 工具写成 mcp__服务器__工具，多个用逗号分开。")
        }
    }

    @ViewBuilder private var notes: some View {
        let missing = draft.missing
        if !problem.isEmpty {
            Section { Text(problem).foregroundStyle(.red) }
        } else if !missing.isEmpty, !draft.name.isEmpty || !draft.description.isEmpty {
            Section { Text(missing).foregroundStyle(.secondary) }
        }
        if let keys = original?.extraKeys, !keys.isEmpty {
            Section {
                let listed = keys.joined(separator: String(localized: "、"))
                Text("另外还设了 \(listed)，保存时原样保留。").foregroundStyle(.secondary)
            }
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        if dirty {
            ToolbarItem(placement: .cancellationAction) {
                Button("放弃") { leaving = true }
            }
        }
        ToolbarItem(placement: .confirmationAction) {
            Button(saving ? String(localized: "保存中") : String(localized: "保存")) { save() }
                .disabled(saving || !draft.missing.isEmpty)
        }
        if original != nil {
            ToolbarItem(placement: .destructiveAction) {
                Button("删除", systemImage: "trash", role: .destructive) { deleting = true }
            }
        }
    }

    private func save() {
        var params: [String: any Sendable] = ["draft": draft.params]
        if let original { params["original"] = original.params }
        saving = true
        problem = ""
        Task {
            defer { saving = false }
            do {
                _ = try await calls.get("config.agents.save", params, as: SavedAgent.self)
                changed()
                dismiss()
            } catch {
                problem = error.localizedDescription
            }
        }
    }

    private func delete() {
        guard let original else { return }
        Task {
            do {
                try await calls.run("config.agents.delete", ["file": original.file])
                changed()
                dismiss()
            } catch {
                failure = ConfigFailure(String(localized: "删除失败"), error)
            }
        }
    }
}
