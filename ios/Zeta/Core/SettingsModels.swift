import Foundation

// 设置各页从核心读的 JSON（hub/mobile 的 devices.go、accounts.go、diag.go）。

/// 方法 devices 的一项：设置里的一台设备。
struct DeviceInfo: Decodable, Sendable, Identifiable, Hashable {
    let id: String
    let name: String
    let customName: String
    let original: String
    let target: String
    let home: String
    let defaultCwd: String
    let via: String
    let hostname: String
    let fingerprint: String
    let off: Bool
    /// 在用的中转名；used 是最要紧的额度窗口用了百分之几，没有时是 -1。
    let relay: String
    let used: Int

    enum CodingKeys: String, CodingKey {
        case id, name, original, target, home, via, hostname, fingerprint, off, relay, used
        case customName = "custom_name"
        case defaultCwd = "default_cwd"
    }

    /// 首页那一行右边写的：断开、中转名或额度已用比例。
    var hint: String {
        if off { return String(localized: "已断开") }
        if !relay.isEmpty { return String(localized: "中转 \(relay)") }
        if used >= 0 { return String(localized: "已用 \((Double(used) / 100).formatted(.percent))") }
        return ""
    }
}

/// 方法 device.checkcwd 的结果。
struct CwdCheck: Decodable, Sendable {
    let path: String
    let problem: String
    let unchecked: String
    let off: Bool
}

struct Prefs: Decodable, Sendable {
    let autoLoadRemoteImages: Bool

    enum CodingKeys: String, CodingKey {
        case autoLoadRemoteImages = "auto_load_remote_images"
    }
}

/// 方法 accounts 的结果：一台设备的账号页。
struct AccountsState: Decodable, Sendable {
    let loaded: Bool
    let accounts: [AccountRow]
    let relays: [Relay]
    let unsaved: Bool
    let blocker: String
    let working: String
    let problem: String
    let login: LoginState?
    let relay: String
    let quota: QuotaView?
    let draft: RelayDraft?
}

struct AccountRow: Decodable, Sendable, Identifiable {
    let name: String
    let title: String
    let email: String
    let plan: String
    let current: Bool
    let expired: Bool

    var id: String { name }
}

struct Relay: Decodable, Sendable, Identifiable {
    let name: String
    let baseUrl: String
    let model: String?
    let hasKey: Bool
    let keyEditable: Bool
    let current: Bool

    var id: String { name }
}

struct LoginState: Decodable, Sendable {
    let url: String
    let notice: String
}

struct QuotaView: Decodable, Sendable {
    let plan: String
    let unavailable: Bool
    let blocked: Bool
    let blockedUntil: String
    let windows: [QuotaWindow]
    let extra: QuotaExtra?
    let footnote: String
    let refreshing: Bool

    enum CodingKeys: String, CodingKey {
        case plan, unavailable, blocked, windows, extra, footnote, refreshing
        case blockedUntil = "blocked_until"
    }
}

struct QuotaWindow: Decodable, Sendable, Identifiable {
    let label: String
    let percent: Int
    let used: Double
    let severity: String
    let reset: String

    var id: String { label }
}

struct QuotaExtra: Decodable, Sendable {
    let amount: String
    let used: Double?
}

struct RelayModel: Decodable, Sendable, Identifiable, Hashable {
    let id: String
    let displayName: String?
}

struct RelayDraft: Decodable, Sendable {
    let create: Bool
    let name: String
    let baseUrl: String
    let model: String
    let hasKey: Bool
    let keyEditable: Bool
    let current: Bool
    let ready: Bool
    let canFetch: Bool
    let loading: Bool
    let fetched: Bool
    let models: [RelayModel]
    let modelsProblem: String
    let saved: Bool

    enum CodingKeys: String, CodingKey {
        case create, name, model, current, ready, loading, fetched, models, saved
        case baseUrl = "base_url"
        case hasKey = "has_key"
        case keyEditable = "key_editable"
        case canFetch = "can_fetch"
        case modelsProblem = "models_problem"
    }
}

/// 方法 diaglog 的结果。
struct LogView: Decodable, Sendable {
    let entries: [LogEntry]
    let topics: [LogChoice]
    let levels: [LogChoice]
    let available: Bool
}

struct LogEntry: Decodable, Sendable, Hashable {
    let time: Int64
    let level: Int
    let levelName: String
    let topic: String
    let message: String

    enum CodingKeys: String, CodingKey {
        case level, topic, message
        case time = "at"
        case levelName = "level_name"
    }

    var date: Date { Date(timeIntervalSince1970: TimeInterval(time) / 1000) }
}

struct LogChoice: Decodable, Sendable, Hashable {
    let id: String
    let name: String
}

/// 核心的 diag.files 一项（docs/core.md「诊断」）。
struct DiagFile: Decodable, Sendable, Hashable, Identifiable {
    let kind: String
    let path: String
    let size: Int64
    let modified: Int64
    let title: String?

    var id: String { path }
    var name: String { (path as NSString).lastPathComponent }
}

struct DiagFiles: Decodable, Sendable {
    let files: [DiagFile]
}

struct DiagTail: Decodable, Sendable {
    let text: String
    let size: Int64
    let truncated: Bool
}

/// 方法 about 的结果。
struct AboutInfo: Decodable, Sendable {
    let version: String
    let code: Int
    let ref: String
    let url: String
    let licenses: [LicenseItem]
    let problem: String
}

struct LicenseItem: Decodable, Sendable, Hashable, Identifiable {
    let `where`: String
    let module: String
    let version: String
    let file: String

    var id: String { `where` + "/" + file + "/" + module }
    /// 用在哪：ios 是 App 链接的，host 是带到设备上的程序链接的。
    var place: String { `where` }
}

/// 只有路径或文字一项的结果。
struct PathResult: Decodable, Sendable {
    let path: String
}

struct TextResult: Decodable, Sendable {
    let text: String
}
