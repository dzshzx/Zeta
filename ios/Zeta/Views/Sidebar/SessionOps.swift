import SwiftUI
import UIKit

/// 会话操作里要先确认或要输入的：删除、结束的确认，改名，导出后的分享面板（同桌面版 sessionops.go）。
struct SessionOps {
    enum Kind {
        case delete, end
    }

    var kind: Kind?
    var targets: [SessionRow] = []
    var renaming: SessionRow?
    var title = ""
    var exported: ExportedFile?

    /// 要确认的操作 kind，对 list 做；list 是空的时什么也不做。
    mutating func ask(_ kind: Kind, _ list: [SessionRow]) {
        guard !list.isEmpty else { return }
        targets = list
        self.kind = kind
    }

    mutating func rename(_ row: SessionRow) {
        renaming = row
        title = row.title
    }
}

/// 导出成的文件（临时目录里），交给分享面板。
struct ExportedFile: Identifiable {
    let url: URL
    var id: URL { url }
}

/// 长按一行的菜单：置顶、改名、归档、结束、导出、删除。
struct SessionMenu: View {
    @Environment(AppModel.self) private var model
    let row: SessionRow
    @Binding var ops: SessionOps

    var body: some View {
        Button(row.pinned ? "取消置顶" : "置顶", systemImage: row.pinned ? "pin.slash" : "pin") {
            model.perform("pin", row.ref)
        }
        Button("重命名", systemImage: "pencil") { ops.rename(row) }
        Button(row.archived ? "取消归档" : "归档", systemImage: "archivebox") {
            model.perform("archive", ["sessions": [row.ref], "archived": !row.archived])
        }
        if !row.ended {
            Button("结束对话", systemImage: "stop.circle") { ops.ask(.end, [row]) }
        }
        Button("导出", systemImage: "square.and.arrow.up") { export([row], into: $ops, model: model) }
        Divider()
        Button("删除", systemImage: "trash", role: .destructive) { ops.ask(.delete, [row]) }
    }
}

/// 导出 list：等核心交回 Markdown，存进临时目录的 export/（每次换掉上一次的），弹出分享面板。
@MainActor
func export(_ list: [SessionRow], into ops: Binding<SessionOps>, model: AppModel) {
    Task {
        do {
            let file = try await model.request("export", ["sessions": list.map(\.ref)], as: Exported.self)
            // 只留这一次要分享的那份：上一次导出的（分享面板早已关上）先清掉
            let dir = FileManager.default.temporaryDirectory.appending(path: "export", directoryHint: .isDirectory)
            try? FileManager.default.removeItem(at: dir)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let url = dir.appending(path: file.name)
            try Data(file.content.utf8).write(to: url, options: .atomic)
            ops.wrappedValue.exported = ExportedFile(url: url)
        } catch {
            model.alert = error.localizedDescription
        }
    }
}

extension View {
    /// 挂上会话操作的确认框、改名框和分享面板。
    func sessionOps(_ ops: Binding<SessionOps>) -> some View {
        modifier(SessionOpsDialogs(ops: ops))
    }
}

private struct SessionOpsDialogs: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var ops: SessionOps

    private var confirmTitle: String {
        let count = ops.targets.count
        switch ops.kind {
        case .delete: return count == 1 ? String(localized: "删除这个对话？") : String(localized: "删除 \(count) 个对话？")
        case .end: return count == 1 ? String(localized: "结束这个对话？") : String(localized: "结束 \(count) 个对话？")
        case nil: return ""
        }
    }

    private var confirmMessage: String {
        switch ops.kind {
        case .delete: String(localized: "将一并删除会话记录及 Claude Code 保存的相关数据，且无法恢复。进行中的对话无法删除。")
        case .end: ops.targets.contains(where: \.external)
            ? String(localized: "结束 Claude Code 进程，保留对话内容并在下次发送消息时恢复。其他位置运行的会话也会被一并结束，无论其是否空闲。")
            : String(localized: "结束 Claude Code 进程，保留对话内容并在下次发送消息时恢复。")
        case nil: ""
        }
    }

    func body(content: Content) -> some View {
        content
            .confirmationDialog(
                confirmTitle, isPresented: Binding { ops.kind != nil } set: { if !$0 { ops.kind = nil } },
                titleVisibility: .visible
            ) {
                switch ops.kind {
                case .delete:
                    Button("删除", role: .destructive) { run("delete") }
                case .end:
                    Button("结束", role: .destructive) { run("end") }
                case nil:
                    EmptyView()
                }
            } message: {
                Text(confirmMessage)
            }
            .alert("重命名", isPresented: Binding { ops.renaming != nil } set: { if !$0 { ops.renaming = nil } }) {
                TextField("标题", text: $ops.title)
                Button("取消", role: .cancel) { ops.renaming = nil }
                Button("好") {
                    if let row = ops.renaming {
                        model.perform("rename", ["backend": row.backend, "id": row.id, "title": ops.title])
                    }
                    ops.renaming = nil
                }
            }
            .sheet(item: $ops.exported) { file in
                ShareSheet(items: [file.url])
                    .presentationDetents([.medium, .large])
            }
    }

    private func run(_ method: String) {
        model.perform(method, ["sessions": ops.targets.map(\.ref)])
        ops.kind = nil
        ops.targets = []
    }
}

/// 系统的分享面板。
private struct ShareSheet: UIViewControllerRepresentable {
    let items: [URL]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
