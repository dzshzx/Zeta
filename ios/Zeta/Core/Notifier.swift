import Foundation
import Observation
import UIKit
import UserNotifications

/// 某个会话的一件事：开始在跑、开始等你、答完一轮、不再等你。通知照它发，别的（比如以后的灵动岛）在 Notifier.onEvent 上接。
struct SessionEvent: Sendable {
    enum Kind: Sendable {
        case running, waiting, finished, settled
    }

    let backend: String
    /// 会话号；还没发过消息的新对话为空。
    let id: String
    let title: String
    let kind: Kind
    /// 通知上的一句（答完时是结局，等你时说等的是什么）。
    let body: String
}

/// 本地通知（同桌面版 windows/internal/ui/notify.go）：App 不在前台，或在前台但看的不是那个会话时，答完一轮、开始等你回应就发一条。
/// 聊天页开着的对话照它自己的推送（答完的各轮、新来的请求），别的会话照核心报的忙闲（state 的 side.alerts）。
/// 每个会话一个编号，后一条换掉前一条；点通知打开那个会话；等你回应的那条在请求处理掉后撤掉。第一次要发时才请求权限。
/// 退到后台时申请一段后台时间，尽量把正在跑的这一轮收完（系统给多久不由 App 定）。
@MainActor @Observable
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()

    /// 点了通知要打开的会话，RootView 看到后打开并清掉。
    var opening: Route?
    /// 每件事都交给它（在主线程上）。
    @ObservationIgnored var onEvent: ((SessionEvent) -> Void)?

    @ObservationIgnored private var asked = false
    @ObservationIgnored private var waitingNotes: Set<String> = []
    @ObservationIgnored private var activities: [String: String] = [:]
    @ObservationIgnored private var alerted: String?
    @ObservationIgnored private var alertNote: String?
    @ObservationIgnored private var background: UIBackgroundTaskIdentifier = .invalid

    func install() {
        UNUserNotificationCenter.current().delegate = self
    }

    private static func noteID(_ backend: String, _ id: String) -> String {
        id.isEmpty ? "zeta-new" : "zeta-\(backend)/\(id)"
    }

    /// 每份新的 state 交进来（AppModel.refresh 里调）。
    func observe(_ state: CoreState) {
        let active = UIApplication.shared.applicationState == .active
        let open = state.chat.map { Self.noteID($0.backend, $0.session) }
        observeRows(state)
        for alert in state.side.alerts {
            let id = Self.noteID(alert.backend, alert.id)
            switch alert.kind {
            case "settled":
                emit(SessionEvent(backend: alert.backend, id: alert.id, title: alert.title, kind: .settled, body: ""))
                withdraw(id)
            case "waiting":
                let event = SessionEvent(backend: alert.backend, id: alert.id, title: alert.title, kind: .waiting,
                                         body: String(localized: "等你回应"))
                emit(event)
                if !active || open != id { post(event, waiting: true) }
            default:
                let event = SessionEvent(backend: alert.backend, id: alert.id, title: alert.title, kind: .finished,
                                         body: String(localized: "答完了"))
                emit(event)
                if !active || open != id { post(event, waiting: false) } else { withdraw(id) }
            }
        }
        if let chat = state.chat { observeChat(chat, active: active) }
        if let chat = state.chat, chat.busy || !chat.prompts.isEmpty {
            return
        }
        endBackground()
    }

    /// 会话行的忙闲变成在回答时报一次 running（通知不发）。
    private func observeRows(_ state: CoreState) {
        var next: [String: String] = [:]
        for row in state.sessions {
            next[row.key] = row.activity
            if row.activity == "answering", let was = activities[row.key], was != "answering" {
                emit(SessionEvent(backend: row.backend, id: row.id, title: row.title, kind: .running, body: ""))
            }
        }
        activities = next
    }

    /// 聊天页开着的对话：答完的各轮，和新来的等你回应的请求（每个只提醒一次）；提醒过的请求不在了就撤掉。
    private func observeChat(_ chat: ChatState, active: Bool) {
        let id = Self.noteID(chat.backend, chat.session)
        if let note = alertNote, !chat.prompts.contains(where: { $0.requestID == alerted }) {
            withdraw(note)
            alertNote = nil
            emit(SessionEvent(backend: chat.backend, id: chat.session, title: chat.title, kind: .settled, body: ""))
        }
        var body = chat.finished.last
        var waiting = false
        if let prompt = chat.prompts.first, prompt.requestID != alerted {
            alerted = prompt.requestID
            body = Self.waitingText(prompt)
            waiting = true
        }
        guard let body else { return }
        let event = SessionEvent(backend: chat.backend, id: chat.session, title: chat.title,
                                 kind: waiting ? .waiting : .finished, body: body)
        emit(event)
        // 开着的对话在眼前时不发
        guard !active else { return }
        post(event, waiting: waiting)
        alertNote = waiting ? id : nil
    }

    private func emit(_ event: SessionEvent) { onEvent?(event) }

    /// 等你回应的请求在通知上的一句（同桌面版 waitingText 的简化）。
    static func waitingText(_ prompt: Prompt) -> String {
        if let questions = prompt.questions, !questions.isEmpty {
            let first = questions[0].question.trimmingCharacters(in: .whitespacesAndNewlines)
            return questions.count == 1 && !first.isEmpty ? String(localized: "有个问题等你回答：\(first)")
                : String(localized: "有几个问题等你回答")
        }
        if prompt.isPlan == true { return String(localized: "计划等你批准") }
        if prompt.kind == "elicitation" { return String(localized: "MCP 服务等你回应") }
        return String(localized: "等你确认：\(prompt.toolName ?? "")")
    }

    private func post(_ event: SessionEvent, waiting: Bool) {
        let id = Self.noteID(event.backend, event.id)
        if waiting { waitingNotes.insert(id) } else { waitingNotes.remove(id) }
        let content = UNMutableNotificationContent()
        content.title = event.title
        content.body = event.body
        content.sound = .default
        content.threadIdentifier = id
        content.userInfo = ["backend": event.backend, "id": event.id]
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        let ask = !asked
        asked = true
        Task {
            let center = UNUserNotificationCenter.current()
            if ask {
                _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
            }
            try? await center.add(request)
        }
    }

    /// 撤掉编号 id 的通知，如果它还是等你回应的那条。
    private func withdraw(_ id: String) {
        guard waitingNotes.remove(id) != nil else { return }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
    }

    /// 退到后台：聊天页的对话还在跑时申请一段后台时间，跑完（observe 里看到不忙了）或系统收回时结束。
    func enteredBackground(busy: Bool) {
        guard busy, background == .invalid else { return }
        background = UIApplication.shared.beginBackgroundTask(withName: "zeta-turn") {
            Task { @MainActor in Notifier.shared.endBackground() }
        }
    }

    func endBackground() {
        guard background != .invalid else { return }
        UIApplication.shared.endBackgroundTask(background)
        background = .invalid
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        let info = response.notification.request.content.userInfo
        guard let backend = info["backend"] as? String, let id = info["id"] as? String, !id.isEmpty else { return }
        await MainActor.run { Notifier.shared.opening = .session(backend: backend, id: id) }
    }
}
