import SwiftUI

/// MCP 服务器（同桌面版 mcp.go）：配置里的每个服务器和它的状态（claude mcp list 的健康检查），新加的放进用户配置。
/// 密钥只说有没有，不显示；Token 只在改的时候输入。
struct McpView: View {
    let calls: ConfigCalls
    let cwd: String
    @State private var servers: [McpServer] = []
    @State private var loaded = false
    @State private var status: McpStatus?
    @State private var checking = false
    @State private var statusProblem = ""
    @State private var adding = false
    @State private var failure: ConfigFailure?

    var body: some View {
        Form {
            Section {
                if !loaded {
                    LoadingRow()
                } else if servers.isEmpty {
                    TitledRow(
                        title: String(localized: "暂无 MCP 服务器"),
                        summary: String(localized: "点击右上角“添加服务器”"))
                }
                ForEach(servers, id: \.key) { server in
                    NavigationLink {
                        McpServerView(calls: calls, cwd: cwd, server: server, state: stateOf(server), changed: reload)
                    } label: {
                        LabeledContent {
                            Text(McpLabels.state(stateOf(server)))
                        } label: {
                            TitledRow(title: server.name, summary: Self.summary(server))
                        }
                    }
                }
            } header: {
                Text("服务器 · \(String(servers.count))")
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    Text("新增服务器适用于所有项目，修改仅对之后新建的对话生效。")
                    if !statusProblem.isEmpty { Text("状态检查失败：\(statusProblem)") }
                }
            }
            if let connectors = status?.connectors, !connectors.isEmpty {
                Section {
                    ForEach(connectors, id: \.name) { connector in
                        LabeledContent(
                            connector.name.replacingOccurrences(of: "claude.ai ", with: ""),
                            value: McpLabels.state(connector.state))
                    }
                } header: {
                    Text("Claude 账号的连接器")
                } footer: {
                    Text("在 claude.ai 中管理，此处仅支持查看。")
                }
            }
        }
        .navigationTitle("MCP 服务器")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("重新检查", systemImage: "arrow.clockwise") { Task { await check() } }
                    .disabled(checking)
            }
            ToolbarItem(placement: .primaryAction) {
                Button("添加服务器", systemImage: "plus") { adding = true }
            }
        }
        .sheet(isPresented: $adding) {
            AddMcpView(calls: calls, added: reload)
        }
        .task { await load(check: true) }
        .refreshable { await load(check: true) }
        .configFailure($failure)
    }

    private func reload() { Task { await load(check: true) } }

    private func load(check: Bool) async {
        do {
            servers = try await calls.get("config.mcp.list", ["cwd": cwd], as: ListOf<McpServer>.self).items
            loaded = true
        } catch {
            failure = ConfigFailure(String(localized: "读取 MCP 配置失败"), error)
            return
        }
        if check { await self.check() }
    }

    private func check() async {
        checking = true
        status = nil
        statusProblem = ""
        defer { checking = false }
        do {
            status = try await calls.slow("config.mcp.status", ["cwd": cwd], as: McpStatus.self)
        } catch {
            statusProblem = error.localizedDescription
        }
    }

    /// 列表和详情里显示的状态：被同名服务器盖住的不看健康检查；nil 是还在检查。
    private func stateOf(_ server: McpServer) -> McpState? {
        if !server.shadowedBy.isEmpty {
            let scope = McpLabels.scope(server.shadowedBy)
            return McpState(state: "shadowed", issue: String(localized: "已被“\(scope)”中的同名服务器覆盖"))
        }
        if let found = status?.servers?[server.key] { return found }
        return status != nil || !statusProblem.isEmpty ? McpState(state: "unknown", issue: "") : nil
    }

    /// 不是用户配置的先写范围，再是地址或命令。
    static func summary(_ server: McpServer) -> String {
        let prefix = server.scope == "user" ? "" : McpLabels.scope(server.scope) + " · "
        return prefix + ConfigLabels.clip(server.target, 80)
    }
}

enum McpLabels {
    static func scope(_ scope: String) -> String {
        switch scope {
        case "user": String(localized: "所有项目")
        case "local": String(localized: "仅限默认工作目录")
        case "project": String(localized: "项目的 .mcp.json")
        default: scope
        }
    }

    static func type(_ type: String) -> String {
        switch type {
        case "http": "HTTP"
        case "sse": "SSE"
        case "stdio": String(localized: "命令")
        case "ws": "WebSocket"
        default: type
        }
    }

    static func state(_ state: McpState?) -> String {
        guard let state else { return String(localized: "检查中…") }
        let labels = [
            "connected": String(localized: "已连接"),
            "partial": String(localized: "已连接 · 获取工具失败"),
            "failed": String(localized: "无法连接"),
            "auth": String(localized: "需要登录"),
            "pending": String(localized: "等待批准"),
            "rejected": String(localized: "已拒绝"),
            "disabled": String(localized: "已停用"),
            "unconfigured": String(localized: "未配置"),
            "shadowed": String(localized: "未生效")
        ]
        return labels[state.state] ?? String(localized: "未知")
    }

    static func auth(_ server: McpServer) -> String {
        if server.bearer { return "Bearer Token" }
        if server.authHeader { return String(localized: "Authorization 请求头") }
        if server.oauth { return String(localized: "OAuth：请在终端中通过 claude 的 /mcp 登录") }
        return String(localized: "未配置")
    }
}

/// 一个 MCP 服务器：状态、范围、类型、地址或命令、身份认证（换 Token）、工具清单、删除。
struct McpServerView: View {
    let calls: ConfigCalls
    let cwd: String
    let server: McpServer
    let state: McpState?
    let changed: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var tools: [McpTool]?
    @State private var toolsBusy = false
    @State private var toolsProblem = ""
    @State private var changingToken = false
    @State private var token = ""
    @State private var removing = false
    @State private var working = false
    @State private var failure: ConfigFailure?

    private var editable: Bool { server.scope == "user" || server.scope == "local" }

    var body: some View {
        Form {
            Section("服务器") {
                LabeledContent {
                    Text(McpLabels.state(state))
                } label: {
                    TitledRow(title: String(localized: "状态"), summary: state?.issue ?? "")
                }
                LabeledContent("范围", value: McpLabels.scope(server.scope))
                LabeledContent("类型", value: McpLabels.type(server.type))
                detailRows
            }
            ToolsSection(
                tools: tools, busy: toolsBusy, problem: toolsProblem, oauth: server.oauth,
                load: { Task { await loadTools() } },
                row: { tool in TitledRow(title: tool.name, summary: ConfigLabels.clip(tool.description, 120)) })
            if server.scope != "project" {
                Section {
                    Button("删除服务器", role: .destructive) { removing = true }
                        .disabled(working)
                } footer: {
                    Text("将从配置中移除其地址、Token 和相关设置")
                }
            }
        }
        .navigationTitle(server.name)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("刷新工具", systemImage: "arrow.clockwise") { Task { await loadTools() } }
                    .disabled(toolsBusy || server.oauth)
            }
        }
        .task {
            // 项目里的 .mcp.json 可能来自别人的仓库，不自动替它启动命令
            if tools == nil, server.scope != "project", !server.oauth { await loadTools() }
        }
        .alert("身份认证", isPresented: $changingToken) {
            SecureField("Bearer Token", text: $token)
            Button("取消", role: .cancel) { token = "" }
            Button("保存") { setToken() }
        } message: {
            Text("输入新 Token，留空则移除身份认证。现有 Token 不会明文显示。")
        }
        .confirmationDialog("删除服务器", isPresented: $removing, titleVisibility: .visible) {
            Button("删除", role: .destructive) { remove() }
        } message: {
            Text("删除 \(server.name)？其地址、Token 和相关设置将从配置中移除，且无法撤销。")
        }
        .configFailure($failure)
    }

    @ViewBuilder private var detailRows: some View {
        if server.type == "stdio" {
            TitledRow(title: String(localized: "命令"), summary: server.target)
            if !server.envKeys.isEmpty {
                LabeledContent {
                    Text("值已隐藏")
                } label: {
                    TitledRow(
                        title: String(localized: "环境变量"),
                        summary: server.envKeys.joined(separator: String(localized: "、")))
                }
            }
        } else {
            TitledRow(title: String(localized: "地址"), summary: server.url).textSelection(.enabled)
            if !server.headerKeys.isEmpty {
                LabeledContent {
                    Text("值已隐藏")
                } label: {
                    TitledRow(
                        title: String(localized: "其他请求头"),
                        summary: server.headerKeys.joined(separator: String(localized: "、")))
                }
            }
            let auth = McpLabels.auth(server)
            HStack {
                TitledRow(
                    title: String(localized: "身份认证"),
                    summary: editable ? auth : String(localized: "\(auth) · 请在项目中修改"))
                Spacer()
                Button("更换") {
                    token = ""
                    changingToken = true
                }
                .buttonStyle(.bordered)
                .disabled(!editable || working)
            }
        }
    }

    private var params: [String: any Sendable] { ["cwd": cwd, "scope": server.scope, "name": server.name] }

    private func loadTools() async {
        toolsBusy = true
        toolsProblem = ""
        defer { toolsBusy = false }
        do {
            tools = try await calls.slow("config.mcp.tools", params, as: ListOf<McpTool>.self).items
        } catch {
            toolsProblem = error.localizedDescription
        }
    }

    private func setToken() {
        var all = params
        all["token"] = token
        token = ""
        working = true
        Task {
            defer { working = false }
            do {
                _ = try await calls.slow("config.mcp.setToken", all, as: Ignored.self)
                changed()
                await loadTools()
            } catch {
                failure = ConfigFailure(String(localized: "修改失败"), error)
            }
        }
    }

    private func remove() {
        working = true
        Task {
            defer { working = false }
            do {
                _ = try await calls.slow("config.mcp.remove", params, as: Ignored.self)
                changed()
                dismiss()
            } catch {
                failure = ConfigFailure(String(localized: "删除失败"), error)
            }
        }
    }
}

/// 「工具」一组（MCP 详情页和工具权限的服务器页共用）：在取、取不到、还没取、没有工具，或每个工具一行。
struct ToolsSection<Row: View>: View {
    let tools: [McpTool]?
    let busy: Bool
    let problem: String
    let oauth: Bool
    let load: () -> Void
    var footer = ""
    @ViewBuilder let row: (McpTool) -> Row

    var body: some View {
        Section {
            if busy {
                LoadingRow(text: "正在连接…")
            } else if !problem.isEmpty {
                HStack {
                    TitledRow(title: String(localized: "无法获取工具列表"), summary: problem)
                    Spacer()
                    Button("重试", action: load).buttonStyle(.bordered)
                }
            } else if let tools {
                if tools.isEmpty { Text("暂无工具").foregroundStyle(.secondary) }
                ForEach(tools, id: \.name) { row($0) }
            } else {
                HStack {
                    TitledRow(
                        title: String(localized: "列出工具"),
                        summary: oauth
                            ? String(localized: "暂不支持获取 OAuth 登录服务器的工具列表")
                            : String(localized: "连接服务器以获取工具列表"))
                    Spacer()
                    Button("列出", action: load).buttonStyle(.bordered).disabled(oauth)
                }
            }
        } header: {
            if let tools { Text("工具 · \(String(tools.count))") } else { Text("工具") }
        } footer: {
            if !footer.isEmpty { Text(footer) }
        }
    }
}
