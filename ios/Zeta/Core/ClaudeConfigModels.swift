import Foundation

// 设备的「Claude Code 配置」各页用的类型：那台核心的 config.*、system.* 的结果（照 helper/coreapi/config.go 的 JSON），
// 和核心桥 claudecfg.* 的结果（hub/mobile/claudecfg.go）。Go 的空切片会编成 null，数组都经 OrEmpty 解成空的。

/// 解 null 或缺省的数组成空数组。
@propertyWrapper
struct OrEmpty<Element: Decodable & Sendable>: Decodable, Sendable {
    var wrappedValue: [Element]

    init(wrappedValue: [Element] = []) { self.wrappedValue = wrappedValue }

    init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        wrappedValue = container.decodeNil() ? [] : try container.decode([Element].self)
    }
}

extension KeyedDecodingContainer {
    func decode<T: Decodable & Sendable>(_ type: OrEmpty<T>.Type, forKey key: Key) throws -> OrEmpty<T> {
        try decodeIfPresent(type, forKey: key) ?? OrEmpty()
    }
}

/// 不看内容的结果（只要成没成）。
struct Ignored: Decodable, Sendable {
    init(from decoder: any Decoder) throws {}
}

// MARK: 中枢自己管的

/// claudecfg.local：客户端本地存的这台的几项，和这台的默认工作目录。
struct ClaudeLocal: Decodable, Sendable {
    let autoCompact: Bool
    let compactPercent: Int
    @OrEmpty var compactPercents: [Int]
    let autoTitle: Bool
    let cwd: String
}

/// claudecfg.models 的一项。
struct ModelChoice: Decodable, Sendable, Hashable {
    let value: String
    let label: String
    let id: String
    let older: Bool
}

struct ModelChoices: Decodable, Sendable {
    /// harness、relay、unknown
    let source: String
    @OrEmpty var models: [ModelChoice] = []
}

// MARK: 新对话默认、主机

struct ModelEffort: Decodable, Sendable, Hashable {
    let model: String
    let level: String
}

struct ConfigDefaults: Decodable, Sendable {
    let model: String
    let permissionMode: String
    @OrEmpty var efforts: [ModelEffort]
    let effort: String
    let compactWindow: Int64
}

struct IdleReply: Decodable, Sendable {
    let minutes: Int
}

struct ClaudeVersion: Decodable, Sendable {
    let version: String?
}

struct ClaudeUpdate: Decodable, Sendable {
    let succeeded: Bool
    let before: String
    let version: String
    let output: String

    enum CodingKeys: String, CodingKey {
        case before, version, output
        case succeeded = "ok"
    }
}

// MARK: 记忆

struct ConfigFile: Decodable, Sendable, Hashable {
    let path: String
    let exists: Bool
    let size: Int64
}

struct MemoryProject: Decodable, Sendable, Hashable {
    let key: String
    let label: String
    let count: Int
    let `default`: Bool
}

struct MemoryEntry: Decodable, Sendable, Hashable {
    let path: String
    let fileName: String
    let name: String
    let description: String
    let type: String
    let size: Int64
}

struct MemoryPage: Decodable, Sendable {
    let autoMemory: Bool
    let forcedOff: Bool
    let customDir: String
    let instructions: ConfigFile
    @OrEmpty var projects: [MemoryProject]
    let selected: String
    let dir: String
    let index: ConfigFile?
    @OrEmpty var files: [MemoryEntry]
}

struct ConfigDocument: Decodable, Sendable {
    let content: String
    let sha: String
    let exists: Bool
}

struct WriteReply: Decodable, Sendable {
    let sha: String?
    let conflict: Bool?
}

// MARK: Skills

struct Skill: Decodable, Sendable, Hashable {
    let name: String
    let title: String
    let description: String
    let enabled: Bool
    let valid: Bool
}

struct SyncedSkill: Decodable, Sendable, Hashable {
    let name: String
    let description: String
    let enabled: Bool
}

struct SkillsPage: Decodable, Sendable {
    @OrEmpty var user: [Skill]
    @OrEmpty var synced: [SyncedSkill]
}

struct SkillImport: Decodable, Sendable {
    let name: String
    let exists: Bool
    let existingDescription: String
    let replaced: Bool
    let enabled: Bool
}

// MARK: Subagent

struct Subagent: Decodable, Sendable, Hashable {
    let file: String
    let name: String
    let description: String
    let model: String
    /// nil 是全部工具
    let tools: [String]?
    let prompt: String
    @OrEmpty var extraKeys: [String]
    let sha: String

    static func == (lhs: Subagent, rhs: Subagent) -> Bool { lhs.file == rhs.file && lhs.sha == rhs.sha }
    func hash(into hasher: inout Hasher) { hasher.combine(file) }

    /// 交回 config.agents.save 的 original。
    var params: [String: any Sendable] {
        var out: [String: any Sendable] = [
            "file": file, "name": name, "description": description, "model": model, "prompt": prompt,
            "extraKeys": extraKeys, "sha": sha
        ]
        if let tools { out["tools"] = tools }
        return out
    }
}

struct SavedAgent: Decodable, Sendable {
    let file: String
}

// MARK: 工具权限

struct PermRule: Decodable, Sendable, Hashable {
    let behavior: String
    let rule: String

    var params: [String: any Sendable] { ["behavior": behavior, "rule": rule] }
}

struct RuleView: Decodable, Sendable, Hashable {
    let behavior: String
    let rule: String
    let malformed: Bool

    var plain: PermRule { PermRule(behavior: behavior, rule: rule) }
}

struct ToolState: Decodable, Sendable, Hashable {
    let behavior: String
    let inherited: Bool
    @OrEmpty var specific: [PermRule]

    static func == (lhs: ToolState, rhs: ToolState) -> Bool {
        lhs.behavior == rhs.behavior && lhs.inherited == rhs.inherited && lhs.specific == rhs.specific
    }
    func hash(into hasher: inout Hasher) { hasher.combine(behavior) }
}

struct ToolView: Decodable, Sendable, Hashable {
    let name: String
    let label: String
    let example: String
    let rule: String
    let own: String
    let more: Bool
    let state: ToolState
}

struct BulkChange: Decodable, Sendable, Hashable {
    @OrEmpty var add: [PermRule]
    @OrEmpty var remove: [PermRule]

    var isEmpty: Bool { add.isEmpty && remove.isEmpty }
    static func == (lhs: BulkChange, rhs: BulkChange) -> Bool { lhs.add == rhs.add && lhs.remove == rhs.remove }
    func hash(into hasher: inout Hasher) { hasher.combine(add.count) }
}

struct BulkPair: Decodable, Sendable, Hashable {
    let enable: BulkChange
    let disable: BulkChange
}

struct ServerView: Decodable, Sendable, Hashable {
    let name: String
    let rule: String
    let scope: String
    let oauth: Bool
    let state: ToolState
    let bulk: BulkPair
}

struct PermissionsPage: Decodable, Sendable {
    @OrEmpty var rules: [RuleView]
    @OrEmpty var builtin: [ToolView]
    let builtinBulk: BulkPair
    @OrEmpty var servers: [ServerView]
    @OrEmpty var serverTools: [ToolView]
    let focus: ServerView?
}

// MARK: MCP

struct McpServer: Decodable, Sendable, Hashable {
    let name: String
    let scope: String
    let type: String
    let url: String
    let command: String
    @OrEmpty var args: [String]
    @OrEmpty var envKeys: [String]
    @OrEmpty var headerKeys: [String]
    let bearer: Bool
    let authHeader: Bool
    let oauth: Bool
    let shadowedBy: String

    var key: String { scope + "/" + name }
    var target: String { type == "stdio" ? ([command] + args).joined(separator: " ") : url }

    static func == (lhs: McpServer, rhs: McpServer) -> Bool { lhs.key == rhs.key && lhs.target == rhs.target }
    func hash(into hasher: inout Hasher) { hasher.combine(key) }
}

struct McpState: Decodable, Sendable, Hashable {
    let state: String
    let issue: String
}

struct McpConnector: Decodable, Sendable, Hashable {
    let name: String
    let state: McpState
}

struct McpStatus: Decodable, Sendable {
    let servers: [String: McpState]?
    @OrEmpty var connectors: [McpConnector]
}

struct McpTool: Decodable, Sendable, Hashable {
    let name: String
    let description: String
}

/// 一个 [McpServer]、[McpTool] 或 [Subagent]：null 是一个都没有。
struct ListOf<Element: Decodable & Sendable>: Decodable, Sendable {
    let items: [Element]

    init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        items = container.decodeNil() ? [] : try container.decode([Element].self)
    }
}
