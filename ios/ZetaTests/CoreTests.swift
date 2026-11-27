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
        #expect(throws: Core.Failure.self) { try core.call("send", ["text": "你好"]) }
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
}
