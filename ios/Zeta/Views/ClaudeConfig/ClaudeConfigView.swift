import SwiftUI

/// 一台设备的工具配置（Claude Code 或 Antigravity）：新对话默认、运行时与 CLI 版本及更新、配置各页（记忆/规则、Skills、
/// MCP 服务器、Subagent、工具权限）的入口。读写的是那台主机上的真实配置。
struct ClaudeConfigView: View {
    @Environment(AppModel.self) private var model
    let backend: String
    var harness = Harness.claude
    @State private var store: ClaudeConfigStore?

    var body: some View {
        Group {
            if let store {
                ClaudeConfigForm(store: store)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(harness == Harness.agy ? "Antigravity 配置" : "Claude Code 配置")
        .task(id: harness) {
            let created = ClaudeConfigStore(model: model, backend: backend, harness: harness)
            store = created
            await created.load()
        }
    }
}

private struct ClaudeConfigForm: View {
    @Bindable var store: ClaudeConfigStore

    var body: some View {
        Form {
            DefaultsSection(store: store)
            ClaudeSection(store: store)
            Section {
                NavigationLink(store.isAgy ? "规则" : "记忆") { MemoryView(calls: store.calls, cwd: store.cwd) }
                NavigationLink("Skills") { SkillsView(calls: store.calls) }
                NavigationLink("MCP 服务器") { McpView(calls: store.calls, cwd: store.cwd) }
                NavigationLink("Subagent") { AgentsView(calls: store.calls) }
                NavigationLink("工具权限") { PermissionsView(calls: store.calls, cwd: store.cwd) }
            } header: {
                Text(store.isAgy ? "Antigravity 配置" : "Claude Code 配置")
            } footer: {
                Text(store.isAgy
                    ? "修改此设备上 Antigravity 的原生配置（~/.gemini），对终端中的 agy 同样生效。"
                    : "修改此设备上 Claude Code 的原生配置（~/.claude），对终端中的 claude 同样生效。")
            }
        }
        .refreshable { await store.load() }
        .configFailure($store.failure)
    }
}

/// 新对话默认：默认模型、默认思考强度、默认权限模式、上下文窗口。
private struct DefaultsSection: View {
    let store: ClaudeConfigStore

    var body: some View {
        Section {
            if let defaults = store.defaults {
                DefaultModelMenu(store: store, defaults: defaults)
                if !store.isAgy {
                    NavigationLink {
                        EffortView(store: store)
                    } label: {
                        LabeledContent("默认思考强度", value: Self.effortSummary(defaults, store))
                    }
                }
                Picker("默认权限模式", selection: modeBinding(defaults)) {
                    ForEach(modes(defaults), id: \.self) { Text(ConfigLabels.permissionMode($0)).tag($0) }
                }
                .disabled(store.working)
                if !store.isAgy {
                    Picker("上下文窗口", selection: windowBinding()) {
                        Text("沿用模型默认窗口").tag(Int64(0))
                        ForEach(windows(defaults), id: \.self) { Text(ConfigLabels.tokens($0)).tag($0) }
                    }
                    .disabled(store.working)
                }
            } else if store.defaultsProblem.isEmpty {
                LoadingRow()
            } else {
                TitledRow(
                    title: store.isAgy
                        ? String(localized: "暂未读取到 Antigravity 设置")
                        : String(localized: "暂未读取到 Claude Code 设置"),
                    summary: store.defaultsProblem)
            }
        } header: {
            Text("新对话默认设置")
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                if store.modelsPending { Text("正在加载此设备的模型列表。") }
                Text(store.isAgy
                    ? "用于在此设备发起新对话。使用中转时，默认模型保存在中转设置中；对话内切换的选项仅对当前对话生效。"
                    : """
                    用于在此设备发起新对话。使用中转时，默认模型保存在中转设置中；对话内切换的选项仅对当前对话生效。\
                    思考强度按模型分别保存。上下文窗口指单次会话的 token 上限，自动压缩以此为基准；若超过模型自身上限，则以模型上限为准。
                    """)
            }
        }
    }

    private func modes(_ defaults: ConfigDefaults) -> [String] {
        let mode = defaults.permissionMode.isEmpty ? "default" : defaults.permissionMode
        return ConfigLabels.permissionModes.contains(mode) ? ConfigLabels.permissionModes
            : ConfigLabels.permissionModes + [mode]
    }

    private func modeBinding(_ defaults: ConfigDefaults) -> Binding<String> {
        Binding {
            defaults.permissionMode.isEmpty ? "default" : defaults.permissionMode
        } set: { mode in
            if ConfigLabels.permissionModes.contains(mode) {
                store.setDefault("config.defaults.setPermissionMode", ["value": mode])
            }
        }
    }

    private func windows(_ defaults: ConfigDefaults) -> [Int64] {
        let all = ConfigLabels.compactWindows
        let current = defaults.compactWindow
        return current == 0 || all.contains(current) ? all : all + [current]
    }

    private func windowBinding() -> Binding<Int64> {
        Binding { store.defaults?.compactWindow ?? 0 } set: {
            store.setDefault("config.defaults.setCompactWindow", ["value": $0])
        }
    }

    /// 「Opus 5.5 超高，其他模型 高」；都没设时说由 Claude Code 定。
    static func effortSummary(_ defaults: ConfigDefaults, _ store: ClaudeConfigStore) -> String {
        var parts = defaults.efforts.map { "\(store.modelName($0.model)) \(ConfigLabels.effort($0.level))" }
        if parts.isEmpty {
            guard !defaults.effort.isEmpty else {
                return store.isAgy
                    ? String(localized: "未设置，沿用 Antigravity 默认值")
                    : String(localized: "未设置，沿用 Claude Code 默认值")
            }
            return ConfigLabels.effort(defaults.effort)
        }
        if !defaults.effort.isEmpty {
            parts.append(String(localized: "其他模型") + " " + ConfigLabels.effort(defaults.effort))
        }
        return parts.joined(separator: String(localized: "，"))
    }
}

/// 默认模型的菜单（同桌面版 modelChoice）：第一项是不设，接着是各系列最新的，旧版本收在「更多模型」里；设着的不在清单里时也列在上层。
private struct DefaultModelMenu: View {
    let store: ClaudeConfigStore
    let defaults: ConfigDefaults

    var body: some View {
        let current = defaults.model == "default" ? "" : defaults.model
        let all = store.models.models
        var top = all.filter { !$0.older }
        let older = all.filter(\.older)
        if !current.isEmpty, !all.contains(where: { $0.value == current }) {
            top.append(ModelChoice(value: current, label: ConfigLabels.readableModel(current), id: "", older: false))
        }
        let defaultLabel = store.models.source == "harness"
            ? (store.isAgy ? String(localized: "Antigravity 内置默认值") : String(localized: "Claude Code 内置默认值"))
            : String(localized: "默认")
        let shown = (top + older).first { $0.value == current }?.label ?? defaultLabel
        let disabled = store.working || store.models.source == "unknown"
        return LabeledContent("默认模型") {
            Menu {
                choice(defaultLabel, value: "", current: current)
                ForEach(top, id: \.value) { choice($0.label, value: $0.value, current: current) }
                if !older.isEmpty {
                    Menu("更多模型") {
                        ForEach(older, id: \.value) { choice($0.label, value: $0.value, current: current) }
                    }
                }
            } label: {
                Text(shown)
            }
            .disabled(disabled)
        }
    }

    private func choice(_ label: String, value: String, current: String) -> some View {
        Button {
            if value != current { store.setDefault("config.defaults.setModel", ["value": value]) }
        } label: {
            if value == current { Label(label, systemImage: "checkmark") } else { Text(label) }
        }
    }
}

/// 运行时与 CLI 版本：自动压缩、压缩时机、自动标题（Claude Code，存在这台手机上），闲置超时和 CLI 版本（在那台主机上）。
private struct ClaudeSection: View {
    let store: ClaudeConfigStore

    var body: some View {
        Section {
            if !store.isAgy {
                if let local = store.local {
                    Toggle("自动压缩上下文", isOn: Binding { local.autoCompact } set: { store.setLocal("autoCompact", $0) })
                    let point = Binding { local.compactPercent } set: { store.setLocal("compactPercent", $0) }
                    Picker("压缩时机", selection: point) {
                        ForEach(points(local), id: \.self) { Text(ConfigLabels.compactPercent($0)).tag($0) }
                    }
                    .disabled(!local.autoCompact)
                    Toggle(isOn: Binding { local.autoTitle } set: { store.setLocal("autoTitle", $0) }) {
                        TitledRow(
                            title: String(localized: "自动生成对话标题"),
                            summary: String(localized: "首轮回复后由 Claude Code 自动生成简短标题，已手动重命名的对话不受影响"))
                    }
                } else {
                    LoadingRow()
                }
            }
            idleRow
            claudeRow
        } header: {
            Text(store.isAgy ? "Antigravity" : "Claude")
        } footer: {
            Text(store.isAgy
                ? "闲置超时作用于此设备上托管的进程并立即生效；更新会运行 agy update，不影响进行中的对话。"
                : "仅对 Zeta 在此设备发起的对话生效。闲置超时立即生效，其余设置将在下次启动进程时生效。")
        }
    }

    private func points(_ local: ClaudeLocal) -> [Int] {
        let all = [0] + local.compactPercents
        return all.contains(local.compactPercent) ? all : all + [local.compactPercent]
    }

    @ViewBuilder private var idleRow: some View {
        if let idle = store.idle {
            let all = ConfigLabels.idleChoices.contains(idle)
                ? ConfigLabels.idleChoices : ConfigLabels.idleChoices + [idle]
            Picker("闲置超时", selection: Binding { idle } set: { store.setIdle($0) }) {
                ForEach(all, id: \.self) { Text(ConfigLabels.idle($0)).tag($0) }
            }
        } else {
            LabeledContent("闲置超时", value: "…")
        }
    }

    private var claudeRow: some View {
        HStack {
            let value = !store.claudeLoaded ? "…"
                : store.claudeVersion.isEmpty ? String(localized: "未安装") : store.claudeVersion
            TitledRow(
                title: store.isAgy ? "Antigravity CLI" : "Claude Code",
                summary: store.updating ? String(localized: "正在更新…") : store.claudeNote)
            Spacer()
            Text(value).foregroundStyle(.secondary)
            if store.updating {
                ProgressView()
            } else {
                Button("更新") { store.updateClaude() }
                    .buttonStyle(.bordered)
                    .disabled(!store.claudeLoaded)
            }
        }
    }
}

/// 默认思考强度：每个模型一行（设过的、清单里的），最后是「其他模型」（同桌面版 effortPage）。
private struct EffortView: View {
    let store: ClaudeConfigStore

    private struct Row: Identifiable {
        let model: String
        let title: String
        let value: String
        var id: String { model }
    }

    var body: some View {
        Form {
            Section {
                if let defaults = store.defaults {
                    ForEach(rows(defaults)) { row in
                        Picker(row.title, selection: Binding { row.value } set: { level in
                            store.setDefault("config.defaults.setEffort", ["model": row.model, "value": level])
                        }) {
                            Text(store.isAgy ? "跟随 Antigravity" : "跟随 Claude Code").tag("")
                            ForEach(ConfigLabels.effortLevels, id: \.self) { Text(ConfigLabels.effort($0)).tag($0) }
                        }
                        .disabled(store.working)
                    }
                } else {
                    LoadingRow()
                }
            } footer: {
                Text(store.isAgy
                    ? "此设备上的新对话按所用模型读取此处设置；未单独设置的模型使用「其他模型」配置。"
                    : "此设备上的新对话按所用模型读取此处设置，与终端内 /effort 保存的默认配置同步；未单独设置的模型使用「其他模型」配置。")
            }
        }
        .navigationTitle("默认思考强度")
    }

    private func rows(_ defaults: ConfigDefaults) -> [Row] {
        var out: [Row] = []
        var listed = Set<String>()
        for effort in defaults.efforts where listed.insert(effort.model).inserted {
            out.append(Row(model: effort.model, title: store.modelName(effort.model), value: effort.level))
        }
        for choice in store.models.models where !choice.id.isEmpty && listed.insert(choice.id).inserted {
            out.append(Row(model: choice.id, title: store.modelName(choice.id), value: ""))
        }
        out.append(Row(model: "", title: String(localized: "其他模型"), value: defaults.effort))
        return out
    }
}
