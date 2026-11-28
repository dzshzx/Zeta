import SwiftUI
import UIKit

/// 诊断（同桌面版的诊断页）：Zeta 自己的运行日志、环境、各台设备上 Claude Code 那边的日志（核心的 diag.*），导出诊断包。
struct DiagnosticsView: View {
    @Environment(AppModel.self) private var model
    @State private var devices: [DeviceInfo] = []
    @State private var exporting = false
    @State private var exported: URL?
    @State private var problem: String?

    var body: some View {
        Form {
            Section {
                NavigationLink("运行日志") { RunLogView() }
            } footer: {
                Text("Zeta 在本机记录的连接、对话与错误日志。")
            }
            Section("环境") {
                ForEach(envLines, id: \.self) { line in
                    LabeledContent(line.name, value: line.value)
                }
            }
            Section {
                ForEach(devices.filter { !$0.off }) { device in
                    NavigationLink(device.name) { DeviceLogsView(device: device) }
                }
            } header: {
                Text("设备日志")
            } footer: {
                Text("各设备上对话进程的错误输出与 Claude Code 调试日志。未开启「连接」的设备不予列出。")
            }
            exportSection
        }
        .navigationTitle("诊断")
        .task {
            if let list = try? await model.request("devices", as: [DeviceInfo].self) { devices = list }
        }
    }

    private var exportSection: some View {
        Section {
            Button {
                Task { await export() }
            } label: {
                HStack {
                    Text("导出诊断包")
                    if exporting {
                        Spacer()
                        ProgressView()
                    }
                }
            }
            .disabled(exporting)
            if let exported {
                ShareLink(item: exported) { Label(exported.lastPathComponent, systemImage: "square.and.arrow.up") }
            }
            if let problem {
                Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
            }
        } footer: {
            Text("将运行日志、环境信息及各设备日志打包为 ZIP，敏感信息（如 Token、密码）会自动脱敏。")
        }
    }

    /// 环境的各行：版本、系统、各台设备怎么连（不含密钥）。也写进诊断包。
    private var envLines: [EnvLine] {
        let version = model.version.isEmpty ? String(localized: "未知") : model.version
        let system = "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)"
        var lines = [
            EnvLine(name: String(localized: "Zeta 版本"), value: version),
            EnvLine(name: String(localized: "系统"), value: system),
            EnvLine(name: String(localized: "机型"), value: UIDevice.current.model)
        ]
        for device in devices {
            let state = device.off ? String(localized: "连接已关闭") : String(localized: "连接已开启")
            var parts = [device.target, device.via, state]
            if !device.hostname.isEmpty { parts.append(device.hostname) }
            parts.append(device.fingerprint)
            if !device.defaultCwd.isEmpty { parts.append(device.defaultCwd) }
            lines.append(EnvLine(name: String(localized: "设备「\(device.name)」"), value: parts.joined(separator: " · ")))
        }
        return lines
    }

    private func export() async {
        exporting = true
        problem = nil
        defer { exporting = false }
        let stamp = Date.now.formatted(.iso8601)
        let header = String(localized: "Zeta iOS 版诊断包\n打包时刻：\(stamp)")
        let env: [[String: String]] = envLines.map { ["Name": $0.name, "Value": $0.value] }
        do {
            let got = try await model.request("diag.export", ["header": header, "env": env], as: PathResult.self)
            exported = URL(fileURLWithPath: got.path)
        } catch {
            problem = error.localizedDescription
        }
    }
}

/// Zeta 自己的运行日志：按类别、轻重筛，能复制、清空。
private struct RunLogView: View {
    @Environment(AppModel.self) private var model
    @State private var view: LogView?
    @State private var topic = ""
    @State private var level = 0
    @State private var clearing = false

    var body: some View {
        List {
            if let view {
                Section {
                    Picker("类别", selection: $topic) {
                        Text("全部").tag("")
                        ForEach(view.topics, id: \.id) { Text($0.name).tag($0.id) }
                    }
                    Picker("至少", selection: $level) {
                        ForEach(Array(view.levels.enumerated()), id: \.offset) { Text($0.element.name).tag($0.offset) }
                    }
                }
                Section {
                    if view.entries.isEmpty {
                        Text("没有记录").foregroundStyle(.secondary)
                    }
                    ForEach(view.entries, id: \.self) { entry in
                        EntryRow(entry: entry)
                    }
                }
            }
        }
        .navigationTitle("运行日志")
        .task(id: "\(topic)/\(level)") { await reload() }
        .refreshable { await reload() }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("复制", systemImage: "doc.on.doc") { UIPasteboard.general.string = text }
                Button("清空", systemImage: "trash", role: .destructive) { clearing = true }
            }
        }
        .confirmationDialog("清空运行日志？", isPresented: $clearing, titleVisibility: .visible) {
            Button("清空", role: .destructive) {
                model.perform("diaglog.clear")
                Task { await reload() }
            }
        }
    }

    private var text: String {
        (view?.entries ?? []).map { entry in
            "\(entry.date.formatted(.iso8601)) \(entry.levelName) [\(entry.topic)] \(entry.message)"
        }.joined(separator: "\n")
    }

    private func reload() async {
        view = try? await model.request("diaglog", ["topic": topic, "level": level], as: LogView.self)
    }
}

private struct EntryRow: View {
    let entry: LogEntry

    private var levelColor: Color {
        switch entry.level {
        case 2...: .red
        case 1: .orange
        default: .secondary
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(entry.date, format: .dateTime.month().day().hour().minute().second())
                Text(entry.levelName).foregroundStyle(levelColor)
                Text(entry.topic)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            Text(entry.message).font(.caption.monospaced()).textSelection(.enabled)
        }
    }
}

/// 一台设备上能看的日志：在跑的对话进程、结束的对话进程、Claude Code 的调试日志。
private struct DeviceLogsView: View {
    @Environment(AppModel.self) private var model
    let device: DeviceInfo
    @State private var files: [DiagFile]?
    @State private var problem: String?

    private struct Kind: Hashable {
        let kind: String
        let title: String
    }

    private var kinds: [Kind] {
        [
            Kind(kind: "running", title: String(localized: "运行中的对话进程")),
            Kind(kind: "finished", title: String(localized: "已结束的对话进程")),
            Kind(kind: "debug", title: String(localized: "Claude Code 调试日志"))
        ]
    }

    var body: some View {
        List {
            if let problem {
                Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
            }
            ForEach(kinds, id: \.kind) { group in
                let list = (files ?? []).filter { $0.kind == group.kind }
                if !list.isEmpty {
                    Section(group.title) {
                        ForEach(list) { file in
                            NavigationLink {
                                LogTailView(backend: device.id, file: file)
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(file.title ?? file.name)
                                    Text(ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            if files?.isEmpty == true {
                Text("没有日志").foregroundStyle(.secondary)
            }
        }
        .navigationTitle(device.name)
        .task { await reload() }
        .refreshable { await reload() }
    }

    private func reload() async {
        do {
            files = try await model.device(device.id, "diag.files", as: DiagFiles.self).files
            problem = nil
        } catch {
            problem = error.localizedDescription
        }
    }
}

/// 一个日志文件的最后 64 KB：能复制、刷新。
private struct LogTailView: View {
    @Environment(AppModel.self) private var model
    let backend: String
    let file: DiagFile
    @State private var tail: DiagTail?
    @State private var problem: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading) {
                if let problem {
                    Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                }
                if let tail {
                    if tail.truncated {
                        Text("仅显示最后 64 KB").font(.caption).foregroundStyle(.secondary)
                    }
                    Text(tail.text).font(.caption.monospaced()).textSelection(.enabled)
                } else if problem == nil {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .navigationTitle(file.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await reload() }
        .refreshable { await reload() }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("复制", systemImage: "doc.on.doc") { UIPasteboard.general.string = tail?.text ?? "" }
                Button("刷新", systemImage: "arrow.clockwise") { Task { await reload() } }
            }
        }
    }

    private func reload() async {
        do {
            tail = try await model.device(backend, "diag.tail", ["path": file.path, "redact": false], as: DiagTail.self)
            problem = nil
        } catch {
            problem = error.localizedDescription
        }
    }
}

/// 环境的一行。
private struct EnvLine: Hashable {
    let name: String
    let value: String
}
