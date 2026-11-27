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
        let blocks = MarkdownText.blocks("看这里：\n```go\nfmt.Println(1)\n```\n完了")
        #expect(blocks.count == 3)
        if case .code(let code) = blocks[1] { #expect(code == "fmt.Println(1)") } else { Issue.record("第二块不是代码") }
    }
}
