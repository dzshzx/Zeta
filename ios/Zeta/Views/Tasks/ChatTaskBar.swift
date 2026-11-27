import SwiftUI

/// 输入栏上方的两条（同手机版 TodoStrip、BackgroundTaskStrip，桌面版 todostrip.go、taskStrip）：
/// 任务清单条，一行说做到第几项、正在做哪一项，点开是整份清单；全部做完、这一轮也结束了就不显示，× 收掉这一份，清单再变时重新出来。
/// 后台任务条，有后台任务时出现，说几个在运行，点进去看这个对话的后台任务。
struct ChatTaskBar: View {
    let chat: ChatState

    @State private var dismissed: [Todo]?
    @State private var expanded = false
    @State private var tasksShown = false

    private var todos: [Todo]? {
        let list = chat.todos
        if list.isEmpty || list == dismissed { return nil }
        if !chat.busy && list.allSatisfy({ $0.status == "completed" }) { return nil }
        return list
    }

    var body: some View {
        VStack(spacing: 6) {
            if let todos { todoStrip(todos) }
            if !chat.tasks.isEmpty { taskStrip }
        }
        .sheet(isPresented: $tasksShown) {
            NavigationStack { ChatTasksView() }
        }
    }

    /// 清单条一行里说的：正在做的那一项用进行时，没在做时是下一项。
    private static func line(_ todo: Todo) -> String {
        todo.status == "in_progress" ? todo.activeForm : todo.content
    }

    private func todoStrip(_ list: [Todo]) -> some View {
        let current = list.first { $0.status == "in_progress" } ?? list.first { $0.status == "pending" }
        let done = list.filter { $0.status == "completed" }.count
        let position = min(done + (list.contains { $0.status == "in_progress" } ? 1 : 0), list.count)
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Button { withAnimation { expanded.toggle() } } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "checklist")
                        Text(verbatim: "\(position)/\(list.count)").monospacedDigit()
                        Text(current.map(Self.line) ?? String(localized: "都做完了"))
                            .lineLimit(1)
                        Spacer(minLength: 0)
                        Image(systemName: expanded ? "chevron.down" : "chevron.up")
                    }
                }
                .foregroundStyle(.primary)
                Button("收起", systemImage: "xmark") { dismissed = list }
                    .labelStyle(.iconOnly)
                    .foregroundStyle(.secondary)
            }
            if expanded {
                ForEach(list) { todo in
                    Label {
                        Text(todo.content)
                            .strikethrough(todo.status == "completed")
                            .foregroundStyle(todo.status == "completed" ? .secondary : .primary)
                    } icon: {
                        Image(systemName: todo.status == "completed" ? "checkmark.circle.fill"
                              : todo.status == "in_progress" ? "circle.dotted.circle" : "circle")
                    }
                }
            }
        }
        .font(.footnote)
        .padding(10)
        .glassEffect(.regular, in: .rect(cornerRadius: 16))
    }

    private var taskStrip: some View {
        let running = chat.tasks.filter(\.running)
        return Button { tasksShown = true } label: {
            HStack(spacing: 8) {
                Image(systemName: "timer")
                VStack(alignment: .leading, spacing: 1) {
                    Text(running.isEmpty ? String(localized: "后台任务都结束了")
                         : String(localized: "\(running.count) 个后台任务在运行"))
                    if let last = running.last {
                        Text(last.progress?.isEmpty == false ? last.progress ?? "" : last.title)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
            .font(.footnote)
            .padding(10)
        }
        .foregroundStyle(.primary)
        .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 16))
    }
}
