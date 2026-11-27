import CoreText
import SwiftUI
import UIKit

/// 公式：排版在核心（hub/mathtex，同桌面版、手机版的 RaTeX），这里照它交回的绘制清单用 KaTeX 字体画成图。
/// 哪些算公式也照核心（math.source：配上的单个 $ 换成 \( \)）；块公式是行首的 $$…$$、\[…\] 和 math 代码块（MarkdownBlock.math），
/// 行内的是 \( \)。排好之前、排不出时照原文显示。

/// 排好的一条公式（hub/mathtex 的 List）：坐标以 em 为单位，原点在左上角，基线在 height 处。
struct MathList: Decodable, Sendable {
    let items: [MathItem]?
    let width: Double
    let height: Double
    let depth: Double
}

extension MathList {
    /// 全没写 \color（都用正文的颜色）。
    var plain: Bool { (items ?? []).allSatisfy { $0.defaultInk == true } }
}

struct MathItem: Decodable, Sendable {
    /// 同 mathtex.Kind。
    enum Kind: Int, Decodable, Sendable {
        case glyph, line, rect, path
    }

    let kind: Kind
    let left: Double
    let top: Double
    let font: String?
    let char: UInt32?
    let scale: Double?
    let width: Double?
    let height: Double?
    let thickness: Double?
    let dashed: Bool?
    let fill: Bool?
    let commands: [MathCommand]?
    let color: [UInt8]
    let defaultInk: Bool?

    enum CodingKeys: String, CodingKey {
        case kind, font, char, scale, width, height, thickness, dashed, fill, commands, color, defaultInk
        case left = "x"
        case top = "y"
    }
}

struct MathCommand: Decodable, Sendable {
    let operation: String
    let control1X: Double?
    let control1Y: Double?
    let control2X: Double?
    let control2Y: Double?
    let endX: Double
    let endY: Double

    enum CodingKeys: String, CodingKey {
        case operation = "op"
        case control1X = "x1"
        case control1Y = "y1"
        case control2X = "x2"
        case control2Y = "y2"
        case endX = "x"
        case endY = "y"
    }
}

private struct MathFontData: Decodable, Sendable {
    let id: String
    let data: Data
}

/// 排好的公式和 KaTeX 字体记在这里，同一条公式只向核心要一次。
@MainActor @Observable
final class MathStore {
    static let shared = MathStore()

    /// 排好的一条：key 认它（Self.key），plain 是全没写 \color，画成模板图跟着前景色。
    struct Formula {
        let key: String
        let list: MathList
        let plain: Bool
    }

    enum Entry {
        case loaded(Formula)
        case failed
    }

    private(set) var entries: [String: Entry] = [:]
    /// KaTeX 字体：第一次要排公式时取一次（fontsTask），取到的放在 fontTable。
    @ObservationIgnored private var fontsTask: Task<[String: CGFont], Never>?
    @ObservationIgnored private var fontTable: [String: CGFont] = [:]
    @ObservationIgnored private var sources: [String: String] = [:]
    @ObservationIgnored private var images: [String: UIImage] = [:]
    @ObservationIgnored private var pending: Set<String> = []

    /// 公式写法换成界面认的（math.source）；没有 $ 和 \[ 的不用问核心。
    func source(_ text: String) -> String {
        guard text.contains("$") || text.contains("\\[") else { return text }
        if let done = sources[text] { return done }
        let out = (try? AppModel.shared.callCore("math.source", ["text": text], as: [String: String].self))?["text"]
        if sources.count > 256 { sources = [:] }
        sources[text] = out ?? text
        return out ?? text
    }

    func entry(_ tex: String, display: Bool) -> Entry? { entries[Self.key(tex, display)] }

    /// 向核心要排版；在画界面时调也行（改状态挪到下一轮）。
    func load(_ tex: String, display: Bool) {
        let key = Self.key(tex, display)
        guard entries[key] == nil, pending.insert(key).inserted else { return }
        Task {
            if fontsTask == nil { fontsTask = Task { await Self.fetchFonts() } }
            fontTable = await fontsTask?.value ?? [:]
            let list = try? await AppModel.shared.request("math", ["tex": tex, "display": display], as: MathList.self)
            if let list, list.width > 0 {
                entries[key] = .loaded(Formula(key: key, list: list, plain: list.plain))
            } else {
                entries[key] = .failed
            }
            pending.remove(key)
        }
    }

    /// 画好的图：size 是 1 em 的点数。plain 的画成模板图，跟着前景色；写了 \color 的照它，其余用这种深浅色下的正文色。
    func image(_ formula: Formula, size: CGFloat, scale: CGFloat, scheme: ColorScheme) -> UIImage {
        let plain = formula.plain
        let cacheKey = "\(formula.key)|\(size)|\(scale)|\(plain ? "template" : "\(scheme)")"
        if let done = images[cacheKey] { return done }
        let ink = plain
            ? UIColor.black
            : UIColor.label.resolvedColor(with: UITraitCollection(userInterfaceStyle: scheme == .dark ? .dark : .light))
        let drawn = MathRenderer(list: formula.list, fonts: fontTable, size: size).image(scale: scale, ink: ink)
        let out = plain ? drawn.withRenderingMode(.alwaysTemplate) : drawn
        if images.count > 512 { images = [:] }
        images[cacheKey] = out
        return out
    }

    static func key(_ tex: String, _ display: Bool) -> String { (display ? "D" : "T") + tex }

    private static func fetchFonts() async -> [String: CGFont] {
        let got = (try? await AppModel.shared.request("math.fonts", as: [MathFontData].self)) ?? []
        var out: [String: CGFont] = [:]
        for font in got {
            if let provider = CGDataProvider(data: font.data as CFData), let graphics = CGFont(provider) {
                out[font.id] = graphics
            }
        }
        return out
    }
}

/// 照绘制清单画一条公式（同桌面版 windows/internal/mathtex/draw.go）。
private struct MathRenderer {
    let list: MathList
    let fonts: [String: CGFont]
    let size: CGFloat

    func image(scale: CGFloat, ink: UIColor) -> UIImage {
        let bounds = CGSize(
            width: max(list.width * size, 1), height: max((list.height + list.depth) * size, 1))
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        return UIGraphicsImageRenderer(size: bounds, format: format).image { context in
            let pixel = 1 / max(scale, 1)
            for item in list.items ?? [] {
                draw(item, in: context.cgContext, pixel: pixel, ink: ink)
            }
        }
    }

    private func draw(_ item: MathItem, in ctx: CGContext, pixel: CGFloat, ink: UIColor) {
        let color = item.defaultInk == true || item.color.count < 4
            ? ink.cgColor
            : CGColor(
                srgbRed: CGFloat(item.color[0]) / 255, green: CGFloat(item.color[1]) / 255,
                blue: CGFloat(item.color[2]) / 255, alpha: CGFloat(item.color[3]) / 255)
        ctx.setFillColor(color)
        ctx.setStrokeColor(color)
        let left = item.left * size
        let top = item.top * size
        switch item.kind {
        case .glyph:
            glyph(item, at: CGPoint(x: left, y: top), in: ctx)
        case .line:
            let thick = max((item.thickness ?? 0) * size, pixel)
            let width = (item.width ?? 0) * size
            // 虚线同网页端：实、虚各 3 倍线宽；实线是一整段
            let (dashLength, step) = item.dashed == true ? (3 * thick, 6 * thick) : (width, width)
            var dash: CGFloat = 0
            while dash < width {
                let piece = min(dashLength, width - dash)
                ctx.fill(CGRect(x: left + dash, y: top - thick / 2, width: piece, height: thick))
                dash += step
            }
        case .rect:
            ctx.fill(CGRect(x: left, y: top, width: (item.width ?? 0) * size, height: (item.height ?? 0) * size))
        case .path:
            ctx.addPath(path(item.commands ?? [], left: left, top: top))
            if item.fill == true {
                ctx.fillPath()
            } else {
                ctx.setLineWidth(pixel)
                ctx.strokePath()
            }
        }
    }

    /// 一个字，基线左端在 origin。KaTeX 字体里有的照它画；没有的（中文、表情）用系统字体。
    private func glyph(_ item: MathItem, at origin: CGPoint, in ctx: CGContext) {
        guard let code = item.char, let scalar = Unicode.Scalar(code) else { return }
        let fontSize = (item.scale ?? 1) * size
        ctx.saveGState()
        defer { ctx.restoreGState() }
        ctx.translateBy(x: origin.x, y: origin.y)
        ctx.scaleBy(x: 1, y: -1)  // Core Text 的 y 朝上
        if let graphics = item.font.flatMap({ fonts[$0] }) {
            let font = CTFontCreateWithGraphicsFont(graphics, fontSize, nil, nil)
            var units = Array(String(Character(scalar)).utf16)
            var glyphs = [CGGlyph](repeating: 0, count: units.count)
            if CTFontGetGlyphsForCharacters(font, &units, &glyphs, units.count), glyphs[0] != 0 {
                var zero = CGPoint.zero
                CTFontDrawGlyphs(font, glyphs, &zero, 1, ctx)
                return
            }
        }
        let text = NSAttributedString(
            string: String(Character(scalar)),
            attributes: [
                .font: UIFont.systemFont(ofSize: fontSize),
                NSAttributedString.Key(kCTForegroundColorFromContextAttributeName as String): true
            ])
        ctx.textPosition = .zero
        CTLineDraw(CTLineCreateWithAttributedString(text), ctx)
    }

    private func path(_ commands: [MathCommand], left: CGFloat, top: CGFloat) -> CGPath {
        let path = CGMutablePath()
        func point(_ horizontal: Double?, _ vertical: Double?) -> CGPoint {
            CGPoint(x: left + (horizontal ?? 0) * size, y: top + (vertical ?? 0) * size)
        }
        for command in commands {
            switch command.operation {
            case "MoveTo": path.move(to: point(command.endX, command.endY))
            case "LineTo": path.addLine(to: point(command.endX, command.endY))
            case "QuadTo":
                path.addQuadCurve(
                    to: point(command.endX, command.endY), control: point(command.control1X, command.control1Y))
            case "CubicTo":
                path.addCurve(
                    to: point(command.endX, command.endY), control1: point(command.control1X, command.control1Y),
                    control2: point(command.control2X, command.control2Y))
            case "Close": path.closeSubpath()
            default: break
            }
        }
        return path
    }
}

/// 公式的字号（同桌面版、手机版）：块公式是正文的 17/16，行内的是四周文字的 1.06 倍。
enum MathSize {
    static var body: CGFloat { UIFont.preferredFont(forTextStyle: .body).pointSize }
    static var block: CGFloat { body * 17 / 16 }
    static var inline: CGFloat { body * 1.06 }
}

/// 块公式：居中，宽过一行时横着滚；排好之前、排不出时照原文（等宽）。
struct MathBlockView: View {
    let tex: String
    @Environment(\.displayScale) private var scale
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let store = MathStore.shared
        Group {
            if case .loaded(let formula) = store.entry(tex, display: true) {
                let image = store.image(formula, size: MathSize.block, scale: scale, scheme: scheme)
                ScrollView(.horizontal) {
                    Image(uiImage: image)
                        .accessibilityLabel(tex)
                        .padding(.vertical, 2)
                }
                .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
                .frame(maxWidth: .infinity)
            } else {
                CodeBlock(language: "", text: tex)
            }
        }
        .onAppear { store.load(tex, display: true) }
    }
}

/// 一段行内 Markdown，里面的 \( \) 换成排好的公式（跟着文字折行）；排好之前显示原文。
enum MathText {
    /// 拆成的段：Markdown 文字或公式。
    enum Segment: Equatable {
        case text(String)
        case math(String)
    }

    /// 按 \( \) 拆开；行内代码（反引号）里的、转义的 \\ 不算。
    static func segments(_ markdown: String) -> [Segment] {
        guard markdown.contains("\\(") else { return [.text(markdown)] }
        let chars = Array(markdown)
        var out: [Segment] = []
        var text = ""
        var pos = 0
        while pos < chars.count {
            let char = chars[pos]
            if char == "`" {
                var run = 0
                while pos + run < chars.count, chars[pos + run] == "`" { run += 1 }
                let end = Self.ticksEnd(chars, from: pos + run, run: run) ?? pos + run
                text += String(chars[pos..<end])
                pos = end
            } else if char == "\\", pos + 1 < chars.count, chars[pos + 1] == "(" {
                if let end = close(chars, from: pos + 2) {
                    if !text.isEmpty { out.append(.text(text)) }
                    text = ""
                    out.append(.math(String(chars[(pos + 2)..<end])))
                    pos = end + 2
                } else {
                    text += "\\("
                    pos += 2
                }
            } else if char == "\\", pos + 1 < chars.count {
                text.append(char)
                text.append(chars[pos + 1])
                pos += 2
            } else {
                text.append(char)
                pos += 1
            }
        }
        if !text.isEmpty { out.append(.text(text)) }
        return out
    }

    /// 从 from 起找一串正好 run 个的反引号（行内代码的结尾），答它后面的位置；没有时 nil。
    private static func ticksEnd(_ chars: [Character], from: Int, run: Int) -> Int? {
        var pos = from
        while pos < chars.count {
            guard chars[pos] == "`" else {
                pos += 1
                continue
            }
            var count = 0
            while pos + count < chars.count, chars[pos + count] == "`" { count += 1 }
            if count == run { return pos + count }
            pos += count
        }
        return nil
    }

    private static func close(_ chars: [Character], from: Int) -> Int? {
        var pos = from
        while pos + 1 < chars.count {
            if chars[pos] == "\\" && chars[pos + 1] == ")" { return pos }
            pos += chars[pos] == "\\" ? 2 : 1
        }
        return nil
    }

    /// 拼成一个 Text：文字照 MarkdownBlock.inline，公式是图，基线对上四周的字。
    @MainActor
    static func text(_ markdown: String, scale: CGFloat, scheme: ColorScheme) -> Text {
        let parts = segments(markdown)
        if parts == [.text(markdown)] { return Text(MarkdownBlock.inline(markdown)) }
        let store = MathStore.shared
        let size = MathSize.inline
        var out = Text(verbatim: "")
        for part in parts {
            let next: Text
            switch part {
            case .text(let text):
                next = Text(MarkdownBlock.inline(text))
            case .math(let tex):
                if case .loaded(let formula) = store.entry(tex, display: false) {
                    let image = store.image(formula, size: size, scale: scale, scheme: scheme)
                    next = Text(Image(uiImage: image)).baselineOffset(-formula.list.depth * size)
                } else {
                    store.load(tex, display: false)
                    next = Text(verbatim: tex).font(.body.monospaced())
                }
            }
            out = Text("\(out)\(next)")
        }
        return out
    }
}
