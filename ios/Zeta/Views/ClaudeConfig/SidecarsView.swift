import SwiftUI

struct SidecarsView: View {
    let calls: ConfigCalls
    @State private var sidecars: [AgySidecar]?
    @State private var working = false
    @State private var failure: ConfigFailure?

    var body: some View {
        List {
            Section {
                if let list = sidecars {
                    if list.isEmpty {
                        Text("未发现 Sidecar 服务").foregroundStyle(.secondary)
                    }
                    ForEach(list) { item in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.displayName)
                                Text(item.description?.isEmpty == false ? item.description! : item.id)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Toggle("", isOn: Binding(
                                get: { item.enabled },
                                set: { toggle(item.id, enabled: $0) }
                            ))
                            .labelsHidden()
                            .disabled(working)
                        }
                    }
                } else {
                    LoadingRow()
                }
            } footer: {
                Text("在 ~/.gemini/config/config.json 中启停已发现的用户与插件 Sidecar 服务。")
            }
        }
        .navigationTitle("Sidecars")
        .task { await load() }
        .refreshable { await load() }
        .configFailure($failure)
    }

    private func load() async {
        do {
            sidecars = try await calls.get("config.sidecars.list", as: [AgySidecar].self)
        } catch {
            failure = ConfigFailure(String(localized: "读取 Sidecars 失败"), error)
        }
    }

    private func toggle(_ id: String, enabled: Bool) {
        guard !working else { return }
        working = true
        Task {
            defer { working = false }
            do {
                sidecars = try await calls.get(
                    "config.sidecars.setEnabled",
                    ["id": id, "enabled": enabled],
                    as: [AgySidecar].self
                )
            } catch {
                failure = ConfigFailure(String(localized: "切换 Sidecar 失败"), error)
            }
        }
    }
}
