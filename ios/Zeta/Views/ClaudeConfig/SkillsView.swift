import SwiftUI
import UniformTypeIdentifiers

/// Skills（同桌面版 skills.go）：~/.claude/skills 下每个含 SKILL.md 的文件夹是一个。开关写 Claude Code 自己的 skillOverrides，
/// 文件不动；从 ZIP 导入，已经有同名的先问替换不替换。账号同步的只列出来。
struct SkillsView: View {
    let calls: ConfigCalls
    @State private var data: SkillsPage?
    @State private var picking = false
    @State private var importing = false
    @State private var pending: PendingZip?
    @State private var deleting: Skill?
    @State private var failure: ConfigFailure?

    private struct PendingZip {
        let name: String
        let file: String
        let zip: String
    }

    /// 能导入的 ZIP 的上限（helper/coreapi.MaxSkillZip）。
    private static let maxZip = 45 << 20

    var body: some View {
        Form {
            Section {
                Button {
                    picking = true
                } label: {
                    HStack {
                        TitledRow(
                            title: String(localized: "从 ZIP 导入"),
                            summary: importing
                                ? String(localized: "正在检查并安装…") : String(localized: "选一个含 SKILL.md 的 Skill 包"))
                        Spacer()
                        if importing { ProgressView() }
                    }
                }
                .disabled(importing)
            }
            userSection
            if let synced = data?.synced, !synced.isEmpty {
                Section {
                    ForEach(synced, id: \.name) { skill in
                        LabeledContent {
                            if !skill.enabled { Text("已停用") }
                        } label: {
                            TitledRow(title: skill.name, summary: ConfigLabels.clip(Self.orNone(skill.description), 90))
                        }
                    }
                } header: {
                    Text("账号同步 · \(String(synced.count))")
                } footer: {
                    Text("在 claude.ai 上管理，这里只能看。")
                }
            }
        }
        .navigationTitle("Skills")
        .task { await load() }
        .refreshable { await load() }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.zip]) { picked($0) }
        .confirmationDialog(
            "替换 Skill？", isPresented: Binding { pending != nil } set: { if !$0 { pending = nil } },
            titleVisibility: .visible, presenting: pending
        ) { zip in
            Button("替换", role: .destructive) { install(file: zip.file, zip: zip.zip, replace: true) }
        } message: { zip in
            Text("已经有一个叫“\(zip.name)”的 Skill。替换会覆盖它当前的全部文件。")
        }
        .confirmationDialog(
            "删除 Skill", isPresented: Binding { deleting != nil } set: { if !$0 { deleting = nil } },
            titleVisibility: .visible, presenting: deleting
        ) { skill in
            Button("删除", role: .destructive) { delete(skill) }
        } message: { skill in
            Text("删除“\(skill.name)”的整个文件夹，之后需要重新导入才能恢复。")
        }
        .configFailure($failure)
    }

    private var userSection: some View {
        Section {
            if let data {
                if data.user.isEmpty {
                    TitledRow(
                        title: String(localized: "还没有 Skill"),
                        summary: String(localized: "从 ZIP 导入，或让 Claude 写一个"))
                }
                ForEach(data.user, id: \.name) { skill in
                    row(skill)
                }
            } else {
                LoadingRow()
            }
        } header: {
            Text("用户安装 · \(String(data?.user.count ?? 0))")
        } footer: {
            Text("装在 ~/.claude/skills 里。关掉后文件留着，Claude 不再用它；改动对之后新开始的对话生效。")
        }
    }

    private func row(_ skill: Skill) -> some View {
        let title = !skill.title.isEmpty && skill.title != skill.name ? "\(skill.title)（\(skill.name)）" : skill.name
        let summary = skill.valid
            ? ConfigLabels.clip(Self.orNone(skill.description), 90) : String(localized: "缺少 SKILL.md")
        let detail = skill.valid ? Self.orNone(skill.description)
            : String(localized: "这个文件夹里没有 SKILL.md，Claude Code 不会把它当成 Skill。")
        return Toggle(isOn: Binding { skill.enabled } set: { setEnabled(skill, $0) }) {
            TitledRow(title: title, summary: summary)
        }
        .contextMenu {
            Button("说明", systemImage: "info.circle") { failure = ConfigFailure(title: skill.name, message: detail) }
            Button("删除", systemImage: "trash", role: .destructive) { deleting = skill }
        }
        .swipeActions {
            Button("删除", role: .destructive) { deleting = skill }
        }
    }

    static func orNone(_ text: String) -> String { text.isEmpty ? String(localized: "无描述") : text }

    private func load() async {
        do {
            data = try await calls.get("config.skills.list", as: SkillsPage.self)
        } catch {
            failure = ConfigFailure(String(localized: "读取 Skills 失败"), error)
        }
    }

    private func picked(_ result: Result<URL, any Error>) {
        do {
            let url = try result.get()
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let bytes = try Data(contentsOf: url)
            guard bytes.count <= Self.maxZip else {
                failure = ConfigFailure(
                    title: String(localized: "没有装上"), message: String(localized: "文件超过 45 MB，不像是一个 Skill 包"))
                return
            }
            install(file: url.lastPathComponent, zip: bytes.base64EncodedString(), replace: false)
        } catch {
            failure = ConfigFailure(String(localized: "没有装上"), error)
        }
    }

    private func install(file: String, zip: String, replace: Bool) {
        importing = true
        Task {
            defer { importing = false }
            do {
                let result = try await calls.get(
                    "config.skills.import", ["fileName": file, "zip": zip, "replace": replace], as: SkillImport.self)
                if result.exists {
                    pending = PendingZip(name: result.name, file: file, zip: zip)
                    return
                }
                let title = result.replaced ? String(localized: "已替换") : String(localized: "已安装")
                let message = result.enabled ? String(localized: "“\(result.name)”装好了，Claude 会按需调用它。")
                    : String(localized: "“\(result.name)”装好了，但它被设成停用，打开开关后才能用。")
                failure = ConfigFailure(title: title, message: message)
                await load()
            } catch {
                failure = ConfigFailure(String(localized: "没有装上"), error)
            }
        }
    }

    private func setEnabled(_ skill: Skill, _ enabled: Bool) {
        Task {
            do {
                try await calls.run(
                    "config.skills.setEnabled", ["name": skill.name, "title": skill.title, "enabled": enabled])
            } catch {
                failure = ConfigFailure(enabled ? String(localized: "没有启用") : String(localized: "没有停用"), error)
            }
            await load()
        }
    }

    private func delete(_ skill: Skill) {
        Task {
            do {
                try await calls.run("config.skills.delete", ["name": skill.name])
            } catch {
                failure = ConfigFailure(String(localized: "删除失败"), error)
            }
            await load()
        }
    }
}
