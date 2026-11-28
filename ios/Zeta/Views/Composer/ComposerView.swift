import PhotosUI
import SwiftUI

/// 输入栏（规矩见 docs/ios.md「输入栏」）：上面是排队的消息和斜杠命令的提示，再是待发的附件和多行输入框，
/// 下面一排：左边「+」（图片、文件、常用指令）和权限模式，右边模型、思考强度、上下文用量的圆环、停止和发送。
/// 输入框里的字归这里管，每改一次交给核心存草稿、算斜杠命令；核心改了字（撤回、没发出去、恢复草稿）时 rev 变，这里换上。
struct ComposerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    let chat: ChatState

    @State private var text = ""
    @State private var selection: TextSelection?
    @State private var shownRev = -1
    @State private var slash: [SlashCommand] = []
    @State private var pickingPhotos = false
    @State private var photos: [PhotosPickerItem] = []
    @State private var pickingFiles = false
    @State private var managingPrompts = false
    @State private var showingUsage = false
    @State private var takingPhoto = false
    /// 单独发了 /config 或 /permissions：要打开的那一页。
    @State private var settingsPage: SettingsPage?

    private var state: ComposerState? { chat.composer }
    private var enabled: Bool { chat.connected && !chat.blocked && state?.ready == true }
    private var blank: Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasAttachments: Bool {
        state?.attachments.contains { !$0.uploading && $0.failed.isEmpty } == true
    }
    private var sendable: Bool { enabled && state?.uploading == false && (!blank || hasAttachments) }
    /// 回答中、没东西可发时圆钮是停止。
    private var stopping: Bool { chat.busy && blank && !hasAttachments }

    var body: some View {
        VStack(spacing: 8) {
            if let state, !state.queued.isEmpty {
                QueueStrip(queued: state.queued, steering: state.steering)
            }
            if !slash.isEmpty {
                SlashList(commands: slash, pick: complete)
            }
            if chat.canSwitchHarness == true {
                HStack {
                    HarnessMenu(harness: chat.harness)
                    Spacer()
                }
            }
            panel
        }
        .onChange(of: state?.rev ?? -1, initial: true) { _, rev in adopt(rev) }
        .onChange(of: text) { typed() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.flushDrafts() }
        }
        .photosPicker(isPresented: $pickingPhotos, selection: $photos, maxSelectionCount: 20, matching: .images)
        .onChange(of: photos) { _, items in
            guard !items.isEmpty else { return }
            photos = []
            Task { await Picked.attach(photos: items, model: model) }
        }
        .fileImporter(
            isPresented: $pickingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true
        ) { result in
            guard case .success(let urls) = result else { return }
            Task { await Picked.attach(files: urls, model: model) }
        }
        .fullScreenCover(isPresented: $takingPhoto) {
            CameraPicker { photo in
                takingPhoto = false
                if let photo { Task { await Picked.attach(photo: photo, model: model) } }
            }
            .ignoresSafeArea()
        }
        .sheet(item: $settingsPage) { page in
            ChatSettingsSheet(page: page, backend: chat.backend, cwd: chat.cwd, harness: chat.harness)
        }
        .sheet(isPresented: $managingPrompts) {
            SavedPromptsView(draft: text, insert: insert)
        }
        .sheet(isPresented: $showingUsage) {
            if let state {
                UsageSheet(composer: state, busy: chat.busy, connected: chat.connected)
            }
        }
        .alert("有些没有加进来", isPresented: problemsShown) {
            Button("知道了") { model.perform("composer.problems") }
        } message: {
            Text(problemsText)
        }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let state, !state.attachments.isEmpty {
                AttachmentsRow(attachments: state.attachments)
            }
            TextField(placeholder, text: $text, selection: $selection, axis: .vertical)
                .lineLimit(1...8)
                .padding(.horizontal, 12)
                .padding(.top, 8)
                .disabled(!enabled)
            HStack(spacing: 6) {
                if let state {
                    AttachMenu(
                        enabled: enabled, pickPhotos: { pickingPhotos = true }, takePhoto: { takingPhoto = true },
                        pickFiles: { pickingFiles = true },
                        insert: insert, manage: { managingPrompts = true })
                    ModeMenu(composer: state)
                    Spacer(minLength: 0)
                    ModelMenuButton(menu: state.model)
                    EffortMenu(composer: state)
                    UsageRing(usage: state.usage) { showingUsage = true }
                } else {
                    Spacer(minLength: 0)
                }
                sendButton
            }
        }
        .padding(6)
        .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 26))
    }

    private var sendButton: some View {
        Button(stopping ? String(localized: "停止") : String(localized: "发送"), systemImage: sendIcon) {
            if stopping {
                model.perform("interrupt")
            } else {
                let sent = model.sendComposer(text)
                if sent.sent {
                    text = ""
                    selection = nil
                }
                settingsPage = sent.open.flatMap(SettingsPage.init(rawValue:))
            }
        }
        .labelStyle(.iconOnly)
        .buttonStyle(.glassProminent)
        .buttonBorderShape(.circle)
        .disabled(!stopping && !sendable)
    }

    private var placeholder: String {
        chat.busy ? String(localized: "回答中，发的消息会排队") : String(localized: "发消息")
    }

    private var sendIcon: String { stopping ? "stop.fill" : "arrow.up" }

    /// 光标（UTF-16），没有时在末尾。
    private var caret: Int {
        if case .selection(let range) = selection?.indices, range.upperBound <= text.endIndex {
            return text.utf16.distance(from: text.startIndex, to: range.upperBound)
        }
        return text.utf16.count
    }

    private func setCaret(_ offset: Int) {
        let utf16 = text.utf16
        let index = utf16.index(utf16.startIndex, offsetBy: min(max(offset, 0), utf16.count))
        selection = TextSelection(insertionPoint: index)
    }

    private func adopt(_ rev: Int) {
        guard let state, rev != shownRev else { return }
        shownRev = rev
        if text != state.text {
            text = state.text
            selection = nil
        }
    }

    private func typed() {
        slash = model.typed(text, caret: caret)
    }

    private func complete(_ command: SlashCommand) {
        guard let done = model.complete(text, caret: caret, name: command.name) else { return }
        text = done.text
        setCaret(done.caret)
        slash = []
    }

    /// 选的常用指令放进输入框：空着就整个换上，有字就插在光标处。
    private func insert(_ prompt: String) {
        if blank {
            text = prompt
            selection = nil
            return
        }
        let offset = caret
        let utf16 = text.utf16
        let index = utf16.index(utf16.startIndex, offsetBy: offset)
        text.insert(contentsOf: prompt, at: index)
        setCaret(offset + prompt.utf16.count)
    }

    private var problemsShown: Binding<Bool> {
        Binding(
            get: { !(state?.problems.isEmpty ?? true) },
            set: { shown in if !shown { model.perform("composer.problems") } })
    }

    private var problemsText: String {
        guard let state else { return "" }
        var message = state.problems.map { "· " + $0 }.joined(separator: "\n")
        if state.tooLarge {
            message += "\n\n" + String(localized: "太大的文件可以先放到设备上，再在消息里写它的路径。")
        }
        return message
    }
}

/// 斜杠命令打开的设置页（同桌面版 /config、/permissions）：这个对话所在设备的 Claude Code 配置，或按对话的工作目录看工具权限。
enum SettingsPage: String, Identifiable {
    case config, permissions

    var id: String { rawValue }
}

private struct ChatSettingsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let page: SettingsPage
    let backend: String
    let cwd: String
    let harness: String

    var body: some View {
        NavigationStack {
            Group {
                switch page {
                case .config:
                    ClaudeConfigView(backend: backend, harness: harness)
                case .permissions:
                    PermissionsView(calls: ConfigCalls(model: model, backend: backend, harness: harness), cwd: cwd)
                }
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成", systemImage: "checkmark") { dismiss() }
                }
            }
        }
    }
}
