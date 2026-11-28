import SwiftUI
import UIKit

/// 添加账号（同桌面版的登录）：核心在设备上起登录命令、给出授权网址；在 Safari 里打开它授权，把授权码或回调网址贴回来。
struct LoginSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    let id: String
    var harness = Harness.claude
    let login: LoginState?
    let working: String
    @State private var code = ""

    private var isAgy: Bool { harness == Harness.agy }

    var body: some View {
        NavigationStack {
            Form {
                if let login {
                    Section {
                        Text(login.url).font(.caption.monospaced()).textSelection(.enabled)
                        Button("在 Safari 中打开", systemImage: "safari") {
                            if let url = URL(string: login.url) { openURL(url) }
                        }
                        Button("复制网址", systemImage: "doc.on.doc") { UIPasteboard.general.string = login.url }
                    } header: {
                        Text("授权网址")
                    } footer: {
                        Text(isAgy
                            ? "打开后用 Google 账号登录并授权；完成后复制页面给出的授权码，或复制跳转后的整条回调网址。"
                            : "打开后用要添加的账号登录并授权，页面会给出一段授权码。")
                    }
                    Section {
                        TextField(isAgy ? String(localized: "授权码或回调网址") : String(localized: "授权码"),
                                  text: $code, axis: .vertical)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .font(.body.monospaced())
                        Button("提交") {
                            model.perform("accounts.code", ["id": id, "harness": harness, "code": code])
                        }
                        .disabled(!working.isEmpty || code.trimmingCharacters(in: .whitespaces).isEmpty)
                    } header: {
                        Text(isAgy ? "授权码或回调网址" : "授权码")
                    } footer: {
                        if !login.notice.isEmpty {
                            Text(login.notice).foregroundStyle(.red)
                        } else if !working.isEmpty {
                            Text(working)
                        } else {
                            Text(isAgy
                                ? "粘贴授权码，或粘贴包含 code= 的整条回调网址（http://localhost…）。"
                                : "把授权页给的整段码贴在这里，中间有一个 #。")
                        }
                    }
                }
            }
            .navigationTitle("添加账号")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", role: .cancel) {
                        model.perform("accounts.cancel", ["id": id, "harness": harness])
                    }
                }
            }
        }
    }
}

/// 打开中转编辑页时的样子：添加时都空着，编辑时照那个中转。
struct RelayEditing: Identifiable {
    let id = UUID()
    let name: String
    let baseUrl: String
    let model: String

    var create: Bool { name.isEmpty }
}

/// 添加或编辑中转：名称（加好后不能改）、地址、密钥（遮着，能显示；Zeta 改不了的灰着说明）、默认模型（能向中转要模型列表）。
/// 草稿在核心里（relay.draft），保存、拉模型列表都由核心做。
struct RelayEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let id: String
    var harness = Harness.claude
    let start: RelayEditing
    let draft: RelayDraft?
    let working: String
    @State private var name = ""
    @State private var baseUrl = ""
    @State private var key = ""
    @State private var defaultModel = ""
    @State private var showKey = false

    private var isAgy: Bool { harness == Harness.agy }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("名称", text: $name).disabled(!start.create)
                    TextField(
                        isAgy ? String(localized: "地址（可选，https://…）") : String(localized: "地址（https://…）"),
                        text: $baseUrl
                    )
                    .keyboardType(.URL)
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if !start.create { Text("名称加好以后不能改。") }
                        if isAgy { Text("不填地址时直接连 Google 官方 Gemini 接口；走自定义接入点时再填地址。") }
                    }
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                keySection
                modelSection
                if draft?.current == true {
                    Text("正在用这个中转：改了以后，新起的对话进程才用上。要删除先换到别的账号或中转。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(start.create ? "添加中转" : "编辑中转")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
        }
        .onAppear {
            name = start.name
            baseUrl = start.baseUrl
            defaultModel = start.model
        }
        .onChange(of: [name, baseUrl, key, defaultModel]) { push() }
        .onChange(of: draft?.saved == true) { _, saved in if saved { dismiss() } }
        .onDisappear { model.perform("relay.close") }
    }

    private var keySection: some View {
        Section {
            HStack {
                Group {
                    if showKey {
                        TextField(keyPlaceholder, text: $key)
                    } else {
                        SecureField(keyPlaceholder, text: $key)
                    }
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                let toggle = showKey ? String(localized: "隐藏") : String(localized: "显示")
                Button(toggle, systemImage: showKey ? "eye.slash" : "eye") { showKey.toggle() }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
            }
            .disabled(!keyEditable)
        } header: {
            Text("密钥")
        } footer: {
            if !keyEditable {
                Text(isAgy
                    ? "这个中转的密钥不是 Zeta 存的，要在设备上的配置里改。"
                    : "这个中转的密钥不是 Zeta 存的，要在设备上的 claude-switch 配置里改。")
            }
        }
    }

    private var modelSection: some View {
        Section {
            TextField("默认模型（可不填）", text: $defaultModel)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if let models = draft?.models, !models.isEmpty {
                Picker("从列表选", selection: $defaultModel) {
                    Text("不设").tag("")
                    ForEach(models) { item in
                        Text(item.displayName ?? item.id).tag(item.id)
                    }
                }
            }
            Button {
                model.perform("relay.fetch", ["id": id, "harness": harness])
            } label: {
                HStack {
                    Text("获取模型列表")
                    if draft?.loading == true {
                        Spacer()
                        ProgressView()
                    }
                }
            }
            .disabled(draft?.canFetch != true)
        } header: {
            Text("默认模型")
        } footer: {
            if let problem = draft?.modelsProblem, !problem.isEmpty {
                Text(problem).foregroundStyle(.red)
            } else if !working.isEmpty {
                Text(working)
            }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button("取消", role: .cancel) { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
            Button("保存") { model.perform("relay.save", ["id": id, "harness": harness]) }
                .disabled(draft?.ready != true || !working.isEmpty)
        }
    }

    /// 能不能填密钥：添加时都能，编辑时照核心说的（用户自己放的、密钥不是从文件读的中转不能）。
    private var keyEditable: Bool { start.create || draft?.keyEditable == true }

    private var keyPlaceholder: String {
        if !start.create, draft?.hasKey == true { return String(localized: "已存着，空着不改") }
        return isAgy ? String(localized: "Gemini API 密钥") : String(localized: "API 密钥")
    }

    private func push() {
        model.perform(
            "relay.draft",
            ["id": id, "harness": harness, "name": name, "base_url": baseUrl, "key": key, "model": defaultModel])
    }
}
