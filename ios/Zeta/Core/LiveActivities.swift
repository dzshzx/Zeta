import ActivityKit
import Foundation

/// 灵动岛与锁屏的实时活动（定义见 Shared/ZetaActivity.swift，画法在扩展 ZetaActivity）。每份新的 state 对一遍：
/// 在回答或等你回应的对话各有一个活动，没有的建上、变了的更新；不再在跑的改成「答完了」，几秒后收起。
/// 聊天页开着的对话用它自己的状态（核心给的那句、等你回应的是什么），别的会话用会话列表报的忙闲。
/// 只由 App 更新，没有推送：App 退到后台、SSH 断开后停在最后的样子，回到前台再对上。
@MainActor
final class LiveActivities {
    static let shared = LiveActivities()

    private typealias Content = ZetaActivityAttributes.ContentState

    private struct Wanted {
        let attributes: ZetaActivityAttributes
        let state: Content
    }

    private var live: [String: Activity<ZetaActivityAttributes>] = [:]
    private var started: [String: Date] = [:]
    private var cleared = false

    /// 答完了的那一条在岛上留多久再收起。
    private static let finishedFor: TimeInterval = 8

    func observe(_ state: CoreState, deviceName: (String) -> String) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        clearLeftovers()
        let wanted = wantedActivities(state, deviceName: deviceName)
        for (key, want) in wanted {
            if let activity = live[key] {
                if activity.content.state != want.state {
                    let content = ActivityContent(state: want.state, staleDate: nil)
                    let id = activity.id
                    Task { await Self.update(id, content) }
                }
            } else {
                start(key, want)
            }
        }
        for (key, activity) in live where wanted[key] == nil {
            live[key] = nil
            started[key] = nil
            var done = activity.content.state
            done.phase = .finished
            done.line = String(localized: "答完了")
            let content = ActivityContent(state: done, staleDate: nil)
            let until = Date.now.addingTimeInterval(Self.finishedFor)
            let id = activity.id
            Task { await Self.end(id, content, dismissalPolicy: .after(until)) }
        }
    }

    // Activity 不是 Sendable，不能从主线程带进异步的调用：只带编号过去，在那边按编号找出来再改。
    private nonisolated static func find(_ id: String) -> Activity<ZetaActivityAttributes>? {
        Activity<ZetaActivityAttributes>.activities.first { $0.id == id }
    }

    private nonisolated static func update(_ id: String, _ content: ActivityContent<Content>) async {
        await find(id)?.update(content)
    }

    private nonisolated static func end(
        _ id: String, _ content: ActivityContent<Content>?, dismissalPolicy: ActivityUIDismissalPolicy
    ) async {
        await find(id)?.end(content, dismissalPolicy: dismissalPolicy)
    }

    private func start(_ key: String, _ want: Wanted) {
        do {
            live[key] = try Activity.request(
                attributes: want.attributes, content: ActivityContent(state: want.state, staleDate: nil), pushType: nil)
        } catch {
            // 系统不让开（同时开着的太多、用户在设置里关了）：这次不显示，下一份 state 再试
            started[key] = nil
        }
    }

    /// 上次运行留下的活动（App 被杀时来不及收）：第一次对之前全部收掉。
    private func clearLeftovers() {
        guard !cleared else { return }
        cleared = true
        for activity in Activity<ZetaActivityAttributes>.activities {
            let id = activity.id
            Task { await Self.end(id, nil, dismissalPolicy: .immediate) }
        }
    }

    private func wantedActivities(_ state: CoreState, deviceName: (String) -> String) -> [String: Wanted] {
        var out: [String: Wanted] = [:]
        let now = Date.now
        func add(_ backend: String, _ session: String, _ title: String, _ phase: ZetaActivityPhase, _ line: String) {
            let key = backend + "/" + session
            let since = started[key] ?? now
            if phase == .running { started[key] = since }
            let attributes = ZetaActivityAttributes(
                backend: backend, session: session, title: title, device: deviceName(backend))
            out[key] = Wanted(attributes: attributes, state: Content(phase: phase, line: line, started: since))
        }
        for row in state.sessions {
            switch row.activity {
            case "answering": add(row.backend, row.id, row.title, .running, String(localized: "正在回答"))
            case "waiting": add(row.backend, row.id, row.title, .waiting, String(localized: "等你回应"))
            default: break
            }
        }
        // 聊天页开着的对话照它自己的状态，盖过列表里那一行（还没有会话号的新对话不显示）
        if let chat = state.chat, !chat.session.isEmpty {
            let key = chat.backend + "/" + chat.session
            if let prompt = chat.prompts.first {
                add(chat.backend, chat.session, chat.title, .waiting, Notifier.waitingText(prompt))
            } else if chat.busy {
                let line = chat.status.isEmpty ? String(localized: "正在回答") : chat.status
                add(chat.backend, chat.session, chat.title, .running, line)
            } else {
                out[key] = nil
            }
        }
        return out
    }
}
