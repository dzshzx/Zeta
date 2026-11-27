import SwiftUI
import UIKit

/// 设置首页（同手机版的设置首页）：常规、设备（每台一行，右边写额度已用比例或在用的中转）、密钥、诊断、关于。
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var devices: [DeviceInfo] = []

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        GeneralSettingsView()
                    } label: {
                        Label("常规", systemImage: "gearshape")
                    }
                }
                devicesSection
                KeySection()
                Section {
                    NavigationLink {
                        DiagnosticsView()
                    } label: {
                        Label("诊断", systemImage: "stethoscope")
                    }
                    NavigationLink {
                        AboutView()
                    } label: {
                        Label("关于", systemImage: "info.circle")
                    }
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .task { model.perform("settings.open") }
            .task(id: model.revision) { await reload() }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .close) { dismiss() }
                }
            }
        }
    }

    private var devicesSection: some View {
        Section("设备") {
            ForEach(devices) { device in
                NavigationLink {
                    DeviceSettingsView(id: device.id)
                } label: {
                    LabeledContent {
                        Text(device.hint)
                    } label: {
                        Text(device.name)
                        Text(device.target)
                    }
                }
            }
            NavigationLink {
                AddDeviceView()
            } label: {
                Label("添加设备", systemImage: "plus")
            }
        }
    }

    private func reload() async {
        if let list = try? await model.request("devices", as: [DeviceInfo].self) {
            devices = list
        }
    }
}

/// 密钥一组：登录用的公钥（复制、分享）、导入私钥、换回 Zeta 自己的。
private struct KeySection: View {
    @Environment(AppModel.self) private var model
    @State private var resetting = false

    var body: some View {
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
        .confirmationDialog("换回 Zeta 的密钥？", isPresented: $resetting, titleVisibility: .visible) {
            Button("换回", role: .destructive) { model.resetKey() }
        } message: {
            Text("导入的私钥从这台设备上删掉，之后用 Zeta 自己的密钥登录。")
        }
    }
}

/// 常规：自动加载网络图片；界面语言跟随系统（跳到系统设置里本 App 的语言），外观跟随系统。
struct GeneralSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var autoLoad = false
    @State private var loaded = false

    var body: some View {
        Form {
            Section {
                Toggle("自动加载网络图片", isOn: $autoLoad)
                    .disabled(!loaded)
                    .onChange(of: autoLoad) { _, enabled in
                        guard loaded else { return }
                        model.perform("prefs.set", ["auto_load_remote_images": enabled])
                    }
            } footer: {
                Text("关着时，回答里的网络图片要点一下才加载。")
            }
            Section {
                Button("界面语言", systemImage: "globe") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
            } footer: {
                Text("界面语言跟随系统，可以在系统设置里给 Zeta 单独选；外观跟随系统的浅色、深色。")
            }
        }
        .navigationTitle("常规")
        .task {
            if let prefs = try? await model.request("prefs", as: Prefs.self) {
                autoLoad = prefs.autoLoadRemoteImages
            }
            loaded = true
        }
    }
}
