import SwiftUI

/// 管理常用指令（同手机版 SavedPromptsUi.kt、桌面版 savedprompts.go）：点一条填进输入框；左滑删除，编辑模式里拖动排序，
/// 长按编辑；「新建」时输入框里有字就带上。存在设置里（hub/settings 的常用指令），各端写法一样。
struct SavedPromptsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let draft: String
    let insert: (String) -> Void

    @State private var prompts = SavedPrompts(list: [], full: false)
    @State private var editing: PromptDraft?

    var body: some View {
        NavigationStack {
            List {
                ForEach(prompts.list) { prompt in
                    Button {
                        insert(prompt.text)
                        dismiss()
                    } label: {
                        row(prompt)
                    }
                    .contextMenu {
                        Button("编辑", systemImage: "pencil") {
                            editing = PromptDraft(id: prompt.id, name: prompt.name, text: prompt.text)
                        }
                    }
                }
                .onDelete { offsets in
                    let ids = offsets.map { prompts.list[$0].id }
                    for id in ids { change("prompts.remove", ["id": id]) }
                }
                .onMove(perform: move)
            }
            .overlay {
                if prompts.list.isEmpty {
                    ContentUnavailableView("暂无常用指令", systemImage: "star", description: Text("保存常用内容，点击即可快速填入输入框。"))
                }
            }
            .navigationTitle("常用指令")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("完成") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("新建", systemImage: "plus") {
                        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : draft
                        editing = PromptDraft(id: "", name: "", text: text)
                    }
                    .disabled(prompts.full)
                }
                ToolbarItem(placement: .secondaryAction) { EditButton() }
            }
            .sheet(item: $editing) { item in
                PromptEditor(draft: item) { saved in
                    let method = saved.id.isEmpty ? "prompts.add" : "prompts.update"
                    return change(method, ["id": saved.id, "name": saved.name, "text": saved.text])
                }
            }
        }
        .onAppear { prompts = model.savedPrompts() }
    }

    private func row(_ prompt: SavedPrompt) -> some View {
        let named = !prompt.name.trimmingCharacters(in: .whitespaces).isEmpty
        return VStack(alignment: .leading, spacing: 2) {
            Text(named ? prompt.name : prompt.text).lineLimit(named ? 1 : 2).foregroundStyle(.primary)
            if named {
                Text(prompt.text).font(.footnote).lineLimit(2).foregroundStyle(.secondary)
            }
        }
    }

    /// 拖动排序：核心一次挪一条（prompts.move 的 delta），onMove 的目标位置是挪之前的下标。
    private func move(_ offsets: IndexSet, _ destination: Int) {
        guard let from = offsets.first else { return }
        let delta = destination > from ? destination - from - 1 : destination - from
        if delta != 0 { change("prompts.move", ["id": prompts.list[from].id, "delta": delta]) }
    }

    @discardableResult
    private func change(_ method: String, _ params: [String: any Sendable]) -> Bool {
        do {
            prompts = try model.editPrompts(method, params)
            return true
        } catch {
            model.alert = error.localizedDescription
            return false
        }
    }
}

struct PromptDraft: Identifiable {
    let id: String
    var name: String
    var text: String
}

/// 新建、编辑一条：名字可以空着（显示时取内容开头）。
private struct PromptEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State var draft: PromptDraft
    let save: (PromptDraft) -> Bool

    var body: some View {
        NavigationStack {
            Form {
                TextField("名称（可选）", text: $draft.name)
                TextField("内容", text: $draft.text, axis: .vertical)
                    .lineLimit(4...12)
            }
            .navigationTitle(draft.id.isEmpty ? String(localized: "新建常用指令") : String(localized: "编辑常用指令"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        if save(draft) { dismiss() }
                    }
                    .disabled(draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
