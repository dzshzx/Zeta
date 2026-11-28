import Foundation

// 核心交来的 JSON（hub/mobile 的 State、chat/items、helper/prompt）。不用 convertFromSnakeCase：
// 它连工具参数这类字典里的键也改写，带下划线的字段各自写 CodingKeys。

/// 方法 state 的结果。
struct CoreState: Decodable, Sendable {
    var devices: [Device] = []
    var listed = ""
    var backend = ""
    var sessions: [SessionRow] = []
    var problem = ""
    var chat: ChatState?
    var side = Side()

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
    let activity: String
    let tasks: Int
    let snippet: String
    private let rawDraft: String?
    private let rawHarness: String?

    enum CodingKeys: String, CodingKey {
        case backend, id, title, place, updated, pinned, external, ended, archived, activity, tasks, snippet
        case rawDraft = "draft"
        case rawHarness = "harness"
    }

    /// 存着的没发出的草稿的一行预览，没有时为空。
    var draft: String { rawDraft ?? "" }
    /// 会话所属的工具，缺省回退 "claude"。
    var harness: String {
        guard let rawHarness, !rawHarness.isEmpty else { return Harness.claude }
        return rawHarness
    }
    var isAgy: Bool { harness == Harness.agy }
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
    let composer: ComposerState?

    let tasks: [TaskInfo]
    let todos: [Todo]
    let process: Bool
    let finished: [String]
    private let rawHarness: String?
    let canSwitchHarness: Bool?
    // 聊天页的操作要的（ChatModels.swift）；旧核心没有时为 nil
    let canRewind: Bool?
    let rewinding: Bool?
    let earlierLoading: Bool?
    let occupant: Occupancy?
    let handing: Bool?
    let handoffProblem: String?
    let autoImages: Bool?

    var harness: String {
        guard let rawHarness, !rawHarness.isEmpty else { return Harness.claude }
        return rawHarness
    }
    var isAgy: Bool { harness == Harness.agy }

    enum CodingKeys: String, CodingKey {
        case backend, session, title, device, cwd, opening, problem, connected, busy, blocked, status, model, earlier
        case prompts, items, failed, composer
        case tasks, todos, process, finished
        case rawHarness = "harness"
        case canSwitchHarness = "can_switch_harness"
        case linkProblem = "link_problem"
        case canRewind = "can_rewind", earlierLoading = "earlier_loading", handoffProblem = "handoff_problem"
        case autoImages = "auto_images"
        case rewinding, occupant, handing
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

    var int: Int? {
        if case .number(let num) = self { Int(exactly: num) } else { nil }
    }

    var array: [JSONValue]? {
        if case .array(let items) = self { items } else { nil }
    }

    private static let summaryKeys = [
        "description", "toolSummary", "command", "CommandLine", "file_path", "Path", "path",
        "Pattern", "pattern", "Query", "query", "SearchPath", "SearchDirectory", "url", "Uri",
        "Role", "Name", "Description", "Prompt", "prompt", "skill", "Action", "Recipient"
    ]

    /// 参数里最能说明在做什么的一项，按这个先后找。
    var summary: String? {
        for key in Self.summaryKeys {
            if let text = self[key]?.string, !text.isEmpty { return text }
        }
        return nil
    }
}
