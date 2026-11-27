import SwiftUI

/// 会话列表：置顶的一组在上，其余按核心排好的先后（排序在顶栏菜单里选）；在搜时换成搜索结果。
/// 行上左滑归档、删除，右滑置顶，长按有全部操作；「选择」进多选，底栏批量结束、归档、删除。手动排序时编辑状态下能拖动。
/// 顶上一行进后台任务面板。顶栏左边选列哪台设备，右边新对话和更多（排序、选择、已归档、设置）。
struct SessionListView: View {
    @Environment(AppModel.self) private var model
    @Binding var selection: Route?
    @Binding var settingsShown: Bool

    @State private var query = ""
    @State private var editing = false
    @State private var picked: Set<String> = []
    @State private var archivedShown = false
    @State private var tasksShown = false
    @State private var ops = SessionOps()

    private var all: [SessionRow] { model.state.sessions }
    private var rows: [SessionRow] { all.filter { !$0.archived } }

    var body: some View {
        list
            .listStyle(.sidebar)
            .navigationTitle("Zeta")
            .searchable(text: $query, prompt: Text("搜索对话"))
            .onChange(of: query) { _, text in model.perform("search", ["query": text]) }
            .refreshable { model.perform("refresh") }
            .environment(\.editMode, .constant(editing ? .active : .inactive))
            .overlay { empty }
            .toolbar { toolbar }
            .sessionOps($ops)
            .sheet(isPresented: $archivedShown) {
                NavigationStack { ArchivedView() }
            }
            .sheet(isPresented: $tasksShown) {
                NavigationStack {
                    BackgroundTasksView { route in
                        tasksShown = false
                        selection = route
                    }
                }
            }
    }

    @ViewBuilder
    private var list: some View {
        if editing {
            List(selection: $picked) { content }
        } else {
            List(selection: $selection) { content }
        }
    }

    @ViewBuilder
    private var content: some View {
        if !model.state.problem.isEmpty {
            Banner(text: Text(model.state.problem), icon: "wifi.exclamationmark")
        }
        if model.state.side.searching {
            searchSection
        } else {
            tasksEntry
            section("置顶", rows.filter(\.pinned))
            section("对话", rows.filter { !$0.pinned })
        }
    }

    /// 后台任务面板的入口：后台对话和当前对话在跑的任务合起来的个数。
    @ViewBuilder
    private var tasksEntry: some View {
        let running = model.state.side.background.count + (model.state.chat?.tasks.filter(\.running).count ?? 0)
        if !model.state.devices.isEmpty && !editing {
            Button { tasksShown = true } label: {
                LabeledContent {
                    if running > 0 { Text(running, format: .number) }
                } label: {
                    Label("后台任务", systemImage: "timer")
                }
            }
            .foregroundStyle(.primary)
        }
    }

    private var searchSection: some View {
        Section {
            ForEach(all, id: \.key) { rowView($0) }
        } header: {
            switch model.state.side.search {
            case "running": Text("正在搜索对话内容")
            case "partial": Text("只搜了一部分对话内容")
            case "failed": Text("没搜成对话内容，只比对了标题")
            default: Text("搜索结果")
            }
        }
    }

    /// 一组会话；没有会话时整组不显示。手动排序时能拖动（编辑状态下）。
    @ViewBuilder
    private func section(_ title: LocalizedStringKey, _ list: [SessionRow]) -> some View {
        if !list.isEmpty {
            Section(title) {
                ForEach(list, id: \.key) { rowView($0) }
                    .onMove(perform: model.state.side.sort == "manual" ? mover(list) : nil)
            }
        }
    }

    private func mover(_ list: [SessionRow]) -> (IndexSet, Int) -> Void {
        { from, offset in move(list, from, offset) }
    }

    /// 拖动落下：落在第 offset 行之前，就是那一行的上半；拖到最后是最后一行的下半（hub.Move）。
    private func move(_ list: [SessionRow], _ from: IndexSet, _ offset: Int) {
        guard let index = from.first, !list.isEmpty else { return }
        let row = list[index]
        let below = offset >= list.count
        let target = list[below ? list.count - 1 : offset]
        model.perform("move", ["backend": row.backend, "id": row.id, "below": below,
                               "target": ["backend": target.backend, "id": target.id]])
    }

    private func rowView(_ row: SessionRow) -> some View {
        SessionRowView(row: row, showDevice: model.state.listed.isEmpty)
            .modifier(RowTag(row: row, editing: editing))
            .swipeActions(edge: .trailing) {
                Button("删除", systemImage: "trash", role: .destructive) { ops.ask(.delete, [row]) }
                Button(row.archived ? "取消归档" : "归档", systemImage: "archivebox") {
                    model.perform("archive", ["sessions": [row.ref], "archived": !row.archived])
                }
                .tint(.indigo)
            }
            .swipeActions(edge: .leading) {
                Button(row.pinned ? "取消置顶" : "置顶", systemImage: row.pinned ? "pin.slash" : "pin") {
                    model.perform("pin", row.ref)
                }
                .tint(.orange)
            }
            .contextMenu { SessionMenu(row: row, ops: $ops) }
    }

    @ViewBuilder
    private var empty: some View {
        if model.state.devices.isEmpty {
            ContentUnavailableView {
                Label("还没有设备", systemImage: "desktopcomputer")
            } description: {
                Text("添加一台装了 Claude Code 的 Linux 设备，经 SSH 在上面开对话。")
            } actions: {
                Button("添加设备") { settingsShown = true }
                    .buttonStyle(.glassProminent)
            }
        } else if model.state.side.searching, all.isEmpty, model.state.side.search != "running" {
            ContentUnavailableView.search(text: query)
        }
    }

    private var sortBinding: Binding<String> {
        Binding { model.state.side.sort } set: { model.perform("sort", ["sort": $0]) }
    }

    private var chosen: [SessionRow] { all.filter { picked.contains($0.key) } }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if editing {
            ToolbarItem(placement: .topBarTrailing) {
                Button("完成") {
                    editing = false
                    picked = []
                }
            }
            ToolbarItemGroup(placement: .bottomBar) {
                Button("结束") { ops.ask(.end, chosen.filter { !$0.ended }) }
                    .disabled(!chosen.contains { !$0.ended })
                Spacer()
                Button("归档") {
                    model.perform("archive", ["sessions": chosen.map(\.ref), "archived": true])
                    picked = []
                }
                .disabled(chosen.isEmpty)
                Spacer()
                Button("删除", role: .destructive) { ops.ask(.delete, chosen) }
                    .disabled(chosen.isEmpty)
            }
        } else {
            ToolbarItem(placement: .topBarLeading) {
                DevicePicker()
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Menu("更多", systemImage: "ellipsis.circle") {
                    Picker("排序", selection: sortBinding) {
                        Text("最新回答").tag("replied")
                        Text("发起时间").tag("created")
                        Text("手动排序").tag("manual")
                    }
                    Button("选择", systemImage: "checkmark.circle") { editing = true }
                    Button("已归档", systemImage: "archivebox") { archivedShown = true }
                    Divider()
                    Button("设置", systemImage: "gearshape") { settingsShown = true }
                }
                Button("新对话", systemImage: "square.and.pencil") {
                    selection = .new(backend: model.state.backend)
                }
                .disabled(model.state.backend.isEmpty)
            }
        }
    }
}

/// 行的 tag：多选时是会话的键，平时是点开它的 Route（两种 List 的 selection 类型不同）。
private struct RowTag: ViewModifier {
    let row: SessionRow
    let editing: Bool

    func body(content: Content) -> some View {
        if editing {
            content.tag(row.key)
        } else {
            content.tag(Route.session(backend: row.backend, id: row.id))
        }
    }
}

/// 已归档的会话：取消归档、删除和别的操作。
private struct ArchivedView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var ops = SessionOps()

    var body: some View {
        let list = model.state.sessions.filter(\.archived)
        List {
            ForEach(list, id: \.key) { row in
                SessionRowView(row: row, showDevice: model.state.listed.isEmpty)
                    .swipeActions {
                        Button("删除", systemImage: "trash", role: .destructive) { ops.ask(.delete, [row]) }
                        Button("取消归档", systemImage: "tray.and.arrow.up") {
                            model.perform("archive", ["sessions": [row.ref], "archived": false])
                        }
                    }
                    .contextMenu { SessionMenu(row: row, ops: $ops) }
            }
        }
        .overlay {
            if list.isEmpty {
                ContentUnavailableView("没有归档的对话", systemImage: "archivebox")
            }
        }
        .navigationTitle("已归档")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
        }
        .sessionOps($ops)
    }
}

/// 列哪台设备：全部设备或某一台。新对话开在选中的那台（全部设备时是上次选的那台）。
private struct DevicePicker: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let devices = model.state.devices
        if devices.count > 1 {
            Menu {
                Picker("设备", selection: Binding { model.state.listed } set: { model.perform("list", ["id": $0]) }) {
                    Text("全部设备").tag("")
                    ForEach(devices) { Text($0.name).tag($0.id) }
                }
            } label: {
                Label(model.state.listed.isEmpty ? String(localized: "全部设备") : model.deviceName(model.state.listed),
                      systemImage: "desktopcomputer")
            }
        } else if let only = devices.first {
            Label(only.name, systemImage: "desktopcomputer")
                .labelStyle(.titleAndIcon)
                .font(.subheadline)
        }
    }
}

/// 一行：标题（已结束的灰）、别处运行的标记、行尾的状态；下面在搜时是命中的片段，再下面是设备、目录、时间。
struct SessionRowView: View {
    @Environment(AppModel.self) private var model
    let row: SessionRow
    let showDevice: Bool

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(row.title)
                        .lineLimit(1)
                        .foregroundStyle(row.ended ? .secondary : .primary)
                    if row.external {
                        Image(systemName: "terminal")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel("别处运行")
                    }
                }
                if !row.snippet.isEmpty {
                    Text(row.snippet)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                if !row.draft.isEmpty {
                    Label(row.draft, systemImage: "pencil")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .accessibilityLabel(Text("草稿：\(row.draft)"))
                }
                HStack(spacing: 4) {
                    if showDevice {
                        Text(model.deviceName(row.backend))
                        Text("·")
                    }
                    Text(row.place)
                    Text("·")
                    Text(row.updatedAt, format: .relative(presentation: .named))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            }
            Spacer(minLength: 0)
            ActivityMark(activity: row.activity, tasks: row.tasks)
        }
    }
}

/// 行尾的状态：等你回应、在回答、后台任务在跑、有新回答。
struct ActivityMark: View {
    let activity: String
    let tasks: Int

    var body: some View {
        switch activity {
        case "waiting":
            Image(systemName: "exclamationmark.bubble.fill")
                .foregroundStyle(.orange)
                .accessibilityLabel("等你回应")
        case "answering":
            ProgressView()
                .controlSize(.small)
                .accessibilityLabel("正在回答")
        case "background":
            Label {
                Text(tasks, format: .number)
            } icon: {
                Image(systemName: "timer")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .accessibilityLabel("后台任务在跑")
        case "unread":
            Circle()
                .fill(.tint)
                .frame(width: 8, height: 8)
                .accessibilityLabel("有新回答")
        default:
            EmptyView()
        }
    }
}

extension SessionRow {
    /// 交给核心的会话（backend、id）。
    var ref: [String: String] { ["backend": backend, "id": id] }
}
