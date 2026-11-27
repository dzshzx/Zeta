import Foundation
import Observation
import UIKit

/// 界面读的状态：核心说有变化时（在 Go 的线程上）排一次刷新，攒 50 毫秒取一次 state，把条目的变化套到 items 上。
/// 操作都转给核心；没成的原因放进 alert。
@MainActor @Observable
final class AppModel {
    static let shared = AppModel()

    private(set) var state = CoreState.empty
    private(set) var items: [Item] = []
    private(set) var publicKey = ""
    private(set) var version = ""
    /// 核心起不来的原因（界面整页显示它）。
    private(set) var startProblem: String?
    /// 最近一次操作没成的原因，界面弹出后清掉。
    var alert: String?

    private var core: Core?
    private var refreshing = false

    func start() {
        guard core == nil else { return }
        do {
            let support = try FileManager.default.url(
                for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            let dir = support.appending(path: "Zeta", directoryHint: .isDirectory)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let lang = Locale.preferredLanguages.first?.hasPrefix("zh") == false ? "en" : "zh-Hans"
            let core = try Core.start(dir: dir, seed: KeyStore.seed(), lang: lang, device: UIDevice.current.name) {
                Task { @MainActor in AppModel.shared.scheduleRefresh() }
            }
            self.core = core
            struct Key: Decodable { let `public`: String; let version: String }
            let key = try core.call("key", as: Key.self)
            publicKey = key.public
            version = key.version
            refresh()
        } catch {
            startProblem = error.localizedDescription
        }
    }

    func scheduleRefresh() {
        guard !refreshing else { return }
        refreshing = true
        Task {
            try? await Task.sleep(for: .milliseconds(50))
            refreshing = false
            refresh()
        }
    }

    private func refresh() {
        guard let core else { return }
        do {
            let next = try core.call("state", as: CoreState.self)
            apply(next.chat)
            state = next
            if let failed = next.chat?.failed, !failed.isEmpty {
                alert = failed
            }
        } catch {
            alert = error.localizedDescription
        }
    }

    /// 把条目的变化依次套上；对不上时（不该发生）整份重取。
    private func apply(_ chat: ChatState?) {
        guard let chat else {
            items = []
            return
        }
        for change in chat.items {
            let added = change.items ?? []
            if change.full {
                items = added
            } else if change.start >= 0, change.remove >= 0, change.start + change.remove <= items.count {
                items.replaceSubrange(change.start..<(change.start + change.remove), with: added)
            } else if let whole = try? core?.call("items", as: [Item].self) {
                items = whole
                return
            }
        }
    }

    /// 调核心的一个方法，没成时弹出原因。
    func perform(_ method: String, _ params: [String: any Sendable] = [:]) {
        guard let core else { return }
        do {
            _ = try core.call(method, params)
        } catch {
            alert = error.localizedDescription
        }
        scheduleRefresh()
    }

    /// 探测地址 target（会等到连上、查完，最多约 45 秒），不在主线程上等。
    func probe(_ target: String) async throws -> Probed {
        guard let core else { throw Core.Failure(message: "core not started") }
        return try await Task.detached { try core.call("probe", ["target": target], as: Probed.self) }.value
    }

    /// 添加探测过的设备，返回它的编号。
    func add(_ probed: Probed) throws -> String {
        guard let core else { throw Core.Failure(message: "core not started") }
        struct Added: Decodable { let id: String }
        let added = try core.call("add", ["token": probed.token], as: Added.self)
        scheduleRefresh()
        return added.id
    }

    func deviceName(_ id: String) -> String {
        state.devices.first { $0.id == id }?.name ?? id
    }
}
