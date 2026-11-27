import SwiftUI

/// 聊天列表的一项（种类见 hub/chat/items），样子照桌面版的 chatview：用户消息是靠右的气泡（另一个会话发来的注明来自谁），
/// 回答是 Markdown，带文字的思考是灰色的 Markdown，提示是一行小字，思考与工具折成一张卡。
struct ItemView: View {
    let item: Item
    /// 查找时命中的项铺一层底色。
    var highlighted = false

    var body: some View {
        content
            .padding(highlighted ? 6 : 0)
            .background {
                if highlighted {
                    RoundedRectangle(cornerRadius: 12).fill(.yellow.opacity(0.18))
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch item.kind {
        case .user:
            UserBubble(item: item)
        case .answer:
            AnswerText(text: item.text)
        case .thought:
            AnswerText(text: item.text)
                .font(.callout)
                .foregroundStyle(.secondary)
        case .notice:
            Label(item.text, systemImage: item.error == true ? "exclamationmark.triangle" : "info.circle")
                .font(.footnote)
                .foregroundStyle(
                    item.error == true ? AnyShapeStyle(Color.red) : AnyShapeStyle(HierarchicalShapeStyle.secondary))
                .textSelection(.enabled)
        case .work:
            WorkView(steps: item.steps ?? [])
        case .unknown:
            EmptyView()
        }
    }
}

/// 回答的 Markdown；里面引用的图（单独一行的 ![…](…)）照桌面版的 LinkedImageLoader 取。
struct AnswerText: View {
    let text: String

    var body: some View {
        MarkdownView(text: text) { alt, url in
            AnyView(
                VStack(alignment: .leading, spacing: 2) {
                    ChatImageView(image: ChatImage(linked: url))
                    if !alt.isEmpty { Text(alt).font(.caption).foregroundStyle(.secondary) }
                })
        }
    }
}

private struct UserBubble: View {
    let item: Item

    /// 用户消息里图片文件的缩小图和那个文件画成一张（Original），这些文件不再单列。
    private var files: [String] {
        let originals = Set((item.images ?? []).compactMap(\.original))
        return (item.files ?? []).filter { !originals.contains($0) }
    }

    var body: some View {
        HStack {
            Spacer(minLength: 48)
            VStack(alignment: .trailing, spacing: 4) {
                if let peer = item.peer, !peer.isEmpty {
                    Label("来自会话「\(peer)」", systemImage: "arrow.left.arrow.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let images = item.images, !images.isEmpty {
                    ScrollView(.horizontal) {
                        HStack {
                            ForEach(images, id: \.cacheKey) { ChatImageView(image: $0, maxHeight: 140) }
                        }
                    }
                    .scrollIndicators(.hidden)
                }
                if !item.text.isEmpty {
                    Text(item.text)
                        .textSelection(.enabled)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(bubble, in: .rect(cornerRadius: 20))
                }
                ForEach(files, id: \.self) { file in
                    Label(file, systemImage: "doc").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
    }

    private var bubble: AnyShapeStyle {
        item.peer?.isEmpty == false
            ? AnyShapeStyle(Color.secondary.opacity(0.2)) : AnyShapeStyle(Color.accentColor.opacity(0.15))
    }
}

/// 连续的思考与工具调用折成的一张卡：收起时一行（在跑时转圈、说在做什么），展开是各步。
private struct WorkView: View {
    let steps: [Step]
    @State private var expanded = false

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(steps) { StepView(step: $0) }
            }
            .padding(.top, 6)
        } label: {
            HStack(spacing: 6) {
                if steps.contains(where: \.isRunning) {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "checklist")
                }
                Text(title).lineLimit(1)
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
        }
        .padding(10)
        .background(.fill.quinary, in: .rect(cornerRadius: 14))
    }

    private var title: String {
        if let running = steps.last(where: \.isRunning) {
            if running.isThinking { return String(localized: "正在思考") }
            return running.summary.isEmpty ? running.name : "\(running.name) · \(running.summary)"
        }
        let tools = steps.filter { !$0.isThinking }.count
        return tools == 0 ? String(localized: "思考") : String(localized: "\(tools) 次工具调用")
    }
}

private struct StepView: View {
    let step: Step

    /// 发起子代理的那一步（Task 是 Agent 的旧名）：点了进子代理页，不在原地展开（同桌面版）。
    private var isSubagent: Bool { step.name == "Agent" || step.name == "Task" }

    var body: some View {
        if isSubagent {
            NavigationLink {
                SubagentView(toolUseID: step.id)
            } label: {
                HStack(spacing: 6) {
                    status
                    Text(step.name).font(.footnote.bold())
                    Text(step.summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .accessibilityHint(Text("查看子代理的步骤"))
        } else if step.isThinking {
            DisclosureGroup {
                MarkdownView(text: step.text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } label: {
                Label(thinkingTitle, systemImage: "brain").font(.footnote)
            }
        } else {
            DisclosureGroup {
                detail
            } label: {
                HStack(spacing: 6) {
                    status
                    Text(step.name).font(.footnote.bold())
                    Text(step.summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    if let patch = step.patch {
                        Text("+\(patch.added)").font(.caption.monospaced()).foregroundStyle(.green)
                        Text("-\(patch.removed)").font(.caption.monospaced()).foregroundStyle(.red)
                    }
                }
            }
        }
    }

    private var detail: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let patch = step.patch, !patch.hunks.isEmpty {
                FileChangeView(patch: patch, clipped: step.clipped == true)
            } else if step.clipped == true {
                Text("参数太长，只显示了一部分").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(step.images ?? [], id: \.cacheKey) { ChatImageView(image: $0) }
            if step.patch == nil || step.failed {
                ScrollView(.horizontal) {
                    Text(step.result.isEmpty ? String(localized: "（没有输出）") : String(step.result.prefix(4000)))
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                        .padding(8)
                }
                .background(.fill.tertiary, in: .rect(cornerRadius: 10))
            }
        }
    }

    private var thinkingTitle: String {
        if step.isRunning { return String(localized: "正在思考") }
        if let seconds = step.seconds { return String(localized: "思考了 \(seconds) 秒") }
        return String(localized: "思考")
    }

    @ViewBuilder
    private var status: some View {
        if step.isRunning {
            ProgressView().controlSize(.mini)
        } else if step.failed {
            Image(systemName: "xmark.circle.fill").foregroundStyle(.red).font(.caption)
        } else {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.caption)
        }
    }
}

/// 改文件的红绿对比（同桌面版 filechange.go）：各段之间隔一行，行号用新文件的（删掉的行用原文件的）；
/// 参数被核心截断时（clipped）说只画得出一部分。
struct FileChangeView: View {
    let patch: Patch
    let clipped: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if patch.created == true {
                Text("新文件").font(.caption).foregroundStyle(.secondary)
            }
            ScrollView(.horizontal) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(patch.hunks.enumerated()), id: \.offset) { index, hunk in
                        if index > 0 {
                            Text("⋯").font(.caption2).foregroundStyle(.secondary).padding(.vertical, 2)
                        }
                        ForEach(Array(hunk.lines.enumerated()), id: \.offset) { _, line in
                            row(line)
                        }
                    }
                }
                .font(.caption.monospaced())
                .textSelection(.enabled)
            }
            .background(.fill.quaternary, in: .rect(cornerRadius: 10))
            if clipped {
                Text("改动太长，这里只显示了一部分").font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func row(_ line: Patch.Line) -> some View {
        let number = line.kind == "removed" ? line.old : line.new
        let sign = line.kind == "added" ? "+" : line.kind == "removed" ? "-" : " "
        let tint: Color =
            line.kind == "added" ? .green.opacity(0.15) : line.kind == "removed" ? .red.opacity(0.15) : .clear
        return HStack(spacing: 6) {
            Text(number > 0 ? String(number) : "")
                .frame(minWidth: 32, alignment: .trailing)
                .foregroundStyle(.secondary)
            Text(sign + line.text)
                .fixedSize()
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 6)
        .background(tint)
    }
}
