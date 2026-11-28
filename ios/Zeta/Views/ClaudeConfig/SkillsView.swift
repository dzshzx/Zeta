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
                                ? String(localized: "正在检查并安装…") : String(localized: "选择包含 SKILL.md 的 Skill 压缩包"))
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
                    Text(calls.isAgy
                        ? "其他目录 · \(String(synced.count))"
                        : "账号同步 · \(String(synced.count))")
                } footer: {
                    Text(calls.isAgy
                        ? "来自 ~/.agents/skills 或 skills.json 声明的目录，此处仅支持查看。"
                        : "在 claude.ai 中管理，此处仅支持查看。")
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
            Text("已存在名为“\(zip.name)”的 Skill，替换将覆盖其全部现有文件。")
        }
        .confirmationDialog(
            "删除 Skill", isPresented: Binding { deleting != nil } set: { if !$0 { deleting = nil } },
            titleVisibility: .visible, presenting: deleting
        ) { skill in
            Button("删除", role: .destructive) { delete(skill) }
        } message: { skill in
            Text("将删除“\(skill.name)”的整个文件夹，后续需重新导入方可恢复。")
        }
        .configFailure($failure)
    }

    private var userSection: some View {
        Section {
            if let data {
                if data.user.isEmpty {
                    TitledRow(
                        title: String(localized: "暂无 Skill"),
                        summary: String(localized: "从 ZIP 导入，或让 Claude 生成"))
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
            Text(calls.isAgy
                ? "安装在 ~/.gemini/config/skills。关闭后文件仍会保留，但 Antigravity 不再调用；修改对之后新建的对话生效。"
                : "安装在 ~/.claude/skills。关闭后文件仍会保留，但 Claude 不再调用；修改对之后新建的对话生效。")
        }
    }

    private func row(_ skill: Skill) -> some View {
        let title = !skill.title.isEmpty && skill.title != skill.name ? "\(skill.title)（\(skill.name)）" : skill.name
        let summary = skill.valid
            ? ConfigLabels.clip(Self.orNone(skill.description), 90) : String(localized: "缺少 SKILL.md")
        let detail = skill.valid ? Self.orNone(skill.description)
            : String(localized: "此文件夹缺少 SKILL.md，\(Harness.name(calls.harness)) 不会将其识别为 Skill。")
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
                    title: String(localized: "安装失败"), message: String(localized: "文件超过 45 MB，不符合 Skill 压缩包要求"))
                return
            }
            install(file: url.lastPathComponent, zip: bytes.base64EncodedString(), replace: false)
        } catch {
            failure = ConfigFailure(String(localized: "安装失败"), error)
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
                let message = result.enabled ? String(localized: "“\(result.name)”已安装，Claude 将按需调用。")
                    : String(localized: "“\(result.name)”已安装，但当前处于停用状态，开启开关后方可使用。")
                failure = ConfigFailure(title: title, message: message)
                await load()
            } catch {
                failure = ConfigFailure(String(localized: "安装失败"), error)
            }
        }
    }

    private func setEnabled(_ skill: Skill, _ enabled: Bool) {
        Task {
            do {
                try await calls.run(
                    "config.skills.setEnabled", ["name": skill.name, "title": skill.title, "enabled": enabled])
            } catch {
                failure = ConfigFailure(enabled ? String(localized: "启用失败") : String(localized: "停用失败"), error)
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
