import SwiftUI

/// 子代理页（同手机版 SubagentScreen、桌面版 subagent.go），样子照手机版：头一组是说明、状态、用时、子代理类型、模型、思考强度、
/// 工具调用、所属对话（知道的才列），在跑时最后是停止；下面是出错的原因、交给它的指令（长的先露 5 行，点了展开）和它的步骤，
/// 步骤和聊天里的条目一样画。用时、停止和出错的原因取自它在后台任务清单里的那一项；核心的第一份还没到时，说明、类型、指令也先用那一项的。
struct SubagentView: View {
    @Environment(AppModel.self) private var model
    let toolUseID: String
    /// 回到所属对话：关掉任务面板。
    let openChat: () -> Void

    @State private var page: SubagentPage?
    @State private var failed = ""
    @State private var stopAsked = false
    @State private var promptOpen = false

    /// 它在后台任务清单里的那一项；不在清单里时没有。
    private var task: TaskInfo? {
        model.state.chat?.tasks.first { $0.kind == "agent" && $0.toolUseID == toolUseID }
    }

    private var title: String {
        if let text = page?.meta?.description, !text.isEmpty { return text }
        if let text = task?.description, !text.isEmpty { return text }
        return String(localized: "子代理")
    }

    private var agentType: String {
        if let type = page?.meta?.agentType, !type.isEmpty { return type }
        return task?.agentType ?? ""
    }

    private var prompt: String {
        if let text = page?.prompt, !text.isEmpty { return text }
        return task?.command ?? ""
    }

    var body: some View {
        List {
            overview
            if let error = task?.error, !error.isEmpty {
                Section("出错") { Text(error).foregroundStyle(.red).textSelection(.enabled) }
            }
            if !prompt.isEmpty {
                Section("交给它的指令") {
                    Text(prompt)
                        .font(.callout)
                        .lineLimit(promptOpen ? nil : 5)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(.rect)
                        .onTapGesture { promptOpen.toggle() }
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
        .confirmationDialog("停止这个任务？", isPresented: $stopAsked, titleVisibility: .visible) {
            Button("停止", role: .destructive) {
                if let task { model.perform("stoptask", ["task": task.id]) }
            }
        }
    }

    /// 头一组：说明、状态、用时、子代理类型、模型、思考强度、工具调用、所属对话（知道的才列），在跑时最后是停止。
    private var overview: some View {
        Section {
            Text(title).font(.headline)
            LabeledContent("状态") { Text(stateText).foregroundStyle(stateColor) }
            if let task {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    LabeledContent(task.running ? LocalizedStringKey("已运行") : LocalizedStringKey("用时")) {
                        Text(task.elapsed(now: context.date), format: TaskInfo.elapsedStyle)
                    }
                }
            }
            facts
            Button(action: openChat) {
                LabeledContent("所属对话", value: model.state.chat?.title ?? "")
            }
            .foregroundStyle(.primary)
            if let task, task.running {
                Button(
                    task.stopping == true ? LocalizedStringKey("正在停止…") : LocalizedStringKey("停止这个任务"),
                    role: .destructive
                ) { stopAsked = true }
                .disabled(task.stopping == true || model.state.chat?.process == false)
            }
        }
    }

    /// 子代理类型、模型、思考强度、工具调用几行，知道的才列。
    @ViewBuilder private var facts: some View {
        if !agentType.isEmpty { LabeledContent("子代理类型", value: agentType) }
        if let used = page?.meta?.model, !used.isEmpty { LabeledContent("模型", value: used) }
        if let effort = page?.meta?.effort, !effort.isEmpty {
            LabeledContent("思考强度", value: ConfigLabels.effort(effort))
        }
        if let page, page.toolUses > 0 {
            LabeledContent("工具调用") { Text(page.toolUses, format: .number) }
        }
    }

    /// 状态的颜色照手机版：在跑是主题色，完成绿色，失败红色，别的不另上色。
    private var stateColor: Color {
        switch page?.state ?? "" {
        case "running": .accentColor
        case "completed": .green
        case "failed": .red
        default: .secondary
        }
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
