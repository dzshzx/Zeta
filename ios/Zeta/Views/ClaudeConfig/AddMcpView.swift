import SwiftUI

/// 添加 MCP 服务器（同桌面版 addServerDialog）：HTTP、SSE 填地址和可选的 Bearer Token，命令行的填命令、参数和环境变量。
/// 加到用户配置，所有项目可用；表单先查一遍，核心还会再查。
struct AddMcpView: View {
    let calls: ConfigCalls
    let added: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var type = "http"
    @State private var name = ""
    @State private var url = ""
    @State private var token = ""
    @State private var command = ""
    @State private var args = ""
    @State private var env = ""
    @State private var problem = ""
    @State private var working = false

    var body: some View {
        NavigationStack {
            Form {
                Picker("类型", selection: $type) {
                    Text(verbatim: "HTTP").tag("http")
                    Text(verbatim: "SSE").tag("sse")
                    Text("命令").tag("stdio")
                }
                Section {
                    TextField("名称，比如 github", text: $name)
                    if type == "stdio" {
                        TextField("命令，比如 npx", text: $command)
                        TextField("参数（可选，空格分开）", text: $args)
                        TextField("环境变量（可选，每行一个 KEY=VALUE）", text: $env, axis: .vertical)
                            .lineLimit(2...6)
                    } else {
                        TextField("服务器地址 https://", text: $url).keyboardType(.URL)
                        SecureField("Bearer Token（可选）", text: $token)
                    }
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .disabled(working)
                if !problem.isEmpty {
                    Section {
                        Label(problem, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("添加服务器")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }.disabled(working)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if working { ProgressView() } else { Button("添加") { add() } }
                }
            }
        }
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }

    private var envLines: [String] {
        env.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    /// 表单还差什么；没问题时为空。
    private var missing: String {
        if trimmedName.wholeMatch(of: /[A-Za-z0-9_-]{1,64}/) == nil {
            return String(localized: "名称只能用字母、数字、下划线和连字符")
        }
        if type != "stdio", url.trimmingCharacters(in: .whitespaces).wholeMatch(of: /https?:\/\/\S+/) == nil {
            return String(localized: "地址要以 http:// 或 https:// 开头")
        }
        if type == "stdio", command.trimmingCharacters(in: .whitespaces).isEmpty {
            return String(localized: "请输入要运行的命令")
        }
        if envLines.contains(where: { $0.wholeMatch(of: /[A-Za-z_][A-Za-z0-9_]*=.*/) == nil }) {
            return String(localized: "环境变量每行一个，写成 KEY=VALUE")
        }
        return ""
    }

    private func add() {
        problem = missing
        guard problem.isEmpty else { return }
        let method: String
        let params: [String: any Sendable]
        if type == "stdio" {
            var vars: [String: String] = [:]
            for line in envLines {
                let parts = line.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                vars[String(parts[0])] = parts.count > 1 ? String(parts[1]) : ""
            }
            method = "config.mcp.addStdio"
            params = [
                "name": trimmedName, "command": command.trimmingCharacters(in: .whitespaces),
                "args": Self.splitArgs(args), "env": vars
            ]
        } else {
            method = "config.mcp.addHttp"
            params = [
                "name": trimmedName, "type": type, "url": url.trimmingCharacters(in: .whitespaces),
                "token": token.trimmingCharacters(in: .whitespaces)
            ]
        }
        working = true
        Task {
            defer { working = false }
            do {
                _ = try await calls.slow(method, params, as: Ignored.self)
                added()
                dismiss()
            } catch {
                problem = error.localizedDescription
            }
        }
    }

    /// 把一行参数拆开：空白分隔，单双引号里的空白不拆（同桌面版 splitArgs）。
    static func splitArgs(_ line: String) -> [String] {
        var out: [String] = []
        var current = ""
        var quote: Character?
        var has = false
        for char in line {
            if let open = quote {
                if char == open { quote = nil } else { current.append(char) }
            } else if char == "\"" || char == "'" {
                quote = char
                has = true
            } else if char == " " || char == "\t" || char == "\n" {
                if has || !current.isEmpty {
                    out.append(current)
                    current = ""
                    has = false
                }
            } else {
                current.append(char)
            }
        }
        if has || !current.isEmpty { out.append(current) }
        return out
    }
}
