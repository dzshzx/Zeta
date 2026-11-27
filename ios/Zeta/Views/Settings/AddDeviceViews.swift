import SwiftUI
import UniformTypeIdentifiers

/// 添加设备：输入地址，检查连接（核对能登录、装着 Claude Code，记下主机指纹），确认后添加。
struct AddDeviceView: View {
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
struct ImportKeyView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var passphrase = ""
    @State private var picking = false
    @State private var problem: String?

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
                Button("导入") { save() }
                    .buttonStyle(.glassProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .navigationTitle("导入私钥")
        .fileImporter(isPresented: $picking, allowedContentTypes: [.item]) { load($0) }
    }

    private func load(_ result: Result<URL, any Error>) {
        do {
            let url = try result.get()
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let read = String(bytes: try Data(contentsOf: url), encoding: .utf8) else {
                problem = String(localized: "这个文件不是文本，不像私钥")
                return
            }
            text = read
            problem = nil
        } catch {
            problem = error.localizedDescription
        }
    }

    private func save() {
        do {
            try model.importKey(text, passphrase: passphrase)
            dismiss()
        } catch {
            problem = error.localizedDescription
        }
    }
}
