import SwiftUI

/// 聊天列表的一项（种类见 windows/internal/chat/items）。
struct ItemView: View {
    let item: Item

    var body: some View {
        switch item.kind {
        case .user:
            HStack {
                Spacer(minLength: 48)
                VStack(alignment: .trailing, spacing: 4) {
                    if let peer = item.peer, !peer.isEmpty {
                        Text("来自 \(peer)").font(.caption).foregroundStyle(.secondary)
                    }
                    Text(item.text)
                        .textSelection(.enabled)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(.tint.opacity(0.15), in: .rect(cornerRadius: 20))
                    ForEach(item.files ?? [], id: \.self) { file in
                        Label(file, systemImage: "doc").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        case .answer:
            MarkdownText(text: item.text)
        case .thought:
            MarkdownText(text: item.text)
                .foregroundStyle(.secondary)
        case .notice:
            Label(item.text, systemImage: item.error == true ? "exclamationmark.triangle" : "info.circle")
                .font(.footnote)
                .foregroundStyle(
                    item.error == true ? AnyShapeStyle(Color.red) : AnyShapeStyle(HierarchicalShapeStyle.secondary))
        case .work:
            WorkView(steps: item.steps ?? [])
        case .unknown:
            EmptyView()
        }
    }
}

/// 连续的思考与工具调用折成的一组：收起时一行（在跑时转圈），展开是各步。
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
                Text(title)
                    .lineLimit(1)
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
        }
    }

    private var title: String {
        if let running = steps.last(where: \.isRunning), !running.isThinking {
            return running.summary.isEmpty ? running.name : "\(running.name) · \(running.summary)"
        }
        return String(localized: "\(steps.count) 步")
    }
}

private struct StepView: View {
    let step: Step

    var body: some View {
        if step.isThinking {
            DisclosureGroup {
                Text(step.text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            } label: {
                Label(step.isRunning ? String(localized: "正在思考") : String(localized: "思考"), systemImage: "brain")
                    .font(.footnote)
            }
        } else {
            DisclosureGroup {
                ScrollView(.horizontal) {
                    Text(step.result.isEmpty ? String(localized: "（没有输出）") : String(step.result.prefix(4000)))
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                        .padding(8)
                }
                .background(.fill.tertiary, in: .rect(cornerRadius: 10))
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
        }
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

/// Markdown：行内的样式交给 AttributedString，``` 围起来的代码块单独画成等宽、能横向滚动的一块。
struct MarkdownText: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(Self.blocks(text).enumerated()), id: \.offset) { _, block in
                switch block {
                case .prose(let prose):
                    Text(Self.styled(prose))
                        .textSelection(.enabled)
                case .code(let code):
                    ScrollView(.horizontal) {
                        Text(code)
                            .font(.callout.monospaced())
                            .textSelection(.enabled)
                            .padding(10)
                    }
                    .background(.fill.tertiary, in: .rect(cornerRadius: 12))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    enum Block {
        case prose(String)
        case code(String)
    }

    static func blocks(_ text: String) -> [Block] {
        var out: [Block] = []
        var current: [Substring] = []
        var inCode = false
        func flush() {
            let joined = current.joined(separator: "\n")
            if inCode {
                out.append(.code(joined))
            } else if !joined.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                out.append(.prose(joined))
            }
            current = []
        }
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            if line.trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                flush()
                inCode.toggle()
            } else {
                current.append(line)
            }
        }
        flush()
        return out
    }

    static func styled(_ markdown: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: markdown, options: options)) ?? AttributedString(markdown)
    }
}
