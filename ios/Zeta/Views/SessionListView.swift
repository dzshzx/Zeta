import SwiftUI

/// 会话列表：置顶的一组在上，其余按核心排好的先后；顶栏左边选列哪台设备，右边新对话和设置。
struct SessionListView: View {
    @Environment(AppModel.self) private var model
    @Binding var selection: Route?
    @Binding var settingsShown: Bool

    private var rows: [SessionRow] { model.state.sessions.filter { !$0.archived } }

    private func rowView(_ row: SessionRow) -> some View {
        SessionRowView(row: row, showDevice: model.state.listed.isEmpty)
            .tag(Route.session(backend: row.backend, id: row.id))
    }

    var body: some View {
        List(selection: $selection) {
            if !model.state.problem.isEmpty {
                Label(model.state.problem, systemImage: "wifi.exclamationmark")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            let pinned = rows.filter(\.pinned)
            if !pinned.isEmpty {
                Section("置顶") {
                    ForEach(pinned, id: \.key) { row in
                        rowView(row)
                    }
                }
            }
            let others = rows.filter { !$0.pinned }
            if !others.isEmpty {
                Section("对话") {
                    ForEach(others, id: \.key) { row in
                        rowView(row)
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .navigationTitle("Zeta")
        .refreshable { model.perform("refresh") }
        .overlay {
            if model.state.devices.isEmpty {
                ContentUnavailableView {
                    Label("还没有设备", systemImage: "desktopcomputer")
                } description: {
                    Text("添加一台装了 Claude Code 的 Linux 设备，经 SSH 在上面开对话。")
                } actions: {
                    Button("添加设备") { settingsShown = true }
                        .buttonStyle(.glassProminent)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                DevicePicker()
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("设置", systemImage: "gearshape") { settingsShown = true }
                Button("新对话", systemImage: "square.and.pencil") {
                    selection = .new(backend: model.state.backend)
                }
                .disabled(model.state.backend.isEmpty)
            }
        }
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

private struct SessionRowView: View {
    @Environment(AppModel.self) private var model
    let row: SessionRow
    let showDevice: Bool

    var body: some View {
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
    }
}
