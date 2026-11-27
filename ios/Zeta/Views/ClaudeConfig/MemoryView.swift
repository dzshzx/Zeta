import SwiftUI

/// 记忆（同桌面版 memory.go）：~/.claude/CLAUDE.md 是用户写给 Claude 的长期指令；自动记忆是它自己记的，每个项目一个目录，
/// 每条一个文件，MEMORY.md 是索引。
struct MemoryView: View {
    let calls: ConfigCalls
    let cwd: String
    @State private var data: MemoryPage?
    @State private var project = ""
    @State private var working = false
    @State private var failure: ConfigFailure?

    var body: some View {
        Form {
            Section {
                if let data {
                    NavigationLink {
                        DocumentView(calls: calls, document: instructions(data), changed: reload)
                    } label: {
                        TitledRow(
                            title: "CLAUDE.md",
                            summary: data.instructions.exists ? ConfigLabels.size(data.instructions.size)
                                : String(localized: "还没有创建，打开就能写"))
                    }
                } else {
                    LoadingRow()
                }
            } header: {
                Text("用户指令")
            } footer: {
                Text("~/.claude/CLAUDE.md 里是你写给 Claude 的长期指令，所有项目通用。改动对之后新开始的对话生效。")
            }
            Section {
                if let data { autoMemoryRows(data) } else { LoadingRow() }
            } header: {
                Text("自动记忆")
            } footer: {
                Text("Claude 会在对话中自己记下你的偏好、纠正和项目背景。开关对所有项目生效；关掉后不再读写，记忆留着。")
            }
            if let data, data.index != nil {
                filesSection(data)
            }
        }
        .navigationTitle("记忆")
        .task { await load() }
        .refreshable { await load() }
        .configFailure($failure)
    }

    private func reload() { Task { await load() } }

    private func load() async {
        do {
            data = try await calls.get("config.memory.read", ["cwd": cwd, "project": project], as: MemoryPage.self)
        } catch {
            failure = ConfigFailure(String(localized: "读取记忆失败"), error)
        }
    }

    private func instructions(_ data: MemoryPage) -> ConfigDocumentRef {
        ConfigDocumentRef(
            path: data.instructions.path, title: String(localized: "用户指令"), file: "CLAUDE.md",
            placeholder: String(localized: "比如：回答用中文；我的主要技术栈是 Go；改代码前先说明打算怎么改"))
    }

    @ViewBuilder private func autoMemoryRows(_ data: MemoryPage) -> some View {
        Toggle(isOn: Binding { data.autoMemory && !data.forcedOff } set: { setAuto($0) }) {
            TitledRow(
                title: String(localized: "启用自动记忆"),
                summary: data.forcedOff ? String(localized: "被环境变量关掉了，这里改不了") : "")
        }
        .disabled(data.forcedOff || working)
        if !data.customDir.isEmpty {
            LabeledContent {
                Text("所有项目共用")
            } label: {
                TitledRow(title: String(localized: "记忆目录"), summary: data.customDir)
            }
        } else if !data.projects.isEmpty {
            Picker("项目", selection: Binding { data.selected } set: { key in
                project = key
                reload()
            }) {
                ForEach(data.projects, id: \.key) { item in
                    Text(item.default ? String(localized: "\(item.label)（默认工作目录）") : item.label).tag(item.key)
                }
            }
        }
        if let index = data.index {
            NavigationLink {
                DocumentView(
                    calls: calls,
                    document: ConfigDocumentRef(
                        path: index.path, title: "MEMORY.md", file: "MEMORY.md",
                        placeholder: String(localized: "一行一条记忆：- [标题](文件名.md) — 一句话说明")),
                    changed: reload)
            } label: {
                LabeledContent {
                    Text(index.exists ? ConfigLabels.size(index.size) : "")
                } label: {
                    TitledRow(
                        title: "MEMORY.md",
                        summary: index.exists ? String(localized: "记忆的索引") : String(localized: "记忆的索引，还没有"))
                }
            }
        }
    }

    private func filesSection(_ data: MemoryPage) -> some View {
        Section("记忆 · \(String(data.files.count))") {
            if data.files.isEmpty {
                TitledRow(
                    title: String(localized: "这个项目还没有记忆"),
                    summary: String(localized: "让 Claude 记住的东西会出现在这里"))
            }
            ForEach(data.files, id: \.path) { file in
                let name = file.name.isEmpty ? file.fileName : file.name
                NavigationLink {
                    DocumentView(
                        calls: calls,
                        document: ConfigDocumentRef(
                            path: file.path, title: name, file: file.fileName, placeholder: "", deletable: true),
                        changed: reload)
                } label: {
                    LabeledContent {
                        Text(Self.typeLabel(file.type))
                    } label: {
                        TitledRow(
                            title: name,
                            summary: ConfigLabels.clip(file.description.isEmpty ? file.fileName : file.description, 90))
                    }
                }
            }
        }
    }

    private func setAuto(_ enabled: Bool) {
        working = true
        Task {
            defer { working = false }
            do {
                try await calls.run("config.memory.setAuto", ["on": enabled])
            } catch {
                failure = ConfigFailure(String(localized: "没有改成"), error)
            }
            await load()
        }
    }

    static func typeLabel(_ type: String) -> String {
        switch type {
        case "user": String(localized: "用户")
        case "feedback": String(localized: "反馈")
        case "project": String(localized: "项目")
        case "reference": String(localized: "参考")
        default: type
        }
    }
}

/// 要编辑的一个文件。
struct ConfigDocumentRef {
    let path: String
    let title: String
    let file: String
    let placeholder: String
    var deletable = false
}

/// 整页的文件编辑（同桌面版 documentPage）：有内容的打开时先预览，能切到编辑；有改动时能保存、放弃。保存时文件在编辑期间
/// 被改过（多半是 Claude 改的）先问一声。预览暂用 AttributedString(markdown:)。
struct DocumentView: View {
    let calls: ConfigCalls
    let document: ConfigDocumentRef
    let changed: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var loading = true
    @State private var saved = ""
    @State private var sha = ""
    @State private var draft = ""
    @State private var saving = false
    @State private var preview = false
    @State private var conflict = false
    @State private var leaving = false
    @State private var deleting = false
    @State private var failure: ConfigFailure?

    private static let limit = 1 << 20

    private var dirty: Bool { !loading && draft != saved }
    private var over: Bool { draft.utf8.count > Self.limit }

    var body: some View {
        VStack(spacing: 8) {
            if loading {
                ProgressView().frame(maxHeight: .infinity)
            } else {
                Picker("预览或编辑", selection: $preview) {
                    Text("预览").tag(true)
                    Text("编辑").tag(false)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                content
                footer
            }
        }
        .navigationTitle(document.title)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(dirty)
        .toolbar { toolbar }
        .task { await load() }
        .confirmationDialog("文件已经变了", isPresented: $conflict, titleVisibility: .visible) {
            Button("仍然保存", role: .destructive) { save(force: true) }
        } message: {
            Text("编辑期间这个文件被改过了，多半是 Claude 在对话里更新了它。保存会用你的版本覆盖。")
        }
        .confirmationDialog("放弃未保存的更改？", isPresented: $leaving, titleVisibility: .visible) {
            Button("放弃", role: .destructive) { dismiss() }
        }
        .confirmationDialog("删除这条记忆", isPresented: $deleting, titleVisibility: .visible) {
            Button("删除", role: .destructive) { delete() }
        } message: {
            Text("删除 \(document.file)，并从 MEMORY.md 里去掉它那一行。删了就找不回来。")
        }
        .configFailure($failure)
    }

    @ViewBuilder private var content: some View {
        if preview {
            let text = Self.stripFrontmatter(draft)
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                ContentUnavailableView {
                    Label("还没有内容", systemImage: "doc.text")
                } description: {
                    Text(document.placeholder)
                } actions: {
                    Button("编辑") { preview = false }
                }
            } else {
                ScrollView {
                    Text(Self.markdown(text))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                }
            }
        } else {
            TextEditor(text: $draft)
                .font(.body.monospaced())
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .disabled(saving)
                .padding(.horizontal)
        }
    }

    private var footer: some View {
        HStack {
            Text(verbatim: "\(document.file) · \(ConfigLabels.size(Int64(draft.utf8.count))) / 1 MiB")
                .font(.caption)
                .foregroundStyle(over ? .red : .secondary)
            Spacer()
            if over {
                Text("已超过 1 MiB 上限，请删减").font(.caption).foregroundStyle(.red)
            } else if dirty {
                Text("未保存的更改").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal)
        .padding(.bottom, 8)
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        if dirty {
            ToolbarItem(placement: .cancellationAction) {
                Button("放弃") { leaving = true }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(saving ? String(localized: "保存中") : String(localized: "保存")) { save(force: false) }
                    .disabled(saving || over)
            }
        } else if document.deletable {
            ToolbarItem(placement: .destructiveAction) {
                Button("删除", systemImage: "trash", role: .destructive) { deleting = true }
                    .disabled(loading || saving)
            }
        }
    }

    private func load() async {
        guard loading else { return }
        do {
            let got = try await calls.get("config.memory.readFile", ["path": document.path], as: ConfigDocument.self)
            saved = got.content
            draft = got.content
            sha = got.exists ? got.sha : ""
            preview = !got.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            loading = false
        } catch {
            failure = ConfigFailure(String(localized: "读取失败"), error)
        }
    }

    private func save(force: Bool) {
        saving = true
        let content = draft
        Task {
            defer { saving = false }
            do {
                let reply = try await calls.get(
                    "config.memory.writeFile",
                    ["path": document.path, "content": content, "sha": sha, "force": force], as: WriteReply.self)
                if reply.conflict == true {
                    conflict = true
                    return
                }
                saved = content
                sha = reply.sha ?? ""
                changed()
            } catch {
                failure = ConfigFailure(String(localized: "保存失败"), error)
            }
        }
    }

    private func delete() {
        Task {
            do {
                try await calls.run("config.memory.delete", ["path": document.path])
                changed()
                dismiss()
            } catch {
                failure = ConfigFailure(String(localized: "删除失败"), error)
            }
        }
    }

    private static func markdown(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
    }

    /// 去掉开头的 --- 前言块（记忆文件的 name、description、type）；没有收尾的 --- 时原样。
    static func stripFrontmatter(_ text: String) -> String {
        let lines = text.components(separatedBy: "\n")
        guard let first = lines.first, first.trimmingCharacters(in: .whitespaces) == "---" else { return text }
        guard let end = lines.dropFirst().firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "---" }) else {
            return text
        }
        let rest = lines[(end + 1)...].joined(separator: "\n")
        return String(rest.drop { $0 == "\n" })
    }
}
