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
    /// 每取到一份新的 state 加一：看着后台任务输出、子代理的页据此重取（taskoutput、subagent），设置各页据此重读自己的那份（账号、设备清单）。
    private(set) var revision = 0
    private(set) var publicKey = ""
    /// 登录用的是导入的私钥（不是 Zeta 自己生成的那把）。
    private(set) var keyImported = false
    private(set) var version = ""
    /// 核心起不来的原因（界面整页显示它）。
    private(set) var startProblem: String?
    /// 最近一次操作没成的原因，界面弹出后清掉。
    var alert: String?

    private var core: Core?
    private var starting = false
    private var refreshing = false

    /// 起核心。读设置、钥匙串和建设备都在后台线程上做，不卡主线程；起好以后回到主线程取公钥和第一份状态。
    func start() {
        guard core == nil, !starting else { return }
        starting = true
        let lang = Locale.preferredLanguages.first?.hasPrefix("zh") == false ? "en" : "zh-Hans"
        let device = UIDevice.current.name
        Task {
            defer { starting = false }
            do {
                let core = try await Task.detached { try Self.launch(lang: lang, device: device) }.value
                self.core = core
                try useKey(core.call("key", as: KeyInfo.self))
                restoreImportedKey(core)
                _ = try? core.call("alerts", [:])
                refresh()
            } catch {
                startProblem = error.localizedDescription
            }
        }
    }

    private nonisolated static func launch(lang: String, device: String) throws -> Core {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let dir = support.appending(path: "Zeta", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return try Core.start(dir: dir, seed: KeyStore.seed(), lang: lang, device: device) {
            Task { @MainActor in AppModel.shared.scheduleRefresh() }
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
            revision &+= 1
            Notifier.shared.observe(next)
            LiveActivities.shared.observe(next) { self.deviceName($0) }
            if !next.side.failed.isEmpty {
                alert = next.side.failed
            }
            if let failed = next.chat?.failed, !failed.isEmpty {
                alert = failed
            }
        } catch {
            alert = error.localizedDescription
        }
    }

    /// 把条目的变化依次套上；对不上时让核心下一次整份交。
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
            } else {
                // 对不上（不该发生）：叫核心下一次整份交
                perform("resync")
                return
            }
        }
    }

    /// 调核心的一个方法，没成时弹出原因。状态变了由核心通知，不用另外刷新。
    func perform(_ method: String, _ params: [String: any Sendable] = [:]) {
        guard let core else { return }
        do {
            _ = try core.call(method, params)
        } catch {
            alert = error.localizedDescription
        }
    }

    /// 在主线程上调核心的方法 method（很快返回的）并解出结果；没成时抛出原因。
    func callCore<T: Decodable>(_ method: String, _ params: [String: any Sendable] = [:], as type: T.Type) throws -> T {
        guard let core else { throw Core.Failure(message: "core not started") }
        return try core.call(method, params, as: type)
    }

    /// 在后台线程上调核心的方法 method 并等它的结果（会等网络的方法，比如 probe、core，都这样调）；没成时抛出原因。
    func request<T: Decodable & Sendable>(
        _ method: String, _ params: [String: any Sendable] = [:], as type: T.Type
    ) async throws -> T {
        guard let core else { throw Core.Failure(message: "core not started") }
        return try await Task.detached { try core.call(method, params, as: type) }.value
    }

    /// 把设备 backend 的核心方法 method（只有 config.*、diag.*、system.*，见 docs/ios.md「核心桥」）转过去，结果解成 T。
    func device<T: Decodable & Sendable>(
        _ backend: String, _ method: String, _ params: [String: any Sendable] = [:], as type: T.Type
    ) async throws -> T {
        try await request("core", ["backend": backend, "method": method, "params": params], as: type)
    }

    /// 探测地址 target（会等到连上、查完，最多约 45 秒），不在主线程上等。
    func probe(_ target: String) async throws -> Probed {
        try await request("probe", ["target": target], as: Probed.self)
    }

    /// 添加探测过的设备。
    func add(_ probed: Probed) throws {
        guard let core else { throw Core.Failure(message: "core not started") }
        _ = try core.call("add", ["token": probed.token])
    }

    private struct KeyInfo: Decodable {
        let `public`: String
        let imported: Bool
        let version: String
        let pem: String?
    }

    private func useKey(_ key: KeyInfo) {
        publicKey = key.public
        keyImported = key.imported
        version = key.version
    }

    /// 把上次导入、存在钥匙串里的私钥交给核心；交不进去时（不该发生）照用 Zeta 自己的密钥，并说一声。
    private func restoreImportedKey(_ core: Core) {
        do {
            guard let pem = try KeyStore.imported() else { return }
            try useKey(core.call("importkey", ["key": pem], as: KeyInfo.self))
        } catch {
            alert = error.localizedDescription
        }
    }

    /// 导入私钥 text（OpenSSH 或 PEM，有密码时给 passphrase）：核心认得出才换上并存进钥匙串，认不出时抛出原因。
    func importKey(_ text: String, passphrase: String) throws {
        guard let core else { throw Core.Failure(message: "core not started") }
        let key = try core.call("importkey", ["key": text, "passphrase": passphrase], as: KeyInfo.self)
        try KeyStore.saveImported(key.pem ?? "")
        useKey(key)
    }

    /// 换回 Zeta 自己的密钥，删掉钥匙串里导入的私钥。
    func resetKey() {
        guard let core else { return }
        do {
            let key = try core.call("resetkey", as: KeyInfo.self)
            KeyStore.clearImported()
            useKey(key)
        } catch {
            alert = error.localizedDescription
        }
    }

    func deviceName(_ id: String) -> String {
        state.devices.first { $0.id == id }?.name ?? id
    }
}
