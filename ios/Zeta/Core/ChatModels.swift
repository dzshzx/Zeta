import Foundation

// 聊天页的条目和等你回应的请求（hub/chat/items、helper/prompt、helper/sessionproto 的 JSON）。

struct Item: Decodable, Sendable, Identifiable {
    enum Kind: String, Decodable, Sendable {
        case user, answer, notice, work, thought
        case unknown

        init(from decoder: any Decoder) throws {
            self = Kind(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .unknown
        }
    }

    let kind: Kind
    let id: String
    let text: String
    let images: [ChatImage]?
    let files: [String]?
    let peer: String?
    let streaming: Bool?
    let error: Bool?
    let steps: [Step]?
    /// 这一轮能改写、重新生成、删除、还原代码（rewindable），能分支（forkable）；操作挂在哪一项下面（roundLast）。
    let rewindable: Bool?
    let forkable: Bool?
    let roundLast: String?

    enum CodingKeys: String, CodingKey {
        case kind, id, text, images, files, peer, streaming, error, steps, rewindable, forkable
        case roundLast = "round_last"
    }

    /// 能拿来查找的字：正文，工作组里是各步的思考、工具名、参数摘要和结果。
    var searchable: String {
        guard let steps else { return text }
        return steps.map { [$0.text, $0.name, $0.summary, $0.result].joined(separator: "\n") }.joined(separator: "\n")
    }
}

/// 聊天里的一张图（items.Image）：怎么取由 kind 定，image 方法照它取。
struct ChatImage: Decodable, Sendable, Hashable {
    let kind: String
    let key: String?
    let data: String?
    let file: String?
    let ref: String?
    let url: String?
    let original: String?

    init(linked url: String) {
        kind = "linked"
        self.url = url
        key = nil
        data = nil
        file = nil
        ref = nil
        original = nil
    }

    /// 同一张图只取一次（同 items.Image.CacheKey）。
    var cacheKey: String {
        switch kind {
        case "inline": "inline:" + (key ?? "")
        case "linked": "linked:" + (url ?? "")
        default: "session:" + (file ?? "") + "#" + (ref ?? "")
        }
    }

    /// 网络上的图：取它会把 IP 交给对方，等用户点一下（或设置里开了自动加载）。
    var remote: Bool {
        kind == "linked" && (url?.hasPrefix("https://") == true || url?.hasPrefix("http://") == true)
    }

    var params: [String: any Sendable] {
        ["kind": kind, "key": key ?? "", "file": file ?? "", "ref": ref ?? "", "url": url ?? ""]
    }
}

struct Step: Decodable, Sendable, Identifiable {
    let kind: String
    let id: String
    let text: String
    let running: Bool
    let seconds: Int?
    let name: String
    let input: JSONValue?
    let status: String
    let result: String
    let images: [ChatImage]?
    let patch: Patch?
    let clipped: Bool?

    var isThinking: Bool { kind == "thinking" }
    var isRunning: Bool { isThinking ? running : status == "running" }
    var failed: Bool { status == "failed" }

    /// 工具调用给人看的一行：参数里最能说明它在做什么的那项。
    var summary: String { input?.summary ?? "" }
}

/// 改文件工具给出的改动（items.Patch）。
struct Patch: Decodable, Sendable {
    let created: Bool?
    let hunks: [Hunk]

    struct Hunk: Decodable, Sendable {
        let lines: [Line]
    }

    struct Line: Decodable, Sendable {
        let kind: String
        let text: String
        let old: Int
        let new: Int
    }

    var added: Int { hunks.reduce(0) { $0 + $1.lines.filter { $0.kind == "added" }.count } }
    var removed: Int { hunks.reduce(0) { $0 + $1.lines.filter { $0.kind == "removed" }.count } }
}

struct Prompt: Decodable, Sendable, Identifiable {
    let kind: String
    let requestID: String
    let toolName: String?
    let input: JSONValue?
    let questions: [Question]?
    let suggestions: [JSONValue]?
    let suggestionLines: [String]?
    let reason: String?
    let agentLabel: String?
    let isPlan: Bool?
    let server: String?
    let message: String?
    let url: String?
    let fields: [Field]?

    var id: String { requestID }
    var isPermission: Bool { kind == "permission" }
    /// 普通的工具确认（不是提问、计划审批、MCP 表单）：通知上能直接允许、拒绝。
    var isToolConfirmation: Bool { isPermission && (questions ?? []).isEmpty && isPlan != true }

    enum CodingKeys: String, CodingKey {
        case kind, input, questions, suggestions, reason, server, message, url, fields
        case requestID = "request_id"
        case toolName = "tool_name"
        case suggestionLines = "suggestion_lines"
        case agentLabel = "agent_label"
        case isPlan = "is_plan"
    }
}

struct Question: Decodable, Sendable, Hashable {
    let question: String
    let header: String
    let options: [Option]
    let multiSelect: Bool?
    let takesNotes: Bool?

    struct Option: Decodable, Sendable, Hashable {
        let label: String
        let description: String
        let preview: String?
    }

    enum CodingKeys: String, CodingKey {
        case question, header, options
        case multiSelect = "multi_select"
        case takesNotes = "takes_notes"
    }
}

/// MCP 表单的一项（prompt.Field）。
struct Field: Decodable, Sendable, Hashable {
    let key: String
    let title: String
    let description: String?
    let required: Bool?
    let kind: String
    let choices: [Choice]?
    let `default`: [String]?
    let format: String?

    struct Choice: Decodable, Sendable, Hashable {
        let value: String
        let label: String
    }
}

/// 会话被别处占着时那个进程的样子（sessionproto.Occupancy）。
struct Occupancy: Decodable, Sendable, Hashable {
    let status: String?
    let waitingFor: String?
    let handsOver: Bool
    let app: String?
    let device: String?
    let local: Bool?

    enum CodingKeys: String, CodingKey {
        case status, app, device, local
        case waitingFor = "waiting_for"
        case handsOver = "hands_over"
    }
}

/// codechanges 的结果：还原代码会动到的文件。
struct CodeChanges: Decodable, Sendable {
    let files: [String]
    let insertions: Int
    let deletions: Int
}

/// image 的结果。
struct Picture: Decodable, Sendable {
    let data: String
}

/// elicit 的结果：表单不对的各项。
struct Elicited: Decodable, Sendable {
    let problems: [String: String]?
}

extension AppModel {
    /// 把自己发过的一条消息收藏为常用指令（同桌面版的 saveMessage）：经输入栏的 prompts.add，同样的内容不再存，
    /// 答的是 toast 里写的一句。常用指令的管理在输入栏那边。
    func saveAsPrompt(_ text: String) async -> String {
        do {
            _ = try editPrompts("prompts.add", ["text": text, "dedupe": true])
            return String(localized: "已收藏为常用指令")
        } catch {
            return error.localizedDescription
        }
    }
}
