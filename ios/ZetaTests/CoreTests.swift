import Foundation
import Testing
@testable import Zeta

/// 核心桥的冒烟测试：在模拟器上真起 Go 核心（测试跑在 App 进程里，App 已经起过，再起一次直接可用），
/// 核对静态库链进来了、C 接口和 JSON 的两头对得上。核心的行为由 Go 的测试管（hub/mobile）。
@MainActor
struct CoreTests {
    private func core() throws -> Core {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return try Core.start(dir: dir, seed: Data(repeating: 7, count: 32), lang: "zh-Hans", device: "test") {}
    }

    @Test func publicKeyAndState() throws {
        let core = try core()
        struct Key: Decodable { let `public`: String }
        let key = try core.call("key", as: Key.self)
        #expect(key.public.hasPrefix("ssh-ed25519 "))
        let state = try core.call("state", as: CoreState.self)
        #expect(state.chat == nil)
    }

    @Test func refusalsThrow() throws {
        let core = try core()
        #expect(throws: Core.Failure.self) { try core.call("no-such-method") }
        #expect(throws: Core.Failure.self) { try core.call("interrupt") }
    }

    @Test func markdownBlocks() {
        let text = "# 标题\n看这里：\n```go\nfmt.Println(1)\n```\n- [x] 做完\n\n"
            + "| a | b |\n|---|---|\n| 1 | 2 |\n\n---\n$$x^2$$"
        let blocks = MarkdownBlock.parse(text)
        #expect(blocks == [
            .heading(1, "标题"), .paragraph("看这里："), .code(language: "go", text: "fmt.Println(1)"),
            .list([.init(depth: 0, number: nil, checked: true, text: "做完")]),
            .table(header: ["a", "b"], rows: [["1", "2"]]), .rule, .math("x^2")
        ])
    }

    /// 公式的绘制清单从核心解得出来（JSON 两头对得上），字、线都在。
    @Test func mathLayout() throws {
        let core = try core()
        let list = try core.call("math", ["tex": "\\frac{1}{2}", "display": true], as: MathList.self)
        #expect(list.width > 0 && list.height > 0)
        #expect(list.items?.contains { $0.kind == 0 && $0.char != nil && $0.font != nil } == true)
        #expect(list.items?.contains { $0.kind == 1 && ($0.thickness ?? 0) > 0 } == true)
        struct Font: Decodable { let id: String }
        #expect(try core.call("math.fonts", as: [Font].self).count == 20)
    }

    /// 行内的 \( \) 拆成公式，行内代码里的、没配上的不算。
    @Test func inlineMath() {
        #expect(MathText.segments(#"面积 \(\pi r^2\) 和 `\(x\)`"#) == [
            .text("面积 "), .math(#"\pi r^2"#), .text(#" 和 `\(x\)`"#)
        ])
        #expect(MathText.segments(#"没配上 \(x"#) == [.text(#"没配上 \(x"#)])
    }
}
