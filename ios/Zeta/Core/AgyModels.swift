import Foundation

struct AgyOrchestrator: Codable, Sendable {
    var planner: String
    var executor: String
}

struct AgySandboxProxy: Decodable, Sendable {
    let http: String
    let https: String
    let noProxy: String
    let username: String
    let hasPassword: Bool
}

struct AgyGCP: Codable, Sendable {
    var project: String
    var location: String
}

struct AgySettings: Decodable, Sendable {
    let toolPermission: String
    let enableTerminalSandbox: Bool
    let allowNonWorkspaceAccess: Bool
    let artifactReviewPolicy: String
    let useG1Credits: Bool
    let queuedMessages: String
    let orchestrator: AgyOrchestrator
    let sandboxProxy: AgySandboxProxy
    let trustedWorkspaces: [String]
    let customModelsConfig: String
    let gcp: AgyGCP
}

struct AgyPlugin: Decodable, Sendable, Identifiable {
    let name: String
    let version: String?
    let description: String?
    let enabled: Bool
    let source: String?

    var id: String { name }
}

struct AgyHookEntry: Decodable, Sendable, Identifiable {
    let name: String
    let scope: String
    let plugin: String?
    let readOnly: Bool?
    let enabled: Bool
    let event: String
    let matcher: String?
    let command: String
    let timeout: Int?
    let events: [String]?
    let commandCount: Int

    var id: String { "\(scope):\(plugin ?? ""):\(name)" }
    var isReadOnly: Bool { readOnly == true }
}

struct AgyHooksView: Decodable, Sendable {
    let userPath: String
    let projectPath: String?
    let hooks: [AgyHookEntry]
}

struct AgySidecar: Decodable, Sendable, Identifiable {
    let id: String
    let displayName: String
    let description: String?
    let command: String?
    let builtin: String?
    let restartPolicy: String?
    let plugin: String?
    let enabled: Bool
    let projectId: String?
}

struct ArtifactInfo: Decodable, Sendable, Identifiable, Hashable {
    let name: String
    let summary: String?
    let userFacing: Bool?
    let updatedAt: String?
    let size: Int64

    var id: String { name }
}

struct ArtifactContent: Decodable, Sendable {
    let name: String
    let summary: String?
    let content: String
    let truncated: Bool?
    let size: Int64
}

struct SwarmMember: Decodable, Sendable, Identifiable {
    let name: String
    let model: String?
    let role: String?
    let status: String
    let conversationId: String?

    var id: String { name }
}

struct SwarmState: Decodable, Sendable {
    let title: String?
    let members: [SwarmMember]
}
