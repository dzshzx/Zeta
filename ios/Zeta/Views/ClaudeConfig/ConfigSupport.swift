import SwiftUI

/// 一台设备的工具配置的请求：config.*、system.* 经核心桥 core 转给那台的核心（config.* 带上 harness），
/// 慢的（要跑 claude / agy、连 MCP 服务器）核心桥对它们等得更久。都在后台线程上等。
@MainActor
struct ConfigCalls {
    let model: AppModel
    let backend: String
    var harness = Harness.claude

    var isAgy: Bool { harness == Harness.agy }

    func get<T: Decodable & Sendable>(
        _ method: String, _ params: [String: any Sendable] = [:], as type: T.Type
    ) async throws -> T {
        var all = params
        if method.hasPrefix("config."), all["harness"] == nil {
            all["harness"] = harness
        }
        return try await model.device(backend, method, all, as: type)
    }

    func run(_ method: String, _ params: [String: any Sendable] = [:]) async throws {
        _ = try await get(method, params, as: Ignored.self)
    }

    func slow<T: Decodable & Sendable>(
        _ method: String, _ params: [String: any Sendable] = [:], as type: T.Type
    ) async throws -> T {
        try await get(method, params, as: type)
    }

    func hub<T: Decodable & Sendable>(
        _ method: String, _ params: [String: any Sendable] = [:], as type: T.Type
    ) async throws -> T {
        var all = params
        all["backend"] = backend
        return try await model.request(method, all, as: type)
    }

    func cliVersion() async throws -> String {
        try await get(isAgy ? "system.agy" : "system.claude", as: ClaudeVersion.self).version ?? ""
    }

    func updateCLI() async throws -> ClaudeUpdate {
        try await slow(isAgy ? "system.agy_update" : "system.claude_update", as: ClaudeUpdate.self)
    }
}

/// 弹一句没成功的原因（标题加核心说的原因）。
struct ConfigFailure: Identifiable {
    let id = UUID()
    let title: String
    let message: String

    init(_ title: String, _ error: any Error) {
        self.title = title
        message = error.localizedDescription
    }

    init(title: String, message: String) {
        self.title = title
        self.message = message
    }
}

extension View {
    /// 没成功时弹出的提示。
    func configFailure(_ failure: Binding<ConfigFailure?>) -> some View {
        let shown = Binding { failure.wrappedValue != nil } set: { if !$0 { failure.wrappedValue = nil } }
        return alert(failure.wrappedValue?.title ?? "", isPresented: shown, presenting: failure.wrappedValue) { _ in
            Button("知道了") {}
        } message: { item in
            Text(item.message)
        }
    }
}

/// 界面上的说法：思考强度、权限模式、规则所在的表、闲置超时、token 数、文件大小（同桌面版 labels.go）。
enum ConfigLabels {
    static let effortLevels = ["low", "medium", "high", "xhigh", "max"]

    static func effort(_ value: String) -> String {
        switch value {
        case "low": String(localized: "低")
        case "medium": String(localized: "中")
        case "high": String(localized: "高")
        case "xhigh": String(localized: "超高")
        case "max": String(localized: "最高")
        default: value
        }
    }

    static let permissionModes = ["default", "acceptEdits", "plan", "auto", "bypassPermissions"]

    static func permissionMode(_ value: String) -> String {
        switch value {
        case "", "default": String(localized: "逐项确认")
        case "acceptEdits": String(localized: "自动接受编辑")
        case "plan": String(localized: "计划模式")
        case "auto": String(localized: "自动判断")
        case "bypassPermissions": String(localized: "完全放行")
        default: value
        }
    }

    static let behaviors = ["allow", "ask", "deny"]

    static func behavior(_ value: String) -> String {
        switch value {
        case "allow": String(localized: "允许")
        case "ask": String(localized: "询问")
        case "deny": String(localized: "拒绝")
        default: String(localized: "未设置")
        }
    }

    static let compactWindows: [Int64] = [200_000, 300_000, 400_000, 500_000, 600_000, 800_000, 1_000_000]
    static let idleChoices = [5, 10, 30, 60, 120, 0]

    static func idle(_ minutes: Int) -> String {
        if minutes == 0 { return String(localized: "不自动结束") }
        if minutes % 1440 == 0 { return String(localized: "\(String(minutes / 1440)) 天") }
        if minutes % 60 == 0 { return String(localized: "\(String(minutes / 60)) 小时") }
        return String(localized: "\(String(minutes)) 分钟")
    }

    static func compactPercent(_ percent: Int) -> String {
        percent == 0 ? String(localized: "默认（快满时）") : String(localized: "用到 \(String(percent))% 时")
    }

    /// 1234 → 1.2K，400000 → 400K，1000000 → 1M。
    static func tokens(_ value: Int64) -> String {
        func trim(_ text: String) -> String {
            guard text.contains(".") else { return text }
            var out = text
            while out.hasSuffix("0") { out.removeLast() }
            if out.hasSuffix(".") { out.removeLast() }
            return out
        }
        let number = Double(value)
        if value >= 999_500 { return trim(String(format: "%.2f", number / 1e6)) + "M" }
        if value >= 99_950 { return String(format: "%.0f", number / 1e3) + "K" }
        if value >= 1000 { return trim(String(format: "%.1f", number / 1e3)) + "K" }
        return String(max(value, 0))
    }

    static func size(_ bytes: Int64) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        if bytes < 1 << 20 { return String(format: "%.1f KiB", Double(bytes) / 1024) }
        return String(format: "%.2f MiB", Double(bytes) / Double(1 << 20))
    }

    /// 完整模型 ID 写成人看的名字（claude-opus-5-5 → Opus 5.5，[1m] 加 (1M)），别名原样（同 hub/chat.ReadableModel）。
    static func readableModel(_ id: String) -> String {
        guard let match = id.wholeMatch(of: /claude-([a-z]+)-(\d+(?:-\d{1,2})*)(?:-\d{8})?(\[1m\])?/) else { return id }
        let family = String(match.1)
        let version = String(match.2).replacingOccurrences(of: "-", with: ".")
        var name = family.prefix(1).uppercased() + String(family.dropFirst()) + " " + version
        if match.3 != nil { name += " (1M)" }
        return name
    }

    /// 多行压成一行、截到 limit 个字符。
    static func clip(_ text: String, _ limit: Int) -> String {
        let line = text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return line.count > limit ? String(line.prefix(limit)) + "…" : line
    }
}

/// 「正在读取…」一行。
struct LoadingRow: View {
    var text: LocalizedStringKey = "正在读取…"

    var body: some View {
        HStack {
            Text(text).foregroundStyle(.secondary)
            Spacer()
            ProgressView()
        }
    }
}

/// 左边名字、下面一行小字的一行。
struct TitledRow: View {
    let title: String
    var summary = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            if !summary.isEmpty {
                Text(summary).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}
