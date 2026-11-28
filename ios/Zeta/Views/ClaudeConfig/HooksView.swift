import SwiftUI

struct HooksView: View {
    let calls: ConfigCalls
    let cwd: String
    @State private var view: AgyHooksView?
    @State private var working = false
    @State private var failure: ConfigFailure?
    @State private var editing: HookForm?

    var body: some View {
        List {
            Section {
                if let list = view?.hooks {
                    if list.isEmpty {
                        Text("暂未配置钩子").foregroundStyle(.secondary)
                    }
                    ForEach(list) { hook in
                        hookRow(hook)
                    }
                } else {
                    LoadingRow()
                }
            } footer: {
                Text("聚合展示用户级（~/.gemini/config/hooks.json）、项目级（.agents/hooks.json）与只读插件钩子。")
            }
        }
        .navigationTitle("钩子")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("添加", systemImage: "plus") { editing = HookForm() }
                    .disabled(working)
            }
        }
        .sheet(item: $editing) { form in
            HookEditorSheet(form: form) { save($0) }
        }
        .task { await load() }
        .refreshable { await load() }
        .configFailure($failure)
    }

    @ViewBuilder
    private func hookRow(_ hook: AgyHookEntry) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(hook.name)
                Text(summary(hook)).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer()
            if hook.isReadOnly {
                Text("只读").font(.caption).foregroundStyle(.secondary)
            } else {
                Toggle("", isOn: Binding(
                    get: { hook.enabled },
                    set: { toggle(hook, enabled: $0) }
                ))
                .labelsHidden()
                .disabled(working)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if !hook.isReadOnly { editing = HookForm(hook) }
        }
        .swipeActions {
            if !hook.isReadOnly {
                Button("删除", role: .destructive) { remove(hook) }.disabled(working)
            }
        }
    }

    private func summary(_ hook: AgyHookEntry) -> String {
        var parts = [hook.plugin.map { "\(hook.scope) (\($0))" } ?? hook.scope]
        if let events = hook.events, !events.isEmpty { parts.append(events.joined(separator: ", ")) }
        if !hook.command.isEmpty { parts.append(hook.command) }
        return parts.joined(separator: " · ")
    }

    private func load() async {
        do {
            view = try await calls.get("config.hooks.read", ["cwd": cwd], as: AgyHooksView.self)
        } catch {
            failure = ConfigFailure(String(localized: "读取钩子失败"), error)
        }
    }

    private func toggle(_ hook: AgyHookEntry, enabled: Bool) {
        mutate("config.hooks.setEnabled", [
            "cwd": cwd, "scope": hook.scope, "name": hook.name, "enabled": enabled
        ])
    }

    private func save(_ form: HookForm) {
        var draft: [String: any Sendable] = [
            "originalName": form.originalName,
            "name": form.name,
            "scope": form.scope,
            "enabled": form.enabled,
            "event": form.event,
            "matcher": form.matcher,
            "command": form.command
        ]
        if let secs = Int(form.timeout), secs > 0 { draft["timeout"] = secs }
        mutate("config.hooks.save", ["cwd": cwd, "draft": draft])
    }

    private func remove(_ hook: AgyHookEntry) {
        mutate("config.hooks.delete", ["cwd": cwd, "scope": hook.scope, "name": hook.name])
    }

    private func mutate(_ method: String, _ params: [String: any Sendable]) {
        guard !working else { return }
        working = true
        Task {
            defer { working = false }
            do {
                view = try await calls.get(method, params, as: AgyHooksView.self)
            } catch {
                failure = ConfigFailure(String(localized: "保存钩子失败"), error)
            }
        }
    }
}

private struct HookForm: Identifiable {
    let id = UUID()
    var originalName = ""
    var name = ""
    var scope = "user"
    var enabled = true
    var event = "PreToolUse"
    var matcher = ""
    var command = ""
    var timeout = ""

    init() {}

    init(_ entry: AgyHookEntry) {
        originalName = entry.name
        name = entry.name
        scope = entry.scope
        enabled = entry.enabled
        event = entry.event.isEmpty ? "PreToolUse" : entry.event
        matcher = entry.matcher ?? ""
        command = entry.command
        timeout = entry.timeout.map { $0 > 0 ? String($0) : "" } ?? ""
    }
}

private struct HookEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var form: HookForm
    let onSave: (HookForm) -> Void

    private static let events = ["SessionStart", "PreInvocation", "PreToolUse", "PostToolUse", "PostInvocation", "Stop"]

    var body: some View {
        NavigationStack {
            Form {
                TextField("钩子名称", text: $form.name)
                Picker("范围", selection: $form.scope) {
                    Text("user").tag("user")
                    Text("project").tag("project")
                }
                Picker("事件", selection: $form.event) {
                    ForEach(Self.events, id: \.self) { Text($0).tag($0) }
                }
                if form.event == "PreToolUse" || form.event == "PostToolUse" {
                    TextField("Matcher（可选）", text: $form.matcher)
                }
                TextField("执行命令", text: $form.command)
                TextField("超时秒数（可选）", text: $form.timeout)
                    .keyboardType(.numberPad)
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .navigationTitle(form.originalName.isEmpty ? "添加钩子" : "编辑钩子")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        onSave(form)
                        dismiss()
                    }
                    .disabled(form.name.trimmingCharacters(in: .whitespaces).isEmpty
                        || form.command.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
