import SwiftUI

/// 任务页里往下走的一页：任务详情或子代理。
enum TaskPage: Hashable {
    case task(String)
    case subagent(String)
}

extension View {
    /// 任务详情和子代理页的去处（放在任务页所在的 NavigationStack 里）。[close] 关掉任务面板、回到聊天（子代理页的「所属对话」）。
    func taskPages(close: @escaping () -> Void) -> some View {
        navigationDestination(for: TaskPage.self) { page in
            switch page {
            case .task(let id): TaskDetailView(taskID: id)
            case .subagent(let id): SubagentView(toolUseID: id, openChat: close)
            }
        }
    }
}

/// 后台任务面板（同手机版 BackgroundTasksScreen、桌面版 alltasks.go）：上面是后台对话（点了切过去），
/// 下面是聊天页这个对话的后台任务（点了看详情，子代理直接是子代理页）。别的对话的任务要打开它才拿得到。
struct BackgroundTasksView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let open: (Route) -> Void

    var body: some View {
        let chats = model.state.side.background
        List {
            Section {
                if chats.isEmpty {
                    Text("暂无后台运行中的对话").foregroundStyle(.secondary)
                }
                ForEach(chats, id: \.key) { chat in
                    Button { open(.session(backend: chat.backend, id: chat.id)) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 4) {
                                Text(chat.title.isEmpty ? chat.id : chat.title).lineLimit(1)
                                if chat.isAgy { HarnessBadge() }
                            }
                            Text(describe(chat)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .foregroundStyle(.primary)
                }
            } header: {
                Text("后台对话")
            } footer: {
                Text("各设备上正在回复、等待回应或仍有后台任务的对话。")
            }
            if let chat = model.state.chat {
                TaskRows(title: chat.title, tasks: chat.tasks)
            }
        }
        .navigationTitle("后台任务")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
        }
        .taskPages { dismiss() }
    }

    private func describe(_ chat: BackgroundChat) -> String {
        var state: String
        switch chat.activity {
        case "waiting": state = String(localized: "等你回应")
        case "answering": state = String(localized: "正在回答")
        default: state = String(localized: "\(chat.tasks) 个后台任务运行中")
        }
        if model.state.listed.isEmpty {
            state = model.deviceName(chat.backend) + " · " + state
        }
        return state
    }
}

/// 聊天页这个对话的后台任务（同手机版 ui/tasks、桌面版 taskpages.go 的任务列表）。
struct ChatTasksView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            if let chat = model.state.chat {
                TaskRows(title: chat.title, tasks: chat.tasks)
            }
        }
        .navigationTitle("后台任务")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
        }
        .taskPages { dismiss() }
    }
}

/// 一个对话的后台任务一组：在跑的在前。
private struct TaskRows: View {
    let title: String
    let tasks: [TaskInfo]

    var body: some View {
        Section {
            if tasks.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    Text("暂无后台任务")
                    Text("Claude 在后台运行的命令、监视器和子代理将显示在此处。").font(.caption).foregroundStyle(.secondary)
                }
            }
            ForEach(tasks.filter(\.running) + tasks.filter { !$0.running }) { task in
                NavigationLink(value: task.page) {
                    TaskRow(task: task)
                }
            }
        } header: {
            Text("当前对话：\(title)")
        }
    }
}

private extension TaskInfo {
    /// 点了去哪一页：子代理直接是子代理页；还不知道是哪一步发起的（task_started 没到）才看任务详情。
    var page: TaskPage {
        if kind == "agent", let id = toolUseID, !id.isEmpty { return .subagent(id) }
        return .task(id)
    }
}

private struct TaskRow: View {
    let task: TaskInfo

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: TaskDetailView.icon(task.kind))
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(task.title).lineLimit(2)
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let elapsed = Text(task.elapsed(now: context.date), format: TaskInfo.elapsedStyle)
                    Text("\(Text(task.stateText)) · \(elapsed)")
                        .font(.caption)
                        .foregroundStyle(task.state == "failed" ? .red : .secondary)
                }
            }
        }
    }
}

/// 一个任务的详情：状态、命令或指令、输出（后台命令、监视器，看着时核心推来）；在跑时能停止。子代理平常不进这页（列表里点了直接是子代理页）。
struct TaskDetailView: View {
    @Environment(AppModel.self) private var model
    let taskID: String

    @State private var output: TaskOutput?
    @State private var failed = ""

    private var task: TaskInfo? { model.state.chat?.tasks.first { $0.id == taskID } }

    static func icon(_ kind: String) -> String {
        switch kind {
        case "command": "terminal"
        case "monitor": "waveform.path.ecg"
        case "agent": "person.2"
        default: "gearshape.2"
        }
    }

    var body: some View {
        Group {
            if let task {
                form(task)
            } else {
                ContentUnavailableView("该任务已不存在", systemImage: "timer")
            }
        }
        .navigationTitle("任务详情")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { watch(true) }
        .onDisappear { watch(false) }
        .task(id: model.revision) { await load() }
    }

    private func form(_ task: TaskInfo) -> some View {
        Form {
            overview(task)
            if let command = task.command, !command.isEmpty {
                Section(task.kind == "agent" ? LocalizedStringKey("指令") : LocalizedStringKey("命令")) {
                    Text(command).font(.callout.monospaced()).textSelection(.enabled)
                }
            }
            if task.readable {
                Section(outputHeader) {
                    Text(outputText(task)).font(.caption.monospaced()).textSelection(.enabled)
                }
            }
            if let summary = task.summary, !summary.isEmpty {
                Section("结果") { Text(summary).textSelection(.enabled) }
            }
            if let error = task.error, !error.isEmpty {
                Section("出错") { Text(error).foregroundStyle(.red).textSelection(.enabled) }
            }
            if task.running {
                Section { StopTaskButton(task: task) }
            }
        }
    }

    /// 头一节：状态、类型、已运行多久、工具调用、正在做的。
    private func overview(_ task: TaskInfo) -> some View {
        Section {
            LabeledContent("状态") { Text(task.stateText) }
            if let type = task.agentType, !type.isEmpty {
                LabeledContent("类型", value: type)
            }
            TaskElapsedRow(task: task)
            if let uses = task.toolUses {
                LabeledContent("工具调用") { Text(uses, format: .number) }
            }
            if task.running, let progress = task.progress, !progress.isEmpty {
                LabeledContent("当前执行", value: progress)
            }
        } header: {
            Text(task.title)
        }
    }

    private var outputHeader: String {
        guard let output, output.truncated else { return String(localized: "输出") }
        let total = ByteCountFormatter.string(fromByteCount: output.totalBytes, countStyle: .file)
        return String(localized: "输出（最后 8 KB，共 \(total)）")
    }

    private func outputText(_ task: TaskInfo) -> String {
        if !failed.isEmpty { return String(localized: "无法读取输出：\(failed)") }
        guard let output, output.got else {
            return model.state.chat?.process == false ? String(localized: "进程已结束，无法读取输出") : String(localized: "正在读取")
        }
        if !output.error.isEmpty { return String(localized: "无法读取输出：\(output.error)") }
        return output.text.isEmpty ? String(localized: "暂无输出") : output.text
    }

    private func watch(_ watching: Bool) {
        guard task?.readable == true else { return }
        model.perform("watch", ["kind": "output", "id": taskID, "on": watching])
    }

    private func load() async {
        guard task?.readable == true else { return }
        do {
            output = try await model.request("taskoutput", ["task": taskID], as: TaskOutput.self)
            failed = ""
        } catch {
            failed = error.localizedDescription
        }
    }
}

/// 任务的「已运行」或「用时」一行，在跑时每秒走一次（任务详情页和子代理页共用）。
struct TaskElapsedRow: View {
    let task: TaskInfo

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            LabeledContent(task.running ? LocalizedStringKey("已运行") : LocalizedStringKey("用时")) {
                Text(task.elapsed(now: context.date), format: TaskInfo.elapsedStyle)
            }
        }
    }
}

/// 停止一个在跑的任务的按钮，点了先确认；正在停止、对话的进程没了时点不了（任务详情页和子代理页共用）。
struct StopTaskButton: View {
    @Environment(AppModel.self) private var model
    let task: TaskInfo
    @State private var asked = false

    var body: some View {
        Button(
            task.stopping == true ? LocalizedStringKey("正在停止…") : LocalizedStringKey("停止这个任务"),
            role: .destructive
        ) { asked = true }
        .disabled(task.stopping == true || model.state.chat?.process == false)
        .confirmationDialog("停止这个任务？", isPresented: $asked, titleVisibility: .visible) {
            Button("停止", role: .destructive) { model.perform("stoptask", ["task": task.id]) }
        }
    }
}
