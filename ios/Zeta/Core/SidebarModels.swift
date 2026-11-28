import Foundation

// 会话列表管理、后台任务、子代理、通知用到的 JSON（hub/mobile 的 sessions.go、tasks.go）。

/// state 里侧栏的其余部分。
struct Side: Decodable, Sendable {
    /// replied、created、manual。
    var sort = "replied"
    var searching = false
    /// 对话内容搜到哪了：running、done、partial、failed，不在搜时为空。
    var search = ""
    var background: [BackgroundChat] = []
    /// 上次 state 以后别的会话的提醒，只交一次。
    var alerts: [SessionAlert] = []
    var failed = ""
}

/// 后台任务面板「后台对话」的一行。
struct BackgroundChat: Decodable, Sendable, Hashable {
    let backend: String
    let id: String
    let title: String
    /// waiting、answering、background、idle。
    let activity: String
    let tasks: Int
    private let rawHarness: String?

    enum CodingKeys: String, CodingKey {
        case backend, id, title, activity, tasks
        case rawHarness = "harness"
    }

    var isAgy: Bool { rawHarness == Harness.agy }
    var key: String { backend + "/" + id }
}

/// 别的会话的提醒：kind 是 finished、waiting、settled。
struct SessionAlert: Decodable, Sendable {
    let backend: String
    let id: String
    let title: String
    let kind: String
}

/// 对话的一个后台任务（helper/tasks 的 Task）。
struct TaskInfo: Decodable, Sendable, Identifiable, Hashable {
    let id: String
    let kind: String
    let description: String
    let command: String?
    let agentType: String?
    let toolUseID: String?
    let startedAt: Int64
    let endedAt: Int64?
    /// running、completed、failed、stopped、ended。
    let state: String
    let stopping: Bool?
    let progress: String?
    let toolUses: Int?
    let summary: String?
    let error: String?
    let running: Bool
    let readable: Bool

    enum CodingKeys: String, CodingKey {
        case id, kind, description, command, state, stopping, progress, summary, error, running, readable
        case agentType = "agent_type"
        case toolUseID = "tool_use_id"
        case startedAt = "started_at"
        case endedAt = "ended_at"
        case toolUses = "tool_uses"
    }

    /// 跑了多久（还在跑时到 now）。主机的钟和这台的可能对不上，只作参考。
    func elapsed(now: Date) -> Duration {
        let end = endedAt ?? Int64(now.timeIntervalSince1970 * 1000)
        return .milliseconds(max(end - startedAt, 0))
    }

    /// 已运行时间的写法：1h 2m 3s。
    static let elapsedStyle = Duration.UnitsFormatStyle(allowedUnits: [.hours, .minutes, .seconds], width: .narrow)

    var title: String { description.isEmpty ? (command ?? "") : description }

    var stateText: LocalizedStringResource {
        switch state {
        case "running": stopping == true ? "正在停止" : "运行中"
        case "completed": "已完成"
        case "failed": "失败"
        case "stopped": "已停止"
        default: "已结束"
        }
    }
}

/// 任务清单的一项：status 是 pending、in_progress、completed。
struct Todo: Decodable, Sendable, Hashable, Identifiable {
    let key: String
    let content: String
    let activeForm: String
    let status: String

    var id: String { key }

    enum CodingKeys: String, CodingKey {
        case key, content, status
        case activeForm = "active_form"
    }
}

/// 方法 taskoutput 的结果。
struct TaskOutput: Decodable, Sendable {
    let got: Bool
    let text: String
    let totalBytes: Int64
    let truncated: Bool
    let error: String

    enum CodingKeys: String, CodingKey {
        case got, text, truncated, error
        case totalBytes = "total_bytes"
    }
}

/// 方法 subagent 的结果。
struct SubagentPage: Decodable, Sendable {
    let meta: SubagentMeta?
    /// 核心给的状态：running、loading、read_failed、missing、completed、failed、stopped、unfinished，还没推来时为空。
    let state: String
    let prompt: String
    let toolUses: Int
    let running: Bool
    let failure: String
    let items: [Item]
    let rev: Int
    let watched: Bool

    enum CodingKeys: String, CodingKey {
        case meta, state, prompt, running, failure, items, rev, watched
        case toolUses = "tool_uses"
    }
}

/// 子代理记录旁的说明。
struct SubagentMeta: Decodable, Sendable {
    let agentType: String
    let description: String
    let model: String
    /// 思考强度（low、medium 这些），不知道时没有。
    let effort: String?

    enum CodingKeys: String, CodingKey {
        case description, model, effort
        case agentType = "agent_type"
    }
}

/// 方法 export 的结果。
struct Exported: Decodable, Sendable {
    let name: String
    let content: String
}
