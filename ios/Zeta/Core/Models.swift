import Foundation

// 核心交来的 JSON（windows/internal/mobile 的 State、chat/items、helper/prompt）。不用 convertFromSnakeCase：
// 它连工具参数这类字典里的键也改写，带下划线的字段各自写 CodingKeys。

/// 方法 state 的结果。
struct CoreState: Decodable, Sendable {
    var devices: [Device] = []
    var listed = ""
    var backend = ""
    var sessions: [SessionRow] = []
    var problem = ""
    var chat: ChatState?

    static let empty = CoreState()
}

struct Device: Decodable, Sendable, Identifiable, Hashable {
    let id: String
    let name: String
    let target: String
    let home: String
    let off: Bool
    let fingerprint: String
}

struct SessionRow: Decodable, Sendable, Hashable {
    let backend: String
    let id: String
    let title: String
    let place: String
    let updated: Int64
    let pinned: Bool
    let external: Bool
    let ended: Bool
    let archived: Bool

    /// 在各台设备之间不重的键。
    var key: String { backend + "/" + id }
    var updatedAt: Date { Date(timeIntervalSince1970: TimeInterval(updated) / 1000) }
}

struct ChatState: Decodable, Sendable {
    let backend: String
    let session: String
    let title: String
    let device: String
    let cwd: String
    let opening: Bool
    let problem: String
    let linkProblem: String
    let connected: Bool
    let busy: Bool
    let blocked: Bool
    let status: String
    let model: String
    let earlier: Int
    let prompts: [Prompt]
    let items: [ItemsChange]
    let failed: String

    enum CodingKeys: String, CodingKey {
        case backend, session, title, device, cwd, opening, problem, connected, busy, blocked, status, model, earlier
        case prompts, items, failed
        case linkProblem = "link_problem"
    }
}

/// 条目的一次变化：full 时 items 是整份，否则从 start（JSON 的 at）起去掉 remove 项、换成 items。
struct ItemsChange: Decodable, Sendable {
    let full: Bool
    let start: Int
    let remove: Int
    let items: [Item]?

    enum CodingKeys: String, CodingKey {
        case full, remove, items
        case start = "at"
    }
}

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
    let files: [String]?
    let peer: String?
    let streaming: Bool?
    let error: Bool?
    let steps: [Step]?
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

    var isThinking: Bool { kind == "thinking" }
    var isRunning: Bool { isThinking ? running : status == "running" }
    var failed: Bool { status == "failed" }

    /// 工具调用给人看的一行：参数里最能说明它在做什么的那项。
    var summary: String { input?.summary ?? "" }
}

struct Prompt: Decodable, Sendable, Identifiable {
    let kind: String
    let requestID: String
    let toolName: String?
    let input: JSONValue?
    let questions: [Question]?
    let suggestions: [JSONValue]?
    let reason: String?
    let agentLabel: String?
    let isPlan: Bool?
    let message: String?
    let url: String?

    var id: String { requestID }
    var isPermission: Bool { kind == "permission" }

    enum CodingKeys: String, CodingKey {
        case kind, input, questions, suggestions, reason, message, url
        case requestID = "request_id"
        case toolName = "tool_name"
        case agentLabel = "agent_label"
        case isPlan = "is_plan"
    }
}

struct Question: Decodable, Sendable, Hashable {
    let question: String
    let header: String
    let options: [Option]
    let multiSelect: Bool?

    struct Option: Decodable, Sendable, Hashable {
        let label: String
        let description: String
    }

    enum CodingKeys: String, CodingKey {
        case question, header, options
        case multiSelect = "multi_select"
    }
}

/// 方法 probe 的结果。
struct Probed: Decodable, Sendable {
    let token: String
    let summary: String
    let hostname: String
    let fingerprint: String
}

/// 任意 JSON（工具参数）。
enum JSONValue: Decodable, Sendable, Hashable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSONValue])
    case array([JSONValue])
    case null

    init(from decoder: any Decoder) throws {
        let box = try decoder.singleValueContainer()
        if box.decodeNil() {
            self = .null
        } else if let value = try? box.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? box.decode(Double.self) {
            self = .number(value)
        } else if let value = try? box.decode(String.self) {
            self = .string(value)
        } else if let value = try? box.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try box.decode([String: JSONValue].self))
        }
    }

    subscript(key: String) -> JSONValue? {
        if case .object(let fields) = self { fields[key] } else { nil }
    }

    var string: String? {
        if case .string(let text) = self { text } else { nil }
    }

    /// 参数里最能说明在做什么的一项，按这个先后找。
    var summary: String? {
        for key in ["description", "command", "file_path", "path", "pattern", "url", "query", "prompt", "skill"] {
            if let text = self[key]?.string, !text.isEmpty { return text }
        }
        return nil
    }
}
