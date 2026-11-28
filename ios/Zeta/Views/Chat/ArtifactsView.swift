import SwiftUI

struct ArtifactsButton: View {
    let artifacts: [ArtifactInfo]
    @State private var open = false

    var body: some View {
        Button {
            open = true
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "doc.text")
                Text("产物 (\(artifacts.count))")
            }
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .glassEffect(.regular.interactive(), in: .capsule)
        }
        .tint(.primary)
        .sheet(isPresented: $open) {
            ArtifactsSheet(artifacts: artifacts)
        }
    }
}

struct ArtifactsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let artifacts: [ArtifactInfo]
    @State private var selected: String?
    @State private var content: ArtifactContent?
    @State private var loading = false
    @State private var problem = ""

    var body: some View {
        NavigationStack {
            List {
                Section("会话产物") {
                    ForEach(artifacts) { item in
                        Button {
                            load(item.name)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name).fontWeight(selected == item.name ? .semibold : .regular)
                                if let summary = item.summary, !summary.isEmpty {
                                    Text(summary).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                }
                            }
                        }
                        .foregroundStyle(.primary)
                    }
                }
                if loading {
                    Section { LoadingRow() }
                } else if !problem.isEmpty {
                    Section { Text(problem).foregroundStyle(.red) }
                } else if let content {
                    Section(content.name) {
                        if content.truncated == true {
                            Text("已截断至 512 KiB（共 \(content.size) 字节）")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Text(content.content)
                            .font(.footnote.monospaced())
                            .textSelection(.enabled)
                    }
                }
            }
            .navigationTitle("会话产物")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
            .task {
                if selected == nil, let first = artifacts.first {
                    load(first.name)
                }
            }
        }
    }

    private func load(_ name: String) {
        selected = name
        loading = true
        problem = ""
        Task {
            defer { loading = false }
            do {
                content = try await model.request("chat.artifact_read", ["name": name], as: ArtifactContent.self)
            } catch {
                problem = error.localizedDescription
            }
        }
    }
}
