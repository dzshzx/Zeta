import SwiftUI

/// 等确认的重新生成、删除一轮、还原代码（同桌面版 msgactions.go 的 confirm）：对哪条用户消息，之后还有几轮。
struct RoundConfirm: Identifiable {
    enum Kind {
        case regenerate, drop, restore
    }

    let kind: Kind
    let user: Item
    let later: Int

    var id: String { user.id }
}

/// 确认框：先问核心还原代码会动到哪些文件（codechanges）；这条消息之后改过文件时，重新生成、删除可以同时还原代码，
/// 还原代码可以同时删除这一轮。
struct RoundConfirmSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let confirm: RoundConfirm
    let cwd: String

    @State private var asked = false
    @State private var changes: CodeChanges?
    @State private var failed: String?
    @State private var withCode = true
    @State private var alsoDrop = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(summary).foregroundStyle(.secondary)
                    if let changes, !changes.files.isEmpty {
                        Text("改动了 \(changes.files.count) 个文件，+\(changes.insertions) -\(changes.deletions)")
                            .font(.footnote)
                    }
                }
                if let changes, confirm.kind == .restore {
                    Section("会还原的文件") {
                        ForEach(changes.files.prefix(20), id: \.self) { file in
                            Text(relative(file)).font(.footnote.monospaced()).lineLimit(1)
                        }
                        if changes.files.count > 20 {
                            Text("还有 \(changes.files.count - 20) 个").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                if changes != nil {
                    Section {
                        if confirm.kind == .restore {
                            Toggle(isOn: $alsoDrop) {
                                Text(confirm.later > 0 ? LocalizedStringKey("同时删除这一轮和之后的对话") : "同时删除这一轮")
                            }
                        } else {
                            Toggle("同时还原代码", isOn: $withCode)
                        }
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(button, role: confirm.kind == .regenerate ? nil : .destructive) {
                        run()
                        dismiss()
                    }
                    .disabled(confirm.kind == .restore && changes == nil)
                }
            }
            .task { await ask() }
        }
        .presentationDetents([.medium, .large])
    }

    private func ask() async {
        do {
            changes = try await model.request("codechanges", ["target": confirm.user.id], as: CodeChanges?.self)
        } catch {
            failed = error.localizedDescription
        }
        asked = true
    }

    private func run() {
        let target = confirm.user.id
        let code = changes != nil && withCode
        switch confirm.kind {
        case .regenerate: model.perform("regenerate", ["target": target, "code": code])
        case .drop: model.perform("drop", ["target": target, "code": code])
        case .restore: model.perform("restorecode", ["target": target, "drop": alsoDrop])
        }
    }

    private var title: String {
        switch confirm.kind {
        case .regenerate: String(localized: "重新生成这一轮？")
        case .drop: String(localized: "删除这一轮？")
        case .restore: String(localized: "还原代码？")
        }
    }

    private var button: String {
        switch confirm.kind {
        case .regenerate: String(localized: "重新生成")
        case .drop: String(localized: "删除")
        case .restore: String(localized: "还原")
        }
    }

    private var summary: String {
        let later = confirm.later
        switch confirm.kind {
        case .regenerate:
            return later > 0
                ? String(localized: "这条消息之后的回答和之后的 \(later) 轮对话都会去掉，再照原样重新发送。")
                : String(localized: "这条消息之后的回答会去掉，再照原样重新发送。")
        case .drop:
            return later > 0
                ? String(localized: "这一轮和之后的 \(later) 轮对话都会删掉。")
                : String(localized: "这一轮对话会删掉。")
        case .restore:
            if let failed { return String(localized: "查不到要还原的文件：\(failed)") }
            if !asked { return String(localized: "正在查这条消息之后改过的文件…") }
            if changes == nil { return String(localized: "这条消息之后没有可还原的文件改动。") }
            return String(localized: "把文件改回这条消息发出之前的样子。")
        }
    }

    private func relative(_ file: String) -> String {
        let base = cwd.hasSuffix("/") ? cwd : cwd + "/"
        return !cwd.isEmpty && file.hasPrefix(base) ? String(file.dropFirst(base.count)) : file
    }
}

/// 改写一条消息：发出后剪掉它和之后的内容，换成改过的（conv.rewrite）。附带的文件路径照旧带上；图片不带（还没做取回）。
struct RewriteSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let item: Item
    @State private var text = ""

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .padding(.horizontal)
                .navigationTitle("改写消息")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消", role: .cancel) { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("发送") {
                            model.perform("rewrite", ["target": item.id, "text": text, "files": item.files ?? []])
                            dismiss()
                        }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                .onAppear { text = item.text }
        }
        .presentationDetents([.medium, .large])
    }
}
