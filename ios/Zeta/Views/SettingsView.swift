import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 设置：登录用的公钥（导入私钥、换回 Zeta 自己的）、设备（添加、连接开关、删除）、版本。
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var resetting = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(model.publicKey)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                    Button("复制公钥", systemImage: "doc.on.doc") { UIPasteboard.general.string = model.publicKey }
                    ShareLink(item: model.publicKey) { Label("分享公钥", systemImage: "square.and.arrow.up") }
                    NavigationLink {
                        ImportKeyView()
                    } label: {
                        Label("导入私钥", systemImage: "key")
                    }
                    if model.keyImported {
                        Button("换回 Zeta 的密钥", systemImage: "arrow.uturn.backward", role: .destructive) {
                            resetting = true
                        }
                    }
                } header: {
                    Text(model.keyImported ? "导入的私钥" : "Zeta 的公钥")
                } footer: {
                    VStack(alignment: .leading) {
                        Text(model.keyImported
                            ? "Zeta 用导入的私钥登录，上面是它的公钥。"
                            : "把这一行加进设备上的 ~/.ssh/authorized_keys，Zeta 才能登录。")
                        Text("私钥只在这台设备的钥匙串里。")
                    }
                }
                Section("设备") {
                    ForEach(model.state.devices) { device in
                        NavigationLink(value: device) {
                            VStack(alignment: .leading) {
                                Text(device.name)
                                Text(device.target).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    NavigationLink {
                        AddDeviceView()
                    } label: {
                        Label("添加设备", systemImage: "plus")
                    }
                }
                Section {
                    LabeledContent("版本", value: model.version.isEmpty ? String(localized: "未知") : model.version)
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: Device.self) { DeviceView(id: $0.id) }
            .confirmationDialog("换回 Zeta 的密钥？", isPresented: $resetting, titleVisibility: .visible) {
                Button("换回", role: .destructive) { model.resetKey() }
            } message: {
                Text("导入的私钥从这台设备上删掉，之后用 Zeta 自己的密钥登录。")
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .close) { dismiss() }
                }
            }
        }
    }
}

/// 一台设备：地址、家目录、指纹，「连接」开关和删除。
private struct DeviceView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let id: String
    @State private var confirming = false

    var body: some View {
        if let device = model.state.devices.first(where: { $0.id == id }) {
            Form {
                Section {
                    Toggle("连接", isOn: Binding { !device.off } set: { model.perform("connect", ["id": id, "on": $0]) })
                } footer: {
                    Text("关掉后 Zeta 不连这台设备，它上面的对话在那边照样跑。")
                }
                Section {
                    LabeledContent("地址", value: device.target)
                    LabeledContent("家目录", value: device.home)
                    LabeledContent("主机指纹") {
                        Text(device.fingerprint).font(.caption.monospaced()).textSelection(.enabled)
                    }
                }
                Section {
                    Button("删除设备", role: .destructive) { confirming = true }
                }
            }
            .navigationTitle(device.name)
            .confirmationDialog("删除「\(device.name)」？", isPresented: $confirming, titleVisibility: .visible) {
                Button("删除", role: .destructive) {
                    model.perform("remove", ["id": id])
                    dismiss()
                }
            } message: {
                Text("只是 Zeta 不再连它，那边的会话记录不动。")
            }
        }
    }
}

/// 添加设备：输入地址，检查连接（核对能登录、装着 Claude Code，记下主机指纹），确认后添加。
private struct AddDeviceView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var target = ""
    @State private var probing = false
    @State private var probed: Probed?
    @State private var problem: String?

    var body: some View {
        Form {
            Section {
                TextField("用户@主机[:端口]", text: $target)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .onChange(of: target) { probed = nil }
                Button {
                    Task { await probe() }
                } label: {
                    HStack {
                        Text("检查连接")
                        if probing {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(probing || target.trimmingCharacters(in: .whitespaces).isEmpty)
            } footer: {
                Text("先把设置里 Zeta 的公钥加进那台设备的 ~/.ssh/authorized_keys。设备要是 arm64 或 x86-64 的 Linux，装着 bash 和 Claude Code。")
            }
            if let problem {
                Section {
                    Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                }
            }
            if let probed {
                Section("找到了") {
                    LabeledContent("主机名", value: probed.hostname)
                    LabeledContent("环境", value: probed.summary)
                    LabeledContent("主机指纹") {
                        Text(probed.fingerprint).font(.caption.monospaced())
                    }
                }
                Section {
                    Button("添加这台设备") { add(probed) }
                        .buttonStyle(.glassProminent)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .navigationTitle("添加设备")
    }

    private func probe() async {
        probing = true
        problem = nil
        probed = nil
        do {
            probed = try await model.probe(target)
        } catch {
            problem = error.localizedDescription
        }
        probing = false
    }

    private func add(_ found: Probed) {
        do {
            try model.add(found)
            dismiss()
        } catch {
            problem = error.localizedDescription
        }
    }
}

/// 导入私钥：从文件选取或粘贴，有密码时填上。核心认得出才换上，之后新的连接用它登录。
private struct ImportKeyView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var passphrase = ""
    @State private var picking = false
    @State private var problem: String?
    @State private var saving = false

    var body: some View {
        Form {
            Section {
                TextEditor(text: $text)
                    .font(.caption.monospaced())
                    .frame(minHeight: 160)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("从文件选取", systemImage: "folder") { picking = true }
            } header: {
                Text("私钥")
            } footer: {
                Text("粘贴或选取 OpenSSH、PEM 格式的私钥文件（如 ~/.ssh/id_ed25519），不是 .pub 公钥。")
            }
            Section {
                SecureField("没有密码就留空", text: $passphrase)
            } header: {
                Text("密码")
            } footer: {
                Text("导入后私钥去掉密码存进这台设备的钥匙串，以后不再问。")
            }
            if let problem {
                Section {
                    Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                }
            }
            Section {
                Button("导入") { Task { await save() } }
                    .buttonStyle(.glassProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(saving || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .navigationTitle("导入私钥")
        .fileImporter(isPresented: $picking, allowedContentTypes: [.item]) { result in Task { await load(result) } }
    }

    /// 私钥文件不过几 KB：大于 64 KB 的不读；读文件（可能要先从 iCloud 下载）放在后台线程上。
    private func load(_ result: Result<URL, any Error>) async {
        do {
            let url = try result.get()
            let read = try await Task.detached { () throws -> String? in
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                if let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 64 << 10 { return nil }
                return String(bytes: try Data(contentsOf: url), encoding: .utf8)
            }.value
            guard let read else {
                problem = String(localized: "这个文件不像私钥")
                return
            }
            text = read
            problem = nil
        } catch {
            problem = error.localizedDescription
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            try await model.importKey(text, passphrase: passphrase)
            dismiss()
        } catch {
            problem = error.localizedDescription
        }
    }
}
