import SwiftUI

/// 聊天页：条目从上往下，滚动停在最新的；底下是等你回应的确认（有时）和输入栏，都浮在内容上面。
struct ChatView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let chat = model.state.chat {
            content(chat)
                .navigationTitle(chat.title)
                .navigationSubtitle(subtitle(chat))
                .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func subtitle(_ chat: ChatState) -> String {
        [chat.device, chat.cwd].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    @ViewBuilder
    private func content(_ chat: ChatState) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                if chat.earlier > 0 {
                    Button("载入更早的消息") { model.perform("earlier") }
                        .buttonStyle(.bordered)
                        .frame(maxWidth: .infinity)
                }
                ForEach(model.items) { item in
                    ItemView(item: item)
                }
                if chat.busy {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(chat.status.isEmpty ? String(localized: "正在回答") : chat.status)
                            .foregroundStyle(.secondary)
                    }
                    .font(.footnote)
                }
            }
            .padding()
        }
        .defaultScrollAnchor(.bottom)
        .scrollDismissesKeyboard(.interactively)
        .overlay {
            if chat.opening {
                ProgressView("正在打开")
            } else if !chat.problem.isEmpty {
                ContentUnavailableView(
                    "打不开这个对话", systemImage: "exclamationmark.triangle", description: Text(chat.problem))
            }
        }
        .safeAreaInset(edge: .bottom) { bottomBar(chat) }
    }

    /// 浮在底部的：连接的问题、别处运行、等你回应的确认、输入栏。
    private func bottomBar(_ chat: ChatState) -> some View {
        VStack(spacing: 10) {
            if !chat.linkProblem.isEmpty {
                Label(chat.linkProblem, systemImage: "wifi.exclamationmark")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if chat.blocked {
                Label("会话正在别处运行，在那边退出后才能在这里接着", systemImage: "terminal")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if let prompt = chat.prompts.first {
                PromptCard(prompt: prompt)
                    .id(prompt.requestID)
            }
            Composer(busy: chat.busy, enabled: chat.connected && !chat.blocked)
        }
        .padding(.horizontal)
        .padding(.bottom, 8)
    }
}

/// 输入栏：多行输入框和一个圆钮（有字时发送；回答中、没字时停止）。
private struct Composer: View {
    @Environment(AppModel.self) private var model
    @State private var draft = ""
    @FocusState private var focused: Bool
    let busy: Bool
    let enabled: Bool

    private var empty: Bool { draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField("发消息", text: $draft, axis: .vertical)
                .lineLimit(1...8)
                .focused($focused)
                .padding(.vertical, 10)
                .padding(.leading, 16)
            if busy && empty {
                Button("停止", systemImage: "stop.fill") { model.perform("interrupt") }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.glassProminent)
                    .buttonBorderShape(.circle)
                    .controlSize(.large)
            } else {
                Button("发送", systemImage: "arrow.up") {
                    model.perform("send", ["text": draft])
                    draft = ""
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.glassProminent)
                .buttonBorderShape(.circle)
                .controlSize(.large)
                .disabled(empty || !enabled)
            }
        }
        .padding(4)
        .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 26))
    }
}
