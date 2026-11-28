import SwiftUI

struct AgySettingsView: View {
    let calls: ConfigCalls
    @State private var settings: AgySettings?
    @State private var working = false
    @State private var failure: ConfigFailure?
    @State private var planner = ""
    @State private var executor = ""
    @State private var proxyHTTP = ""
    @State private var proxyHTTPS = ""
    @State private var proxyNo = ""
    @State private var proxyUser = ""
    @State private var proxyPass = ""
    @State private var customModels = ""
    @State private var gcpProject = ""
    @State private var gcpLocation = ""
    @State private var workspaces = ""

    private static let toolPerms = ["", "request-review", "proceed-in-sandbox", "always-proceed", "strict"]
    private static let reviewPols = ["", "asks-for-review", "agent-decides", "always-proceed"]
    private static let queuePols = ["", "queue", "send-immediately"]

    var body: some View {
        Form {
            if let current = settings {
                policySection(current)
                orchestratorSection
                proxySection(current)
                workspacesSection
            } else {
                LoadingRow()
            }
        }
        .navigationTitle("Antigravity 高级设置")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") { saveFields() }
                    .disabled(settings == nil || working)
            }
        }
        .task { await load() }
        .configFailure($failure)
    }

    @ViewBuilder
    private func policySection(_ cur: AgySettings) -> some View {
        Section("权限与沙箱") {
            Picker("工具权限策略", selection: stringBinding(cur.toolPermission, key: "toolPermission")) {
                ForEach(Self.toolPerms, id: \.self) { Text($0.isEmpty ? "默认" : $0).tag($0) }
            }
            Toggle("终端沙箱", isOn: boolBinding(cur.enableTerminalSandbox, key: "enableTerminalSandbox"))
            Toggle("允许访问工作区外文件", isOn: boolBinding(cur.allowNonWorkspaceAccess, key: "allowNonWorkspaceAccess"))
            Picker("产物审查策略", selection: stringBinding(cur.artifactReviewPolicy, key: "artifactReviewPolicy")) {
                ForEach(Self.reviewPols, id: \.self) { Text($0.isEmpty ? "默认" : $0).tag($0) }
            }
            Toggle("使用 Google One AI Credits", isOn: boolBinding(cur.useG1Credits, key: "useG1Credits"))
            Picker("排队消息处理", selection: stringBinding(cur.queuedMessages, key: "queuedMessages")) {
                ForEach(Self.queuePols, id: \.self) { Text($0.isEmpty ? "默认" : $0).tag($0) }
            }
        }
        .disabled(working)
    }

    private var orchestratorSection: some View {
        Section("双模型编排、自定义模型与 GCP") {
            TextField("Planner (orchestrator.planner)", text: $planner)
            TextField("Executor (orchestrator.executor)", text: $executor)
            TextField("自定义模型配置路径", text: $customModels)
            TextField("GCP Project", text: $gcpProject)
            TextField("GCP Location", text: $gcpLocation)
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
    }

    @ViewBuilder
    private func proxySection(_ cur: AgySettings) -> some View {
        Section("沙箱代理") {
            TextField("HTTP Proxy", text: $proxyHTTP)
            TextField("HTTPS Proxy", text: $proxyHTTPS)
            TextField("No Proxy", text: $proxyNo)
            TextField("Username", text: $proxyUser)
            SecureField(cur.sandboxProxy.hasPassword ? "已设置密码（留空保持不变）" : "可选密码", text: $proxyPass)
            if cur.sandboxProxy.hasPassword {
                Button("清除已保存的代理密码", role: .destructive) {
                    patch(["sandboxProxy": ["clearPassword": true]])
                }
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
    }

    private var workspacesSection: some View {
        Section {
            TextEditor(text: $workspaces)
                .frame(minHeight: 80)
                .font(.footnote.monospaced())
        } header: {
            Text("可信工作区")
        } footer: {
            Text("每行填写一个绝对目录路径。")
        }
    }

    private func stringBinding(_ current: String, key: String) -> Binding<String> {
        Binding(get: { current }, set: { patch([key: $0]) })
    }

    private func boolBinding(_ current: Bool, key: String) -> Binding<Bool> {
        Binding(get: { current }, set: { patch([key: $0]) })
    }

    private func load() async {
        do {
            apply(try await calls.get("config.agy.settings.read", as: AgySettings.self))
        } catch {
            failure = ConfigFailure(String(localized: "读取 Antigravity 设置失败"), error)
        }
    }

    private func apply(_ data: AgySettings) {
        settings = data
        planner = data.orchestrator.planner
        executor = data.orchestrator.executor
        proxyHTTP = data.sandboxProxy.http
        proxyHTTPS = data.sandboxProxy.https
        proxyNo = data.sandboxProxy.noProxy
        proxyUser = data.sandboxProxy.username
        proxyPass = ""
        customModels = data.customModelsConfig
        gcpProject = data.gcp.project
        gcpLocation = data.gcp.location
        workspaces = data.trustedWorkspaces.joined(separator: "\n")
    }

    private func saveFields() {
        let dirs = workspaces
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        var proxy: [String: any Sendable] = [
            "http": proxyHTTP, "https": proxyHTTPS, "noProxy": proxyNo, "username": proxyUser
        ]
        if !proxyPass.isEmpty { proxy["password"] = proxyPass }
        patch([
            "orchestrator": ["planner": planner, "executor": executor],
            "sandboxProxy": proxy,
            "trustedWorkspaces": dirs,
            "customModelsConfig": customModels,
            "gcp": ["project": gcpProject, "location": gcpLocation]
        ])
    }

    private func patch(_ fields: [String: any Sendable]) {
        guard !working else { return }
        working = true
        Task {
            defer { working = false }
            do {
                let updated = try await calls.get("config.agy.settings.update", ["patch": fields], as: AgySettings.self)
                apply(updated)
            } catch {
                failure = ConfigFailure(String(localized: "保存失败"), error)
            }
        }
    }
}
