import SwiftUI

struct AgyLaunchButton: View {
    @Environment(AppModel.self) private var model
    let composer: ComposerState
    @State private var open = false

    private var hasActiveOptions: Bool {
        composer.sandbox == true
            || !(composer.agent ?? "").isEmpty
            || !(composer.extraDirs ?? []).isEmpty
    }

    var body: some View {
        Button {
            open = true
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "slider.horizontal.3")
                Text(hasActiveOptions ? "启动项 ●" : "启动项")
            }
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .glassEffect(.regular.interactive(), in: .capsule)
        }
        .tint(.primary)
        .sheet(isPresented: $open) {
            AgyLaunchSheet(
                agent: composer.agent ?? "",
                dirsRaw: (composer.extraDirs ?? []).joined(separator: "\n"),
                sandbox: composer.sandbox ?? false
            ) { agent, dirs, sandbox in
                Task {
                    _ = try? await model.request("composer.set_agent", ["value": agent], as: Ignored.self)
                    _ = try? await model.request("composer.set_extra_dirs", ["dirs": dirs], as: Ignored.self)
                    _ = try? await model.request("composer.set_sandbox", ["sandbox": sandbox], as: Ignored.self)
                }
            }
        }
    }
}

private struct AgyLaunchSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var agent: String
    @State var dirsRaw: String
    @State var sandbox: Bool
    let onSave: (String, [String], Bool) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("终端沙箱 (--sandbox)", isOn: $sandbox)
                    TextField("子代理名称 (--agent，可选)", text: $agent)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section {
                    TextEditor(text: $dirsRaw)
                        .frame(minHeight: 80)
                        .font(.footnote.monospaced())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("附加目录 (--add-dir)")
                } footer: {
                    Text("每行填写一个绝对目录路径。")
                }
            }
            .navigationTitle("Antigravity 启动选项")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        let dirs = dirsRaw
                            .split(separator: "\n")
                            .map { $0.trimmingCharacters(in: .whitespaces) }
                            .filter { !$0.isEmpty }
                        onSave(agent.trimmingCharacters(in: .whitespaces), dirs, sandbox)
                        dismiss()
                    }
                }
            }
        }
    }
}
