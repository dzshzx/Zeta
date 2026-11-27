import ActivityKit
import Foundation

/// 实时活动的哪一种样子：在回答、等你回应、答完了。
enum ZetaActivityPhase: String, Codable, Hashable, Sendable {
    case running, waiting, finished
}

/// 灵动岛和锁屏上的实时活动（Live Activity）：每个在回答或等你回应的对话一个，App 和扩展 ZetaActivity 共用这份定义。
/// 内容只由 App 更新（没有推送服务）：App 退到后台、SSH 断开以后停在最后一次的样子，回到前台再接着更新。
struct ZetaActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable, Sendable {
        var phase: ZetaActivityPhase
        /// 现在在做什么（核心给的那句、等你回应的是什么、答完了的结局）。
        var line: String
        /// 这一轮从什么时候开始跑，岛上按它走秒。
        var started: Date
    }

    let backend: String
    let session: String
    let title: String
    let device: String

    /// 点活动打开这个会话的网址：scheme 是 App 的包名（正式版和测试版各一个，不会打开另一个）。
    func openURL(scheme: String) -> URL? {
        var parts = URLComponents()
        parts.scheme = scheme
        parts.host = "session"
        parts.queryItems = [URLQueryItem(name: "backend", value: backend), URLQueryItem(name: "id", value: session)]
        return parts.url
    }

    /// 从 openURL 读回设备和会话号。
    static func route(_ url: URL) -> (backend: String, id: String)? {
        guard url.host == "session", let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let backend = parts.queryItems?.first(where: { $0.name == "backend" })?.value,
              let id = parts.queryItems?.first(where: { $0.name == "id" })?.value, !id.isEmpty
        else { return nil }
        return (backend, id)
    }
}
