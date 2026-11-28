import SwiftUI

struct PluginsView: View {
    let calls: ConfigCalls
    @State private var plugins: [AgyPlugin]?
    @State private var working = false
    @State private var failure: ConfigFailure?
    @State private var showingInstall = false
    @State private var target = ""
    @State private var removing: AgyPlugin?

    var body: some View {
        List {
            Section {
                if let list = plugins {
                    if list.isEmpty {
                        Text("暂未安装插件").foregroundStyle(.secondary)
                    }
                    ForEach(list) { plugin in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(plugin.name)
                                if let desc = subtitle(plugin) {
                                    Text(desc).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Toggle("", isOn: Binding(
                                get: { plugin.enabled },
                                set: { toggle(plugin.name, enabled: $0) }
                            ))
                            .labelsHidden()
                            .disabled(working)
                        }
                        .swipeActions {
                            Button("卸载", role: .destructive) { removing = plugin }
                                .disabled(working)
                        }
                    }
                } else {
                    LoadingRow()
                }
            } footer: {
                Text("通过本机 agy plugin 命令管理已安装的插件。")
            }
        }
        .navigationTitle("插件")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("安装", systemImage: "plus") {
                    target = ""
                    showingInstall = true
                }
                .disabled(working)
            }
        }
        .alert("安装插件", isPresented: $showingInstall) {
            TextField("插件名、路径或地址", text: $target)
            Button("取消", role: .cancel) {}
            Button("安装") { install() }
        }
        .alert("卸载该插件？", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("取消", role: .cancel) { removing = nil }
            Button("卸载", role: .destructive) {
                if let name = removing?.name { uninstall(name) }
                removing = nil
            }
        } message: {
            Text(removing?.name ?? "")
        }
        .task { await load() }
        .refreshable { await load() }
        .configFailure($failure)
    }

    private func subtitle(_ plugin: AgyPlugin) -> String? {
        let parts = [plugin.version, plugin.description].compactMap { $0 }.filter { !$0.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func load() async {
        do {
            plugins = try await calls.get("config.plugins.list", as: [AgyPlugin].self)
        } catch {
            failure = ConfigFailure(String(localized: "读取插件列表失败"), error)
        }
    }

    private func toggle(_ name: String, enabled: Bool) {
        runAction("config.plugins.setEnabled", ["name": name, "enabled": enabled])
    }

    private func install() {
        let trimmed = target.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        runAction("config.plugins.install", ["target": trimmed], slow: true)
    }

    private func uninstall(_ name: String) {
        runAction("config.plugins.uninstall", ["name": name], slow: true)
    }

    private func runAction(_ method: String, _ params: [String: any Sendable], slow: Bool = false) {
        guard !working else { return }
        working = true
        Task {
            defer { working = false }
            do {
                if slow {
                    plugins = try await calls.slow(method, params, as: [AgyPlugin].self)
                } else {
                    plugins = try await calls.get(method, params, as: [AgyPlugin].self)
                }
            } catch {
                failure = ConfigFailure(String(localized: "操作失败"), error)
            }
        }
    }
}
