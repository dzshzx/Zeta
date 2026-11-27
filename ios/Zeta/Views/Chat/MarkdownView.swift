import SwiftUI
import UIKit

/// 块级 Markdown：自己把块拆出来（标题、列表与任务列表、引用、表格、代码块、公式、分隔线、段落、图片），
/// 行内的样式交给 AttributedString(markdown:)。每块是一个 Equatable 的视图，流式输出时只有内容变了的那块（一般是最后一块）重排。
/// 公式（$$…$$、\[…\]）先按原文等宽显示；桌面版和手机版用 RaTeX 排，iOS 上还没有。
struct MarkdownView: View {
    let text: String
    /// 回答里用 Markdown 引用的图怎么画（聊天页给，别处不给时只显示替代文字）。
    var image: ((String, String) -> AnyView)?

    var body: some View {
        let blocks = MarkdownBlock.parse(text)
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                if case .image(let alt, let url) = block {
                    if let image { image(alt, url) } else { Text(alt).foregroundStyle(.secondary) }
                } else {
                    BlockView(block: block).equatable()
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 拆出来的一块。
enum MarkdownBlock: Equatable {
    case paragraph(String)
    case heading(Int, String)
    /// 列表的各项：缩进层级、序号（无序为 nil）、任务列表的勾（不是任务时为 nil）、正文。
    case list([ListItem])
    case quote(String)
    case code(language: String, text: String)
    case math(String)
    case table(header: [String], rows: [[String]])
    case rule
    case image(alt: String, url: String)

    struct ListItem: Equatable {
        let depth: Int
        let number: String?
        let checked: Bool?
        let text: String
    }

    static func parse(_ text: String) -> [MarkdownBlock] {
        var parser = Parser(lines: text.components(separatedBy: "\n"))
        return parser.run()
    }

    /// 只看一行的写法，不管上下文。
    fileprivate struct Parser {
        let lines: [String]
        var pos = 0
        var out: [MarkdownBlock] = []
        var paragraph: [String] = []

        init(lines: [String]) { self.lines = lines }

        mutating func run() -> [MarkdownBlock] {
            while pos < lines.count {
                let line = lines[pos]
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                if trimmed.isEmpty {
                    flush()
                    pos += 1
                } else if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") {
                    fence(String(trimmed.prefix(3)), language: String(trimmed.dropFirst(3)))
                } else if trimmed.hasPrefix("$$") || trimmed == "\\[" {
                    math(trimmed)
                } else if let heading = Self.heading(trimmed) {
                    flush()
                    out.append(heading)
                    pos += 1
                } else if Self.isRule(trimmed) {
                    flush()
                    out.append(.rule)
                    pos += 1
                } else if trimmed.hasPrefix(">") {
                    quote()
                } else if Self.listItem(line) != nil {
                    list()
                } else if trimmed.hasPrefix("|"), pos + 1 < lines.count, Self.isDivider(lines[pos + 1]) {
                    table()
                } else if let image = Self.image(trimmed) {
                    flush()
                    out.append(image)
                    pos += 1
                } else {
                    paragraph.append(line)
                    pos += 1
                }
            }
            flush()
            return out
        }

        mutating func flush() {
            if !paragraph.isEmpty {
                out.append(.paragraph(paragraph.joined(separator: "\n")))
                paragraph = []
            }
        }

        mutating func fence(_ marker: String, language: String) {
            flush()
            pos += 1
            var body: [String] = []
            while pos < lines.count, !lines[pos].trimmingCharacters(in: .whitespaces).hasPrefix(marker) {
                body.append(lines[pos])
                pos += 1
            }
            pos += 1  // 收尾的那行（流式输出时可能还没来）
            let lang = language.trimmingCharacters(in: .whitespaces)
            if lang == "math" || lang == "latex" {
                out.append(.math(body.joined(separator: "\n")))
            } else {
                out.append(.code(language: lang, text: body.joined(separator: "\n")))
            }
        }

        mutating func math(_ first: String) {
            flush()
            let close = first == "\\[" ? "\\]" : "$$"
            var rest = String(first.dropFirst(first == "\\[" ? 2 : 2))
            if rest.hasSuffix(close), rest.count >= close.count, !rest.isEmpty {
                out.append(.math(String(rest.dropLast(close.count))))
                pos += 1
                return
            }
            var body = rest.isEmpty ? [String]() : [rest]
            pos += 1
            while pos < lines.count {
                let line = lines[pos].trimmingCharacters(in: .whitespaces)
                pos += 1
                if line.hasSuffix(close) {
                    rest = String(line.dropLast(close.count))
                    if !rest.isEmpty { body.append(rest) }
                    break
                }
                body.append(lines[pos - 1])
            }
            out.append(.math(body.joined(separator: "\n")))
        }

        mutating func quote() {
            flush()
            var body: [String] = []
            while pos < lines.count {
                let trimmed = lines[pos].trimmingCharacters(in: .whitespaces)
                guard trimmed.hasPrefix(">") else { break }
                var rest = trimmed.dropFirst()
                if rest.hasPrefix(" ") { rest = rest.dropFirst() }
                body.append(String(rest))
                pos += 1
            }
            out.append(.quote(body.joined(separator: "\n")))
        }

        mutating func list() {
            flush()
            var entries: [ListItem] = []
            while pos < lines.count {
                if let item = Self.listItem(lines[pos]) {
                    entries.append(item)
                    pos += 1
                } else if Self.continues(lines[pos]), let last = entries.popLast() {
                    // 接在上一项后面的续行
                    let more = lines[pos].trimmingCharacters(in: .whitespaces)
                    entries.append(ListItem(depth: last.depth, number: last.number, checked: last.checked,
                                            text: last.text + "\n" + more))
                    pos += 1
                } else {
                    break
                }
            }
            out.append(.list(entries))
        }

        mutating func table() {
            flush()
            let header = Self.cells(lines[pos])
            pos += 2
            var rows: [[String]] = []
            while pos < lines.count, lines[pos].trimmingCharacters(in: .whitespaces).hasPrefix("|") {
                rows.append(Self.cells(lines[pos]))
                pos += 1
            }
            out.append(.table(header: header, rows: rows))
        }

        static func heading(_ line: String) -> MarkdownBlock? {
            let hashes = line.prefix { $0 == "#" }.count
            guard (1...6).contains(hashes), line.dropFirst(hashes).first == " " else { return nil }
            return .heading(hashes, line.dropFirst(hashes + 1).trimmingCharacters(in: .whitespaces))
        }

        static func isRule(_ line: String) -> Bool {
            let chars = line.filter { $0 != " " }
            guard chars.count >= 3, let first = chars.first, "-*_".contains(first) else { return false }
            return chars.allSatisfy { $0 == first }
        }

        static func listItem(_ line: String) -> ListItem? {
            let indent = line.prefix { $0 == " " || $0 == "\t" }.count
            let rest = line.dropFirst(indent)
            var number: String?
            var body: Substring
            if let marker = rest.first, "-*+".contains(marker), rest.dropFirst().first == " " {
                body = rest.dropFirst(2)
            } else {
                let digits = rest.prefix { $0.isNumber }
                let after = rest.dropFirst(digits.count)
                guard !digits.isEmpty, digits.count < 10, let dot = after.first, dot == "." || dot == ")",
                    after.dropFirst().first == " "
                else { return nil }
                number = String(digits) + "."
                body = after.dropFirst(2)
            }
            var checked: Bool?
            if body.hasPrefix("[ ] ") {
                checked = false
                body = body.dropFirst(4)
            } else if body.hasPrefix("[x] ") || body.hasPrefix("[X] ") {
                checked = true
                body = body.dropFirst(4)
            }
            return ListItem(depth: indent / 2, number: number, checked: checked, text: String(body))
        }

        /// 缩进的非空行接在上一项后面。
        static func continues(_ line: String) -> Bool {
            line.hasPrefix("  ") && !line.trimmingCharacters(in: .whitespaces).isEmpty
        }

        static func isDivider(_ line: String) -> Bool {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            return trimmed.hasPrefix("|") && trimmed.contains("-")
                && trimmed.allSatisfy { "|-: ".contains($0) }
        }

        static func cells(_ line: String) -> [String] {
            var trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("|") { trimmed.removeFirst() }
            if trimmed.hasSuffix("|") { trimmed.removeLast() }
            return trimmed.components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespaces) }
        }

        /// 单独一行的图 ![替代文字](地址)。
        static func image(_ line: String) -> MarkdownBlock? {
            guard line.hasPrefix("!["), line.hasSuffix(")"), let mid = line.range(of: "](") else { return nil }
            let alt = String(line[line.index(line.startIndex, offsetBy: 2)..<mid.lowerBound])
            let url = String(line[mid.upperBound..<line.index(before: line.endIndex)])
                .components(separatedBy: " ").first ?? ""
            return url.isEmpty ? nil : .image(alt: alt, url: url)
        }
    }

    /// 行内的样式（粗体、斜体、行内代码、链接、删除线）。
    static func inline(_ markdown: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: markdown, options: options)) ?? AttributedString(markdown)
    }
}

/// 一块的样子。内容没变时不重画（Equatable）。
private struct BlockView: View, Equatable {
    let block: MarkdownBlock

    var body: some View {
        switch block {
        case .paragraph(let text):
            Text(MarkdownBlock.inline(text)).textSelection(.enabled)
        case .heading(let level, let text):
            Text(MarkdownBlock.inline(text))
                .font(level == 1 ? .title2.bold() : level == 2 ? .title3.bold() : .headline)
                .padding(.top, 4)
                .textSelection(.enabled)
        case .list(let entries):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        if let checked = entry.checked {
                            Image(systemName: checked ? "checkmark.square.fill" : "square")
                                .foregroundStyle(checked ? Color.accentColor : Color.secondary)
                        } else {
                            Text(entry.number ?? "•").monospacedDigit().foregroundStyle(.secondary)
                        }
                        Text(MarkdownBlock.inline(entry.text)).textSelection(.enabled)
                    }
                    .padding(.leading, CGFloat(entry.depth) * 16)
                }
            }
        case .quote(let text):
            HStack(spacing: 10) {
                Capsule().fill(.tertiary).frame(width: 3)
                MarkdownView(text: text).foregroundStyle(.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
        case .code(let language, let text):
            CodeBlock(language: language, text: text)
        case .math(let text):
            CodeBlock(language: "", text: text)
        case .table(let header, let rows):
            TableBlock(header: header, rows: rows)
        case .rule:
            Divider()
        case .image(let alt, _):
            Text(alt)
        }
    }
}

/// 代码块：等宽、能横向滚动、右上角复制。
struct CodeBlock: View {
    let language: String
    let text: String
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(language).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button(
                    copied ? String(localized: "已复制") : String(localized: "复制"),
                    systemImage: copied ? "checkmark" : "doc.on.doc"
                ) {
                    UIPasteboard.general.string = text
                    copied = true
                }
                .labelStyle(.iconOnly)
                .font(.caption)
            }
            .padding(.horizontal, 10)
            .padding(.top, 6)
            ScrollView(.horizontal) {
                Text(text)
                    .font(.callout.monospaced())
                    .textSelection(.enabled)
                    .padding([.horizontal, .bottom], 10)
                    .padding(.top, 4)
            }
        }
        .background(.fill.tertiary, in: .rect(cornerRadius: 12))
    }
}

/// 表格：横向能滚。
private struct TableBlock: View {
    let header: [String]
    let rows: [[String]]

    var body: some View {
        ScrollView(.horizontal) {
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                GridRow {
                    ForEach(Array(header.enumerated()), id: \.offset) { _, cell in
                        Text(MarkdownBlock.inline(cell)).bold()
                    }
                }
                Divider()
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    GridRow {
                        ForEach(0..<header.count, id: \.self) { column in
                            Text(MarkdownBlock.inline(column < row.count ? row[column] : ""))
                        }
                    }
                }
            }
            .font(.callout)
            .textSelection(.enabled)
            .padding(10)
        }
        .background(.fill.quaternary, in: .rect(cornerRadius: 12))
    }
}
