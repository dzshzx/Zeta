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
                Text("""
                    请先将设置中 Zeta 的公钥添加到目标设备的 ~/.ssh/authorized_keys。\
                    设备须为 arm64 或 x86-64 架构的 Linux，并已安装 bash 及 Claude Code 或 Antigravity CLI。
                    """)
            }
            if let problem {
                Section {
                    Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                }
            }
            if let probed {
                Section("已找到设备") {
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
                Text("粘贴或选取 OpenSSH、PEM 格式的私钥文件（例如 ~/.ssh/id_ed25519），请勿选择 .pub 公钥。")
            }
            Section {
                SecureField("无密码请留空", text: $passphrase)
            } header: {
                Text("密码")
            } footer: {
                Text("导入后会移除密码并将私钥存入本机钥匙串，之后不再提示输入。")
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
                problem = String(localized: "该文件不是有效的私钥格式")
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
