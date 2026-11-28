import SwiftUI

/// 一台设备的账号与额度（同手机版的账号页）：订阅额度、账号（切换先确认、删除）、添加账号（登录）、中转。
/// 状态和操作都在核心（accounts.go），这里只画；在用中转时核心不给额度，这里就不画额度。
struct AccountsView: View {
    @Environment(AppModel.self) private var model
    let id: String
    var harness = Harness.claude
    @State private var state: AccountsState?
    @State private var problem: String?
    @State private var switching: AccountRow?
    @State private var switchingRelay: Relay?
    @State private var editing: RelayEditing?

    private var isAgy: Bool { harness == Harness.agy }

    private func params(_ extra: [String: any Sendable] = [:]) -> [String: any Sendable] {
        var all: [String: any Sendable] = ["id": id, "harness": harness]
        all.merge(extra) { _, new in new }
        return all
    }

    var body: some View {
        Form {
            if let state {
                StatusSection(id: id, harness: harness, state: state)
                if let quota = state.quota {
                    QuotaSection(quota: quota) { model.perform("accounts.refresh", params(["force": true])) }
                }
                accountsSection(state)
                relaysSection(state)
            } else if let problem {
                Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(isAgy ? "Antigravity 账号与额度" : "账号与额度")
        .task(id: harness) { model.perform("accounts.open", params()) }
        .task(id: "\(harness)/\(model.revision)") { await reload() }
        .onDisappear { model.perform("accounts.dismiss", params()) }
        .refreshable { model.perform("accounts.refresh", params(["force": true])) }
        .confirmationDialog(
            "切换到「\(switching?.title ?? "")」？", isPresented: present($switching), titleVisibility: .visible
        ) {
            Button("切换") { model.perform("accounts.switch", params(["name": switching?.name ?? ""])) }
        } message: {
            Text("这台设备上闲着的对话进程会先结束，内容不丢，下次发消息时接上。")
        }
        .confirmationDialog(
            "切换到中转「\(switchingRelay?.name ?? "")」？", isPresented: present($switchingRelay), titleVisibility: .visible
        ) {
            Button("切换") { model.perform("relay.switch", params(["name": switchingRelay?.name ?? ""])) }
        } message: {
            Text(isAgy
                ? "这台设备上的 Antigravity 改用这个中转的 Gemini API 密钥和地址；闲着的对话进程会先结束。"
                : "这台设备上的 Claude Code 改用这个中转的地址、密钥和默认模型；闲着的对话进程会先结束。")
        }
        .sheet(isPresented: loginShown) {
            LoginSheet(id: id, harness: harness, login: state?.login, working: state?.working ?? "")
        }
        .sheet(item: $editing) { edit in
            RelayEditor(id: id, harness: harness, start: edit, draft: state?.draft, working: state?.working ?? "")
        }
    }

    private var loginShown: Binding<Bool> {
        Binding { state?.login != nil } set: { shown in
            if !shown { model.perform("accounts.cancel", params()) }
        }
    }

    private func accountsSection(_ state: AccountsState) -> some View {
        Section {
            ForEach(state.accounts) { account in
                Button {
                    if !account.current { switching = account }
                } label: {
                    AccountLabel(account: account)
                }
                .tint(.primary)
                .swipeActions {
                    if !account.current {
                        Button("删除", role: .destructive) {
                            model.perform("accounts.remove", params(["name": account.name]))
                        }
                    }
                }
            }
            if state.unsaved {
                Button("记下在用的账号", systemImage: "square.and.arrow.down") {
                    model.perform("accounts.save", params())
                }
            }
            Button("添加账号", systemImage: "person.badge.plus") { model.perform("accounts.login", params()) }
                .disabled(!state.working.isEmpty)
        } header: {
            Text("账号")
        } footer: {
            Text("删除只删这台设备上存着的一份，那个账号在服务端仍是登录状态。")
        }
    }

    private func relaysSection(_ state: AccountsState) -> some View {
        Section {
            ForEach(state.relays) { relay in
                Button {
                    if !relay.current { switchingRelay = relay }
                } label: {
                    RelayLabel(relay: relay)
                }
                .tint(.primary)
                .swipeActions {
                    if !relay.current {
                        Button("删除", role: .destructive) {
                            model.perform("relay.remove", params(["name": relay.name]))
                        }
                    }
                    Button("编辑") { edit(relay) }
                }
                .contextMenu {
                    Button("编辑", systemImage: "pencil") { edit(relay) }
                }
            }
            Button("添加中转", systemImage: "plus") {
                model.perform("relay.edit", params(["name": ""]))
                editing = RelayEditing(name: "", baseUrl: "", model: "")
            }
        } header: {
            Text("中转")
        } footer: {
            Text(isAgy
                ? "切到中转后，这台设备上的 Antigravity 使用这里填的 Gemini API 密钥（与可选地址）访问模型，没有订阅额度。"
                : "切到中转后，这台设备上的 Claude Code 经它的地址和密钥访问模型，没有订阅额度。")
        }
    }

    private func edit(_ relay: Relay) {
        model.perform("relay.edit", params(["name": relay.name]))
        editing = RelayEditing(name: relay.name, baseUrl: relay.baseUrl, model: relay.model ?? "")
    }

    private func reload() async {
        do {
            state = try await model.request("accounts", params(), as: AccountsState.self)
            problem = nil
        } catch {
            problem = error.localizedDescription
        }
    }
}

/// 有值时为真的绑定：置成假时清掉。
private func present<T>(_ value: Binding<T?>) -> Binding<Bool> {
    Binding { value.wrappedValue != nil } set: { if !$0 { value.wrappedValue = nil } }
}

/// 正在做的事、上一件事为什么没做成、谁挡着换号。
private struct StatusSection: View {
    @Environment(AppModel.self) private var model
    let id: String
    let harness: String
    let state: AccountsState

    var body: some View {
        if !state.working.isEmpty || !state.problem.isEmpty || !state.blocker.isEmpty {
            Section {
                if !state.working.isEmpty {
                    HStack {
                        ProgressView()
                        Text(state.working)
                    }
                }
                if !state.problem.isEmpty {
                    Label(state.problem, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button("知道了") { model.perform("accounts.dismiss", ["id": id, "harness": harness]) }
                }
                if !state.blocker.isEmpty {
                    Label(state.blocker, systemImage: "lock")
                }
            }
        }
    }
}

private struct AccountLabel: View {
    let account: AccountRow

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(account.title)
                if !account.email.isEmpty, account.email != account.title {
                    Text(account.email).font(.caption).foregroundStyle(.secondary)
                }
                if account.expired {
                    Text("登录过期了，要重新添加").font(.caption).foregroundStyle(.red)
                }
            }
            Spacer()
            if !account.plan.isEmpty {
                Text(account.plan).font(.caption).foregroundStyle(.secondary)
            }
            if account.current {
                Image(systemName: "checkmark").foregroundStyle(.tint)
            }
        }
    }
}

private struct RelayLabel: View {
    let relay: Relay

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(relay.name)
                Text(relay.baseUrl).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if relay.current {
                Image(systemName: "checkmark").foregroundStyle(.tint)
            }
        }
    }
}
