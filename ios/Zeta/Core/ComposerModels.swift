import Foundation

// 输入栏的 JSON（hub/mobile/composer.go 的 Composer、SlashView、Prompts）和输入栏调核心的几个方法。

/// state 里 chat.composer：输入栏要画的。
struct ComposerState: Decodable, Sendable {
    /// 核心改过的输入框里的字；rev 变了界面才换上（见 composer.go 开头）。
    let text: String
    let rev: Int
    let ready: Bool
    let uploading: Bool
    let attachments: [Attachment]
    let problems: [String]
    let tooLarge: Bool
    let process: Bool
    let model: ModelMenu
    let effort: String
    let appliedEffort: String
    let mode: String
    let modes: [Choice]
    let queued: [QueuedMessage]
    let steering: Bool
    let usage: UsageInfo
    let quota: QuotaView?

    enum CodingKeys: String, CodingKey {
        case text, rev, ready, uploading, attachments, problems, process, model, effort, mode, modes, queued, steering
        case usage, quota
        case tooLarge = "too_large"
        case appliedEffort = "applied_effort"
    }
}

struct Choice: Decodable, Sendable, Hashable {
    let value: String
    let name: String
}

struct Attachment: Decodable, Sendable, Identifiable {
    let id: Int
    let name: String
    let picture: Bool
    let thumb: String
    let uploading: Bool
    let percent: Int
    let failed: String
}

struct ModelMenu: Decodable, Sendable {
    let available: Bool
    let harness: Bool
    let chosen: String
    let chosenName: String
    let inUse: String
    let latest: [Choice]
    let older: [Choice]

    enum CodingKeys: String, CodingKey {
        case available, harness, chosen, latest, older
        case chosenName = "chosen_name"
        case inUse = "in_use"
    }
}

struct QueuedMessage: Decodable, Sendable, Identifiable {
    let uuid: String
    let preview: String
    let images: Int
    let extra: Int

    var id: String { uuid }
}

struct UsageInfo: Decodable, Sendable {
    let fraction: Double?
    let level: String
    let context: String
    let contextKnown: Bool
    let contextEstimated: Bool
    let autoCompactOff: Bool
    let compactAt: String
    let compactPercent: Int?
    let compactions: Int
    let model: String
    let appliedEffort: String
    let input: String
    let cacheRead: String
    let cacheWrite: String
    let output: String
    let cost: String
    let costEstimated: Bool
    let costPartial: Bool
    let earlier: Bool
    let turnCount: Int
    let turns: [UsageTurn]

    enum CodingKeys: String, CodingKey {
        case fraction, level, context, compactions, model, input, output, cost, earlier, turns
        case contextKnown = "context_known"
        case contextEstimated = "context_estimated"
        case autoCompactOff = "auto_compact_off"
        case compactAt = "compact_at"
        case compactPercent = "compact_percent"
        case appliedEffort = "applied_effort"
        case cacheRead = "cache_read"
        case cacheWrite = "cache_write"
        case costEstimated = "cost_estimated"
        case costPartial = "cost_partial"
        case turnCount = "turn_count"
    }
}

struct UsageTurn: Decodable, Sendable, Identifiable {
    let number: Int
    let duration: String
    let cost: String
    let input: String
    let cacheRead: String
    let cacheWrite: String
    let output: String

    var id: Int { number }

    enum CodingKeys: String, CodingKey {
        case duration, cost, input, output
        case number = "n"
        case cacheRead = "cache_read"
        case cacheWrite = "cache_write"
    }
}

/// composer.draft 答的：要提示的斜杠命令。
struct SlashSuggestions: Decodable, Sendable {
    let commands: [SlashCommand]
}

struct SlashCommand: Decodable, Sendable, Hashable, Identifiable {
    let name: String
    let description: String
    let argumentHint: String

    var id: String { name }
}

/// composer.complete 答的：选了命令以后的字和光标（UTF-16）。
struct Completed: Decodable, Sendable {
    let text: String
    let caret: Int
}

struct SavedPrompts: Decodable, Sendable {
    let list: [SavedPrompt]
    let full: Bool
}

struct SavedPrompt: Decodable, Sendable, Identifiable, Hashable {
    let id: String
    let name: String
    let text: String
    let title: String
}

extension AppModel {
    /// 把输入框里的字和光标（UTF-16）交给核心存草稿，答要提示的斜杠命令。
    func typed(_ text: String, caret: Int) -> [SlashCommand] {
        (try? callCore("composer.draft", ["text": text, "caret": caret], as: SlashSuggestions.self))?.commands ?? []
    }

    /// 选了斜杠命令 name：答换好的字和光标。
    func complete(_ text: String, caret: Int, name: String) -> Completed? {
        try? callCore("composer.complete", ["text": text, "caret": caret, "name": name], as: Completed.self)
    }

    /// 发输入框里的字和传好了的附件；答发出去了没有（没东西可发、还有在传的时不发）。
    func sendComposer(_ text: String) -> Bool {
        do {
            return try callCore("composer.send", ["text": text], as: Sent.self).sent
        } catch {
            alert = error.localizedDescription
            return false
        }
    }

    /// 选模型、思考强度、权限模式（method 是 composer.model、composer.effort、composer.mode）；value 为 nil 回到默认。
    func choose(_ method: String, _ value: String?) {
        if let value {
            perform(method, ["value": value])
        } else {
            perform(method)
        }
    }

    /// 常用指令的清单。
    func savedPrompts() -> SavedPrompts {
        (try? callCore("prompts", as: SavedPrompts.self)) ?? SavedPrompts(list: [], full: false)
    }

    /// 改常用指令（method 是 prompts.add、prompts.update、prompts.remove、prompts.move），答改好的清单；没成时抛出原因。
    @discardableResult
    func editPrompts(_ method: String, _ params: [String: any Sendable]) throws -> SavedPrompts {
        try callCore(method, params, as: SavedPrompts.self)
    }

    /// 退到后台时马上把草稿写盘。
    func flushDrafts() {
        Task { _ = try? await request("composer.flush", as: [String: Bool].self) }
    }
}

private struct Sent: Decodable, Sendable {
    let sent: Bool
}
