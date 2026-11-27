import Observation
import SwiftUI

/// 一台设备的 Claude Code 配置页共用的状态：新对话默认、模型清单、本地的几项、闲置超时、Claude Code 版本（同桌面版 Page）。
@MainActor @Observable
final class ClaudeConfigStore {
    let calls: ConfigCalls

    var defaults: ConfigDefaults?
    var defaultsProblem = ""
    var models = ModelChoices.unknown
    var modelsPending = true
    var local: ClaudeLocal?
    var idle: Int?
    var claudeLoaded = false
    var claudeVersion = ""
    var claudeNote = ""
    var updating = false
    /// 正在改新对话默认。
    var working = false
    var failure: ConfigFailure?

    init(model: AppModel, backend: String) {
        calls = ConfigCalls(model: model, backend: backend)
    }

    /// 默认工作目录：记忆、MCP、权限页按它找；还没读到时是空串（那边的家目录）。
    var cwd: String { local?.cwd ?? "" }

    func load() async {
        async let defaults: Void = refreshDefaults()
        async let local: Void = loadLocal()
        async let idle: Void = loadIdle()
        async let claude: Void = loadClaude()
        async let models: Void = loadModels()
        _ = await (defaults, local, idle, claude, models)
    }

    func refreshDefaults() async {
        do {
            defaults = try await calls.get("config.defaults.read", as: ConfigDefaults.self)
            defaultsProblem = ""
        } catch {
            defaultsProblem = error.localizedDescription
        }
    }

    private func loadLocal() async {
        do {
            local = try await calls.hub("claudecfg.local", as: ClaudeLocal.self)
        } catch {
            failure = ConfigFailure(String(localized: "读取失败"), error)
        }
    }

    private func loadIdle() async {
        idle = try? await calls.get("system.idle", as: IdleReply.self).minutes
    }

    private func loadModels() async {
        modelsPending = true
        models = (try? await calls.hub("claudecfg.models", as: ModelChoices.self)) ?? .unknown
        modelsPending = false
    }

    private func loadClaude() async {
        do {
            claudeVersion = try await calls.get("system.claude", as: ClaudeVersion.self).version ?? ""
            claudeLoaded = true
            claudeNote = ""
        } catch {
            claudeNote = String(localized: "读不到版本：\(error.localizedDescription)")
        }
    }

    /// 改一项新对话默认，写成了换上新的。
    func setDefault(_ method: String, _ params: [String: any Sendable]) {
        guard !working, defaults != nil else { return }
        working = true
        Task {
            defer { working = false }
            do {
                defaults = try await calls.get(method, params, as: ConfigDefaults.self)
            } catch {
                failure = ConfigFailure(String(localized: "没有改成"), error)
            }
        }
    }

    /// 改本地存的一项（autoCompact、compactPercent、autoTitle）。
    func setLocal(_ key: String, _ value: any Sendable) {
        Task {
            do {
                local = try await calls.hub("claudecfg.setlocal", [key: value], as: ClaudeLocal.self)
            } catch {
                failure = ConfigFailure(String(localized: "没存上"), error)
            }
        }
    }

    func setIdle(_ minutes: Int) {
        Task {
            do {
                idle = try await calls.get("system.set_idle", ["minutes": minutes], as: IdleReply.self).minutes
            } catch {
                failure = ConfigFailure(String(localized: "没有改成"), error)
            }
        }
    }

    /// 在那台主机上跑 claude update，最多几分钟。
    func updateClaude() {
        updating = true
        claudeNote = ""
        Task {
            defer { updating = false }
            do {
                let update = try await calls.slow("system.claude_update", as: ClaudeUpdate.self)
                claudeVersion = update.version
                claudeNote = Self.note(update)
            } catch {
                claudeNote = String(localized: "没更新成：\(error.localizedDescription)")
            }
        }
    }

    /// 更新完的一句话：成了说新旧版本，没成说 claude update 输出的最后一行。
    static func note(_ update: ClaudeUpdate) -> String {
        if !update.succeeded {
            let last = update.output.split(separator: "\n").last { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            let reason = last.map { String($0) } ?? String(localized: "claude update 出错了")
            return String(localized: "没更新成：\(reason)")
        }
        if !update.version.isEmpty, update.version != update.before {
            let before = update.before.isEmpty ? String(localized: "未安装") : update.before
            return String(localized: "已从 \(before) 更新到 \(update.version)")
        }
        return String(localized: "已是最新")
    }

    /// 给人看的模型名：清单里有就用清单的，没有按 ID 写。
    func modelName(_ id: String) -> String {
        if models.source == "relay", let found = models.models.first(where: { $0.id == id }) { return found.label }
        return ConfigLabels.readableModel(id)
    }
}

extension ModelChoices {
    static let unknown = ModelChoices(source: "unknown")

    init(source: String) {
        self.source = source
    }
}
