import SwiftUI

/// 工具权限（同桌面版 permissions.go）：Claude Code 自己的 permissions 规则（~/.claude/settings.json 的 allow、ask、deny）。
/// 每个内置工具、每个 MCP 服务器能整体设成允许、询问、拒绝；带参数的规则和认不出的写法列在「规则」里，可以改、删、加。
struct PermissionsView: View {
    @State private var store: PermissionsStore
    @State private var showMore = false
    @State private var editing: RuleEditing?
    @State private var deleting: PermRule?

    init(calls: ConfigCalls, cwd: String) {
        _store = State(initialValue: PermissionsStore(calls: calls, cwd: cwd))
    }

    var body: some View {
        Form {
            Section {
                Text("规则按“拒绝 > 询问 > 允许”取最严的，写得再细也一样。完全放行模式下，询问和拒绝照样生效。改动对所有项目、之后新开始的对话生效。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if !store.loadProblem.isEmpty {
                    Text("读取失败：\(store.loadProblem)").foregroundStyle(.red)
                }
            }
            if let data = store.data {
                builtinSections(data)
                serversSection(data)
                rulesSection(data)
            } else if store.loadProblem.isEmpty {
                LoadingRow()
            }
        }
        .navigationTitle("工具权限")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("重新读取", systemImage: "arrow.clockwise") { store.reload() }
                    .disabled(store.working)
            }
            ToolbarItem(placement: .primaryAction) {
                Button("添加规则", systemImage: "plus") { editing = RuleEditing(original: nil) }
                    .disabled(store.data == nil || store.working)
            }
        }
        .task {
            if store.data == nil { store.reload() }
        }
        .onAppear {
            // 从服务器页回来：不再另算那个服务器的工具
            if store.server != nil {
                store.server = nil
                store.serverTools = nil
            }
        }
        .sheet(item: $editing) { RuleEditView(store: store, editing: $0) }
        .confirmationDialog(
            "删除规则", isPresented: Binding { deleting != nil } set: { if !$0 { deleting = nil } },
            titleVisibility: .visible, presenting: deleting
        ) { rule in
            Button("删除", role: .destructive) { store.remove(rule) }
        } message: { rule in
            Text("删除\(ConfigLabels.behavior(rule.behavior))规则 \(rule.rule)？")
        }
        .configFailure($store.failure)
    }

    @ViewBuilder private func builtinSections(_ data: PermissionsPage) -> some View {
        let more = data.builtin.filter(\.more)
        Section("内置工具") {
            BulkButtons(store: store, server: nil, pair: data.builtinBulk)
            ForEach(data.builtin.filter { !$0.more }, id: \.name) { toolRow($0) }
            if !showMore, !more.isEmpty {
                Button {
                    showMore = true
                } label: {
                    LabeledContent("更多内置工具", value: String(localized: "\(String(more.count)) 个"))
                }
                .accessibilityLabel("展开更多内置工具")
            }
        }
        if showMore, !more.isEmpty {
            Section("更多内置工具") {
                ForEach(more, id: \.name) { toolRow($0) }
            }
        }
    }

    private func toolRow(_ tool: ToolView) -> some View {
        ToolPermissionRow(
            store: store, title: tool.name, summary: PermLabels.summary(tool.label, tool.state, fromServer: true),
            rule: tool.rule, own: tool.own)
    }

    private func serversSection(_ data: PermissionsPage) -> some View {
        Section("MCP 服务器") {
            if data.servers.isEmpty {
                Text("没有配置 MCP 服务器").foregroundStyle(.secondary)
            }
            ForEach(data.servers, id: \.name) { server in
                NavigationLink {
                    PermServerView(store: store, name: server.name)
                } label: {
                    LabeledContent {
                        Text(ConfigLabels.behavior(server.state.behavior))
                    } label: {
                        TitledRow(
                            title: server.name,
                            summary: PermLabels.summary(server.rule, server.state, fromServer: false))
                    }
                }
            }
        }
    }

    private func rulesSection(_ data: PermissionsPage) -> some View {
        Section {
            if data.rules.isEmpty {
                TitledRow(
                    title: String(localized: "还没有规则"),
                    summary: String(localized: "点右上角的“添加规则”"))
            }
            ForEach(data.rules, id: \.self) { rule in
                let table = ConfigLabels.behavior(rule.behavior)
                Button {
                    editing = RuleEditing(original: rule.plain)
                } label: {
                    TitledRow(
                        title: rule.rule,
                        summary: rule.malformed
                            ? String(localized: "\(table) · 括号不配对，\(Harness.name(store.calls.harness)) 认不出这条")
                            : table)
                }
                .foregroundStyle(.primary)
                .disabled(store.working)
                .swipeActions {
                    Button("删除", role: .destructive) { deleting = rule.plain }
                }
                .contextMenu {
                    Button("编辑", systemImage: "pencil") { editing = RuleEditing(original: rule.plain) }
                    Button("删除", systemImage: "trash", role: .destructive) { deleting = rule.plain }
                }
            }
        } header: {
            Text("规则 · \(String(data.rules.count))")
        } footer: {
            Text("带参数的规则只在这里改，上面的下拉不动它们。")
        }
    }
}

/// 要添加（original 为 nil）或编辑的规则。
struct RuleEditing: Identifiable {
    let id = UUID()
    let original: PermRule?
}

/// 添加或编辑一条规则：选表、写规则。
private struct RuleEditView: View {
    let store: PermissionsStore
    let editing: RuleEditing
    @Environment(\.dismiss) private var dismiss
    @State private var behavior = "allow"
    @State private var text = ""
    @State private var problem = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Picker("表", selection: $behavior) {
                    ForEach(ConfigLabels.behaviors, id: \.self) { Text(ConfigLabels.behavior($0)).tag($0) }
                }
                Section {
                    let example = store.calls.isAgy ? "run_command(git push:*)" : "Bash(git push:*)"
                    TextField(text: $text, prompt: Text(verbatim: example)) { Text("规则") }
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.body.monospaced())
                } footer: {
                    Text(store.calls.isAgy
                        ? """
                            写成 工具名 或 工具名(参数)。\
                            比如 run_command(npm test:*)、read_file(./.env)、\
                            edit_file(src/**)、mcp__github（整个服务器）。
                            """
                        : """
                            写成 工具名 或 工具名(参数)。\
                            比如 Bash(npm run test:*)、Read(./.env)、\
                            Edit(src/**)、WebFetch(domain:github.com)、mcp__github（整个服务器）。
                            """)
                }
                if !problem.isEmpty {
                    Section { Text(problem).foregroundStyle(.red) }
                }
            }
            .disabled(saving)
            .navigationTitle(editing.original == nil ? "添加规则" : "编辑规则")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else { Button("保存") { save() } }
                }
            }
            .onAppear {
                if let original = editing.original {
                    behavior = original.behavior
                    text = original.rule
                }
            }
        }
    }

    private func save() {
        let rule = PermRule(behavior: behavior, rule: text.trimmingCharacters(in: .whitespaces))
        guard !rule.rule.isEmpty else {
            problem = String(localized: "规则不能是空的")
            return
        }
        saving = true
        problem = ""
        store.save(original: editing.original, new: rule) { failed in
            saving = false
            if failed.isEmpty { dismiss() } else { problem = failed }
        }
    }
}

/// 一个 MCP 服务器的权限：一键开关、整个服务器一行、每个工具一行（连上服务器取工具清单后由核心算状态）。
private struct PermServerView: View {
    let store: PermissionsStore
    let name: String
    @State private var busy = false
    @State private var problem = ""

    var body: some View {
        Form {
            if let server = store.serverView(name) {
                Section {
                    BulkButtons(store: store, server: server.name, pair: server.bulk)
                    ToolPermissionRow(
                        store: store, title: String(localized: "整个服务器"), summary: server.rule, rule: server.rule,
                        own: server.state.behavior)
                } footer: {
                    Text("整个服务器的设置管它的每个工具；工具自己也设了的，取更严的那个。")
                }
                ToolsSection(
                    tools: store.server == name ? store.serverTools : nil, busy: busy, problem: problem,
                    oauth: server.oauth, load: { Task { await loadTools(server) } },
                    footer: server.oauth ? String(localized: "要按工具设置，在上一页的“规则”里手写 \(server.rule)__工具名。") : "",
                    row: { tool in
                    if let view = store.serverTool(tool.name) {
                        ToolPermissionRow(
                            store: store, title: tool.name,
                            summary: PermLabels.summary(
                                ConfigLabels.clip(tool.description, 60), view.state, fromServer: true),
                            rule: view.rule, own: view.own)
                    } else {
                        TitledRow(title: tool.name, summary: ConfigLabels.clip(tool.description, 60))
                    }
                })
            }
        }
        .navigationTitle(name)
        .task {
            // 和 MCP 页一样：项目里的 .mcp.json 可能来自别人的仓库，不自动替它启动命令
            if let server = store.serverView(name), store.server != name, server.scope != "project", !server.oauth {
                await loadTools(server)
            }
        }
    }

    private func loadTools(_ server: ServerView) async {
        busy = true
        problem = ""
        defer { busy = false }
        do {
            let params: [String: any Sendable] = ["cwd": store.cwd, "scope": server.scope, "name": server.name]
            let tools = try await store.calls.slow("config.mcp.tools", params, as: ListOf<McpTool>.self).items
            store.server = name
            store.serverTools = tools
            store.reload()  // 算这些工具的状态
        } catch {
            problem = error.localizedDescription
        }
    }
}
