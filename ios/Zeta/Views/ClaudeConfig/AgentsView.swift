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
                        title: String(localized: "暂无自定义 Subagent"),
                        summary: String(localized: "可让 Claude 生成，或点击右上角“新建”"))
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
                Text(calls.isAgy
                    ? "保存在 ~/.gemini/config/agents，所有项目通用。Antigravity 会根据“适用场景”自动委派，你也可以在对话中直接指定。"
                    : "保存在 ~/.claude/agents，所有项目通用。Claude 会根据“适用场景”自动委派，你也可以在对话中直接指定。")
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
    var isAgy = false
    var name = ""
    var description = ""
    var model = ""
    var prompt = ""
    var allTools = true
    var checked: Set<String> = []
    var others = ""

    /// 模型的选项：不写时跟随主对话的模型。
    static let models = ["", "inherit", "sonnet", "opus", "haiku", "fable"]
    static let agyModels = ["", "inherit", "flash_lite", "flash", "pro"]

    /// 常用的内置工具；别的（比如 MCP 工具）写进「其他工具」。
    static let commonTools = [
        "Read", "Edit", "Write", "Glob", "Grep", "Bash", "WebFetch", "WebSearch", "NotebookEdit", "Skill"
    ]
    static let agyTools = [
        "read_file", "edit_file", "write_file", "find_by_name", "grep_search",
        "run_command", "search_web", "invoke_subagent", "ask_question", "update_steps"
    ]

    var builtinTools: [String] { isAgy ? Self.agyTools : Self.commonTools }

    init(isAgy: Bool = false) {
        self.isAgy = isAgy
    }

    init(_ agent: Subagent, isAgy: Bool = false) {
        self.isAgy = isAgy
        let builtins = isAgy ? Self.agyTools : Self.commonTools
        name = agent.name
        description = agent.description
        model = agent.model
        prompt = agent.prompt
        allTools = agent.tools == nil
        let tools = agent.tools ?? []
        checked = Set(tools.filter(builtins.contains))
        others = tools.filter { !builtins.contains($0) }.joined(separator: ", ")
    }

    /// 要写进文件的工具：勾选的常用工具按固定顺序，再加「其他工具」里逗号分开的；nil 是全部工具。
    var selectedTools: [String]? {
        guard !allTools else { return nil }
        let extra = others.split { $0 == "," || $0 == "，" }.map { $0.trimmingCharacters(in: .whitespaces) }
        return builtinTools.filter(checked.contains) + extra.filter { !$0.isEmpty }
    }

    /// 保存前还差什么；不差时为空。
    var missing: String {
        if name.trimmingCharacters(in: .whitespaces).wholeMatch(of: /[a-z0-9][a-z0-9-]{0,63}/) == nil {
            return String(localized: "名称仅支持小写字母、数字和连字符，且须以字母或数字开头")
        }
        if description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return String(localized: "请填写适用场景后再保存")
        }
        return ""
    }

    func same(as other: AgentDraft) -> Bool {
        name == other.name && description == other.description && model == other.model && prompt == other.prompt
            && selectedTools == other.selectedTools
    }

    var params: [String: any Sendable] {
        var out: [String: any Sendable] = [
            "name": name.trimmingCharacters(in: .whitespaces),
            "description": description.trimmingCharacters(in: .whitespacesAndNewlines),
            "model": model, "prompt": prompt
        ]
        if let tools = selectedTools { out["tools"] = tools }
        return out
    }

    static func modelLabel(_ model: String) -> String {
        let labels = [
            "": String(localized: "默认"),
            "inherit": String(localized: "跟随主对话"),
            "sonnet": "Sonnet",
            "opus": "Opus",
            "haiku": "Haiku",
            "fable": "Fable",
            "flash_lite": "Flash Lite",
            "flash": "Flash",
            "pro": "Pro"
        ]
        return labels[model.lowercased()] ?? model
    }

    static func toolLabel(_ tool: String) -> String {
        let labels = [
            "Read": String(localized: "读取文件"),
            "Edit": String(localized: "修改文件"),
            "Write": String(localized: "新建或覆盖文件"),
            "Glob": String(localized: "按文件名查找"),
            "Grep": String(localized: "搜索文件内容"),
            "Bash": String(localized: "执行命令"),
            "WebFetch": String(localized: "抓取网页"),
            "WebSearch": String(localized: "网络搜索"),
            "NotebookEdit": String(localized: "编辑 Jupyter 笔记本"),
            "Skill": String(localized: "使用 Skill")
        ]
        return labels[tool] ?? ToolLabels.title(tool)
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
        return !draft.same(as: AgentDraft(original, isAgy: calls.isAgy))
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
                Text("仅支持小写字母、数字和连字符，例如 code-reviewer。")
            }
            Section {
                TextField("例如：在修改代码后审查变更，排查潜在缺陷与不清晰之处", text: $draft.description, axis: .vertical)
                    .lineLimit(2...6)
            } header: {
                Text("适用场景")
            } footer: {
                Text("Claude 根据此说明决定是否委派任务，请明确描述其擅长领域。")
            }
            Section {
                Picker("模型", selection: $draft.model) {
                    ForEach(modelValues, id: \.self) { Text(AgentDraft.modelLabel($0)).tag($0) }
                }
            } footer: {
                Text("默认沿用主对话模型。")
            }
            toolsSection
            Section {
                TextField("你是一名代码审查员……", text: $draft.prompt, axis: .vertical)
                    .lineLimit(8...24)
            } header: {
                Text("系统提示词")
            } footer: {
                Text("描述其角色与工作方式。该 Subagent 无法读取主对话，仅能看到此提示词和委派的任务。")
            }
            notes
        }
        .disabled(saving)
        .navigationTitle(original == nil ? "新建 Subagent" : "编辑 Subagent")
        .navigationBarBackButtonHidden(dirty)
        .toolbar { toolbar }
        .onAppear {
            if !started {
                draft = original.map { AgentDraft($0, isAgy: calls.isAgy) } ?? AgentDraft(isAgy: calls.isAgy)
                started = true
            }
        }
        .confirmationDialog("放弃未保存的更改？", isPresented: $leaving, titleVisibility: .visible) {
            Button("放弃", role: .destructive) { dismiss() }
        }
        .confirmationDialog("删除 Subagent", isPresented: $deleting, titleVisibility: .visible) {
            Button("删除", role: .destructive) { delete() }
        } message: {
            Text("删除“\(originalName)”？删除后无法恢复。")
        }
        .configFailure($failure)
    }

    private var originalName: String { original?.name ?? "" }

    private var modelValues: [String] {
        let base = calls.isAgy ? AgentDraft.agyModels : AgentDraft.models
        return base.contains { $0.caseInsensitiveCompare(draft.model) == .orderedSame } ? base
            : base + [draft.model]
    }

    private var toolsSection: some View {
        Section {
            Toggle(isOn: $draft.allTools) {
                TitledRow(title: String(localized: "全部工具"), summary: String(localized: "包括 MCP 工具"))
            }
            if !draft.allTools {
                ForEach(draft.builtinTools, id: \.self) { tool in
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
            Text("关闭“全部工具”后仅可使用已勾选工具。MCP 工具格式为 mcp__服务器__工具，多项请用逗号分隔。")
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
                Text("已另行配置 \(listed)，保存时将原样保留。").foregroundStyle(.secondary)
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
