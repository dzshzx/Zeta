import SwiftUI
import UIKit

/// 聊天页的条目列表：往上翻到顶自动载入更早的，离开底部时出「回到底部」，工具栏里的放大镜在对话内查找（同桌面版 find.go），
/// 长按消息是消息操作（同桌面版 msgactions.go、手机版 MessageActions.kt）。
struct ChatList: View {
    @Environment(AppModel.self) private var model
    let chat: ChatState

    @State private var atBottom = true
    @State private var finding = false
    @State private var query = ""
    @State private var hit = 0
    @State private var confirm: RoundConfirm?
    @State private var editing: Item?
    @State private var toast: String?

    private static let bottomID = "chat-bottom"

    var body: some View {
        let items = model.items
        let rounds = Rounds(items)
        let matches = finding ? Self.matches(items, query) : []
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    earlier
                    ForEach(items) { item in
                        ItemView(item: item, highlighted: matches.indices.contains(hit) && matches[hit] == item.id)
                            .id(item.id)
                            .contextMenu { menu(item, rounds) }
                    }
                    if chat.busy {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text(chat.status.isEmpty ? String(localized: "正在回答") : chat.status)
                                .foregroundStyle(.secondary)
                        }
                        .font(.footnote)
                    }
                    Color.clear.frame(height: 1).id(Self.bottomID)
                }
                .padding()
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onScrollGeometryChange(for: Bool.self) { geo in
                geo.contentOffset.y + geo.containerSize.height >= geo.contentSize.height - 80
            } action: { _, bottom in
                atBottom = bottom
            }
            .overlay(alignment: .bottomTrailing) {
                if !atBottom {
                    Button("回到底部", systemImage: "arrow.down") {
                        withAnimation { proxy.scrollTo(Self.bottomID, anchor: .bottom) }
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .padding()
                }
            }
            .safeAreaInset(edge: .top) {
                if finding {
                    FindBar(query: $query, hit: $hit, count: matches.count) {
                        finding = false
                        query = ""
                    }
                }
            }
            .onChange(of: hit) { _, index in scrollToHit(proxy, matches, index) }
            .onChange(of: query) {
                hit = max(0, matches.count - 1)  // 从最新的往前找
                scrollToHit(proxy, matches, hit)
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("在对话中查找", systemImage: "magnifyingglass") { finding.toggle() }
            }
        }
        .sheet(item: $confirm) { RoundConfirmSheet(confirm: $0, cwd: chat.cwd) }
        .sheet(item: $editing) { RewriteSheet(item: $0) }
        .alert(toast ?? "", isPresented: Binding(get: { toast != nil }, set: { if !$0 { toast = nil } })) {}
    }

    private func scrollToHit(_ proxy: ScrollViewProxy, _ matches: [String], _ index: Int) {
        guard matches.indices.contains(index) else { return }
        withAnimation { proxy.scrollTo(matches[index], anchor: .center) }
    }

    /// 顶上的「载入更早的」：翻到它时自动载入（同手机版），也能点。
    @ViewBuilder
    private var earlier: some View {
        if chat.earlier > 0 {
            Group {
                if chat.earlierLoading == true {
                    ProgressView()
                } else {
                    Button("载入更早的消息") { model.perform("earlier") }
                        .buttonStyle(.bordered)
                }
            }
            .frame(maxWidth: .infinity)
            .onAppear {
                if chat.earlierLoading != true { model.perform("earlier") }
            }
        }
    }

    /// 命中查找的条目（从旧到新）。
    static func matches(_ items: [Item], _ query: String) -> [String] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return [] }
        return items.filter { $0.searchable.localizedStandardContains(needle) }.map(\.id)
    }

    /// 现在能改写、删除、还原代码、分支（核心说能，连接在）。
    private var canRewind: Bool { chat.connected && chat.canRewind == true }

    @ViewBuilder
    private func menu(_ item: Item, _ rounds: Rounds) -> some View {
        if !item.text.isEmpty, item.kind != .work {
            Button("复制", systemImage: "doc.on.doc") { UIPasteboard.general.string = item.text }
        }
        if item.kind == .user {
            Button("收藏为常用指令", systemImage: "star") {
                Task { toast = await model.saveAsPrompt(item.text) }
            }
            if item.rewindable == true {
                Group {
                    Button("改写", systemImage: "pencil") { editing = item }
                    Button("还原代码", systemImage: "arrow.uturn.backward") {
                        confirm = RoundConfirm(kind: .restore, user: item, later: rounds.later(item.id))
                    }
                    Button("删除这一轮", systemImage: "trash", role: .destructive) {
                        confirm = RoundConfirm(kind: .drop, user: item, later: rounds.later(item.id))
                    }
                }
                .disabled(!canRewind)
            }
        }
        if let user = rounds.owner[item.id] {
            let later = rounds.later(user.id)
            if !(later == 0 && (chat.busy || chat.rewinding == true)) {
                Group {
                    if user.rewindable == true {
                        Button("重新生成", systemImage: "arrow.clockwise") {
                            confirm = RoundConfirm(kind: .regenerate, user: user, later: later)
                        }
                        if item.kind != .user {
                            Button("删除这一轮", systemImage: "trash", role: .destructive) {
                                confirm = RoundConfirm(kind: .drop, user: user, later: later)
                            }
                        }
                    }
                    if user.forkable == true {
                        Button("从这里分支", systemImage: "arrow.triangle.branch") {
                            model.perform("fork", ["last": user.roundLast ?? item.id])
                        }
                    }
                }
                .disabled(!canRewind)
            }
        }
    }
}

/// 各轮：一轮的操作挂在哪一项下面（用户条目的 round_last → 那条用户消息），和每条用户消息之后还有几轮。
private struct Rounds {
    var owner: [String: Item] = [:]
    var order: [String] = []

    init(_ items: [Item]) {
        for item in items where item.kind == .user {
            order.append(item.id)
            if let last = item.roundLast, !last.isEmpty { owner[last] = item }
        }
    }

    func later(_ user: String) -> Int {
        guard let index = order.firstIndex(of: user) else { return 0 }
        return order.count - index - 1
    }
}

/// 查找栏：输入框、第几个 / 共几个、上一个、下一个、关闭。
private struct FindBar: View {
    @Binding var query: String
    @Binding var hit: Int
    let count: Int
    let close: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("在对话中查找", text: $query)
                .focused($focused)
                .submitLabel(.search)
                .onSubmit { step(-1) }
            if !query.isEmpty {
                Text(count == 0 ? String(localized: "没有找到") : "\(hit + 1)/\(count)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Button("上一个", systemImage: "chevron.up") { step(-1) }.disabled(count == 0)
            Button("下一个", systemImage: "chevron.down") { step(1) }.disabled(count == 0)
            Button("关闭", systemImage: "xmark", action: close)
        }
        .labelStyle(.iconOnly)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .glassEffect(.regular, in: .capsule)
        .padding(.horizontal)
        .onAppear { focused = true }
    }

    private func step(_ delta: Int) {
        guard count > 0 else { return }
        hit = (hit + delta + count) % count
    }
}
