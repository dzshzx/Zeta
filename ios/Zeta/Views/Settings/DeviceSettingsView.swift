import SwiftUI

/// 一台设备的页面（同手机版的主机页）：「连接」开关；名字、默认工作目录、新对话默认工具；
/// 切换管理 Claude Code 与 Antigravity 的账号与额度、配置；主机指纹；删除。
struct DeviceSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let id: String
    @State private var info: DeviceInfo?
    @State private var selectedHarness: String?
    @State private var naming = false
    @State private var nameText = ""
    @State private var movingCwd = false
    @State private var removing = false

    var body: some View {
        Form {
            if let info {
                sections(info)
            }
        }
        .navigationTitle(info?.name ?? "")
        .task(id: model.revision) { await reload() }
        .alert("名称", isPresented: $naming) {
            TextField(info?.original ?? "", text: $nameText)
            Button("取消", role: .cancel) {}
            Button("好") { model.perform("device.rename", ["id": id, "name": nameText]) }
        } message: {
            Text("留空则使用设备默认名称。")
        }
        .sheet(isPresented: $movingCwd) {
            if let info { CwdEditor(device: info) }
        }
        .confirmationDialog("删除「\(info?.name ?? "")」？", isPresented: $removing, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                model.perform("remove", ["id": id])
                dismiss()
            }
        } message: {
            Text("仅停止 Zeta 与此设备的连接，不会删除设备上的会话记录。")
        }
    }

    @ViewBuilder
    private func sections(_ info: DeviceInfo) -> some View {
        Section {
            Toggle("连接", isOn: Binding { !info.off } set: { model.perform("connect", ["id": id, "on": $0]) })
        } footer: {
            Text("关闭后，Zeta 将不再连接此设备，设备上的对话仍会继续运行。")
        }
        Section {
            Button {
                nameText = info.customName
                naming = true
            } label: {
                LabeledContent("名称", value: info.name)
            }
            .tint(.primary)
            LabeledContent("地址", value: info.target)
            Button {
                movingCwd = true
            } label: {
                LabeledContent("默认工作目录", value: info.defaultCwd.isEmpty ? info.home : info.defaultCwd)
            }
            .tint(.primary)
            Picker("新对话默认工具", selection: defaultHarnessBinding(info)) {
                ForEach(Harness.all, id: \.self) { item in
                    Text(Harness.name(item)).tag(item)
                }
            }
        }
        harnessSection(info)
        Section("主机指纹") {
            Text(info.fingerprint).font(.caption.monospaced()).textSelection(.enabled)
        }
        Section {
            Button("删除设备", role: .destructive) { removing = true }
        }
    }

    private func harnessSection(_ info: DeviceInfo) -> some View {
        let tool = selectedHarness ?? info.defaultHarness
        return Section {
            Picker("管理的工具", selection: Binding { tool } set: { selectedHarness = $0 }) {
                ForEach(Harness.all, id: \.self) { item in
                    Text(Harness.name(item)).tag(item)
                }
            }
            .pickerStyle(.segmented)
            NavigationLink {
                AccountsView(id: id, harness: tool)
            } label: {
                LabeledContent("账号与额度", value: info.off ? "" : info.hint(for: tool))
            }
            .disabled(info.off)
            NavigationLink {
                ClaudeConfigView(backend: id, harness: tool)
            } label: {
                Text(tool == Harness.agy ? "Antigravity 配置" : "Claude Code 配置")
            }
            .disabled(info.off)
        } header: {
            Text("工具与账号")
        } footer: {
            if info.off { Text("开启「连接」后才能查看账号与配置。") }
        }
    }

    private func defaultHarnessBinding(_ info: DeviceInfo) -> Binding<String> {
        Binding {
            info.defaultHarness
        } set: { next in
            model.perform("device.setharness", ["id": id, "harness": next])
            if selectedHarness == nil { selectedHarness = next }
        }
    }

    private func reload() async {
        if let list = try? await model.request("devices", as: [DeviceInfo].self) {
            info = list.first { $0.id == id }
        }
    }
}

/// 改默认工作目录：先到那台设备上核对能不能进去，进得去才存；进不去时写明原因、留着让改；
/// 连不上或「连接」关着时说明核对不了，问仍然保存还是取消（同桌面版）。
private struct CwdEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let device: DeviceInfo
    @State private var text = ""
    @State private var checking = false
    @State private var problem: String?
    @State private var unchecked: CwdCheck?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(device.home, text: $text)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onChange(of: text) { problem = nil }
                } footer: {
                    if checking {
                        Text("正在「\(device.name)」上校验…")
                    } else if let problem {
                        Text(problem).foregroundStyle(.red)
                    } else {
                        Text("在此设备发起新对话时的默认目录。~ 表示主目录 \(device.home)。")
                    }
                }
            }
            .navigationTitle("默认工作目录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("好") { Task { await check() } }
                        .disabled(checking || text.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .alert("无法校验", isPresented: Binding { unchecked != nil } set: { if !$0 { unchecked = nil } }) {
                Button("取消", role: .cancel) {}
                Button("仍然保存") {
                    if let path = unchecked?.path { model.perform("device.setcwd", ["id": device.id, "path": path]) }
                    dismiss()
                }
            } message: {
                Text(uncheckedMessage)
            }
        }
        .onAppear { text = device.defaultCwd.isEmpty ? device.home : device.defaultCwd }
    }

    private var uncheckedMessage: String {
        guard let unchecked else { return "" }
        let why = unchecked.off ? String(localized: "「连接」未开启") : unchecked.unchecked
        return String(localized: "无法在「\(device.name)」上校验目录 \(unchecked.path) 是否可访问：\(why)。仍要保存吗？")
    }

    private func check() async {
        checking = true
        defer { checking = false }
        do {
            let got = try await model.request(
                "device.checkcwd", ["id": device.id, "path": text], as: CwdCheck.self)
            if !got.problem.isEmpty {
                problem = got.problem
            } else if got.off || !got.unchecked.isEmpty {
                unchecked = got
            } else {
                dismiss()
            }
        } catch {
            problem = error.localizedDescription
        }
    }
}
