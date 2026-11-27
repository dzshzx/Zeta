import SwiftUI

/// 任务页里往下走的一页：任务详情或子代理。
enum TaskPage: Hashable {
    case task(String)
    case subagent(String)
}

extension View {
    /// 任务详情和子代理页的去处（放在任务页所在的 NavigationStack 里）。
    func taskPages() -> some View {
        navigationDestination(for: TaskPage.self) { page in
            switch page {
            case .task(let id): TaskDetailView(taskID: id)
            case .subagent(let id): SubagentView(toolUseID: id)
            }
        }
    }
}

/// 后台任务面板（同手机版 BackgroundTasksScreen、桌面版 alltasks.go）：上面是后台对话（点了切过去），
/// 下面是聊天页这个对话的后台任务（点了看详情）。别的对话的任务要打开它才拿得到。
struct BackgroundTasksView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let open: (Route) -> Void

    var body: some View {
        let chats = model.state.side.background
        List {
            Section {
                if chats.isEmpty {
                    Text("没有在后台进行的对话").foregroundStyle(.secondary)
                }
                ForEach(chats, id: \.key) { chat in
                    Button { open(.session(backend: chat.backend, id: chat.id)) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(chat.title.isEmpty ? chat.id : chat.title).lineLimit(1)
                            Text(describe(chat)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .foregroundStyle(.primary)
                }
            } header: {
                Text("后台对话")
            } footer: {
                Text("各台设备上在回答、等你回应或还有后台任务的对话。")
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
        .taskPages()
    }

    private func describe(_ chat: BackgroundChat) -> String {
        var state: String
        switch chat.activity {
        case "waiting": state = String(localized: "等你回应")
        case "answering": state = String(localized: "正在回答")
        default: state = String(localized: "\(chat.tasks) 个后台任务在跑")
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
        .taskPages()
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
                    Text("没有后台任务")
                    Text("Claude 在后台跑的命令、监视器和子代理会列在这里。").font(.caption).foregroundStyle(.secondary)
                }
            }
            ForEach(tasks.filter(\.running) + tasks.filter { !$0.running }) { task in
                NavigationLink(value: TaskPage.task(task.id)) {
                    TaskRow(task: task)
                }
            }
        } header: {
            Text("当前对话：\(title)")
        }
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
                    Text("\(Text(task.stateText)) · \(Text(task.elapsed(now: context.date), format: TaskInfo.elapsedStyle))")
                        .font(.caption)
                        .foregroundStyle(task.state == "failed" ? .red : .secondary)
                }
            }
        }
    }
}

/// 一个任务的详情：状态、命令或指令、输出（后台命令、监视器，看着时核心推来）、子代理的进展和报告；在跑时能停止。
struct TaskDetailView: View {
    @Environment(AppModel.self) private var model
    let taskID: String

    @State private var output: TaskOutput?
    @State private var failed = ""
    @State private var stopAsked = false

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
                ContentUnavailableView("这个任务已经不在了", systemImage: "timer")
            }
        }
        .navigationTitle("任务详情")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { watch(true) }
        .onDisappear { watch(false) }
        .task(id: model.revision) { await load() }
        .confirmationDialog("停止这个任务？", isPresented: $stopAsked, titleVisibility: .visible) {
            Button("停止", role: .destructive) { model.perform("stoptask", ["task": taskID]) }
        }
    }

    private func form(_ task: TaskInfo) -> some View {
        Form {
            Section {
                LabeledContent("状态") { Text(task.stateText) }
                if let type = task.agentType, !type.isEmpty {
                    LabeledContent("类型", value: type)
                }
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    LabeledContent("已运行") {
                        Text(task.elapsed(now: context.date), format: TaskInfo.elapsedStyle)
                    }
                }
                if let uses = task.toolUses {
                    LabeledContent("工具调用") { Text(uses, format: .number) }
                }
                if task.running, let progress = task.progress, !progress.isEmpty {
                    LabeledContent("正在做", value: progress)
                }
                if let id = task.toolUseID, !id.isEmpty {
                    NavigationLink("查看步骤", value: TaskPage.subagent(id))
                }
            } header: {
                Text(task.title)
            }
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
            if task.running && task.stopping != true {
                Section {
                    Button("停止任务", role: .destructive) { stopAsked = true }
                }
            }
        }
    }

    private var outputHeader: String {
        guard let output, output.truncated else { return String(localized: "输出") }
        let total = ByteCountFormatter.string(fromByteCount: output.totalBytes, countStyle: .file)
        return String(localized: "输出（最后 8 KB，共 \(total)）")
    }

    private func outputText(_ task: TaskInfo) -> String {
        if !failed.isEmpty { return String(localized: "没读到输出：\(failed)") }
        guard let output, output.got else {
            return model.state.chat?.process == false ? String(localized: "进程已经没了，读不到输出") : String(localized: "正在读取")
        }
        if !output.error.isEmpty { return String(localized: "没读到输出：\(output.error)") }
        return output.text.isEmpty ? String(localized: "还没有输出") : output.text
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

/// 子代理页（同手机版 SubagentScreen、桌面版 subagent.go）：概要、交给它的指令，下面是它的步骤，和聊天里的条目一样画。
struct SubagentView: View {
    @Environment(AppModel.self) private var model
    let toolUseID: String

    @State private var page: SubagentPage?
    @State private var failed = ""

    var body: some View {
        List {
            Section("概要") {
                LabeledContent("状态", value: stateText)
                if let meta = page?.meta {
                    if !meta.description.isEmpty { LabeledContent("说明", value: meta.description) }
                    if !meta.agentType.isEmpty { LabeledContent("类型", value: meta.agentType) }
                    if !meta.model.isEmpty { LabeledContent("模型", value: meta.model) }
                }
                if let page, page.toolUses > 0 {
                    LabeledContent("工具调用") { Text(page.toolUses, format: .number) }
                }
            }
            if let prompt = page?.prompt, !prompt.isEmpty {
                Section("指令") {
                    DisclosureGroup("交给它的指令") {
                        Text(prompt).font(.callout).textSelection(.enabled)
                    }
                }
            }
            Section("步骤") {
                if let page, !page.items.isEmpty {
                    ForEach(page.items) { ItemView(item: $0) }
                    if page.running {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                } else {
                    Text(noSteps).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("子代理")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.perform("watch", ["kind": "subagent", "id": toolUseID, "on": true]) }
        .onDisappear { model.perform("watch", ["kind": "subagent", "id": toolUseID, "on": false]) }
        .task(id: model.revision) { await load() }
    }

    private var stateText: String {
        switch page?.state ?? "" {
        case "running": String(localized: "运行中")
        case "completed": String(localized: "已完成")
        case "failed": String(localized: "失败")
        case "stopped": String(localized: "已停止")
        case "missing": String(localized: "还没有记录")
        case "read_failed": String(localized: "没读成")
        case "loading", "": String(localized: "正在读取")
        default: String(localized: "没有做完")
        }
    }

    private var noSteps: String {
        if !failed.isEmpty { return String(localized: "没看上：\(failed)") }
        switch page?.state ?? "" {
        case "missing": return String(localized: "它的记录文件还没有")
        case "read_failed": return page?.failure ?? ""
        case "loading", "": return String(localized: "正在读取")
        default: return String(localized: "还没有步骤")
        }
    }

    private func load() async {
        do {
            let next = try await model.request("subagent", ["tool_use_id": toolUseID], as: SubagentPage.self)
            if next.rev != page?.rev || page == nil { page = next }
            failed = ""
        } catch {
            failed = error.localizedDescription
        }
    }
}
