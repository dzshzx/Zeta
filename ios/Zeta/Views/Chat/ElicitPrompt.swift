import SwiftUI
import UIKit

/// MCP 服务器的表单（逐项填，核心核对，不对的原因写在那一项下面）或网址（去网页上做，点「打开网页」算同意）。
struct ElicitPrompt: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    let prompt: Prompt
    @State private var values: [String: [String]] = [:]
    @State private var problems: [String: String] = [:]
    @State private var sending = false

    private var isURL: Bool { prompt.url?.isEmpty == false }
    private var server: String { prompt.server.flatMap { $0.isEmpty ? nil : $0 } ?? "MCP" }

    var body: some View {
        Text(isURL ? String(localized: "\(server) 请你打开网页") : String(localized: "\(server) 请你填写"))
            .font(.headline)
        if let message = prompt.message, !message.isEmpty {
            Text(message).font(.footnote).textSelection(.enabled)
        }
        if let url = prompt.url, isURL {
            DetailBlock(text: url)
            HStack {
                Button("拒绝") { send("decline") }.buttonStyle(.glass)
                Spacer()
                Button("打开网页") {
                    if let link = URL(string: url) { openURL(link) }
                    send("accept")
                }
                .buttonStyle(.glassProminent)
            }
            .disabled(sending)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(prompt.fields ?? [], id: \.key) { field in
                        FieldView(field: field, values: $values, problem: problems[field.key])
                    }
                }
            }
            .frame(maxHeight: 360)
            HStack {
                Button("取消") { send("cancel") }.buttonStyle(.glass)
                Button("拒绝") { send("decline") }.buttonStyle(.glass)
                Spacer()
                Button("提交") { send("accept") }
                    .buttonStyle(.glassProminent)
            }
            .disabled(sending)
        }
    }

    private func send(_ action: String) {
        var params: [String: any Sendable] = ["request_id": prompt.requestID, "action": action]
        if action == "accept", !isURL {
            var filled: [String: [String]] = [:]
            for field in prompt.fields ?? [] {
                filled[field.key] = values[field.key] ?? field.default ?? []
            }
            params["values"] = filled
        }
        sending = true
        let request = params
        Task {
            defer { sending = false }
            do {
                problems = try await model.request("elicit", request, as: Elicited.self).problems ?? [:]
            } catch {
                model.alert = error.localizedDescription
            }
        }
    }
}

/// 表单的一项：开关是开关行，单选、多选是选项行，别的是输入框；核心说不对时原因写在下面。
private struct FieldView: View {
    let field: Field
    @Binding var values: [String: [String]]
    let problem: String?

    private var current: [String] { values[field.key] ?? field.default ?? [] }
    private var title: String { field.title + (field.required == true ? " *" : "") }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            switch field.kind {
            case "toggle":
                Toggle(title, isOn: Binding(
                    get: { current.first == "true" },
                    set: { values[field.key] = [$0 ? "true" : "false"] }))
            case "single", "multi":
                Text(title).font(.subheadline)
                ForEach(field.choices ?? [], id: \.value) { choice in
                    ChoiceRow(
                        picked: current.contains(choice.value), multi: field.kind == "multi",
                        label: choice.label, detail: ""
                    ) { pick(choice.value) }
                }
            default:
                TextField(title, text: Binding(
                    get: { current.first ?? "" },
                    set: { values[field.key] = [$0] }))
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(keyboard)
            }
            if let description = field.description, !description.isEmpty {
                Text(description).font(.caption).foregroundStyle(.secondary)
            }
            if let problem {
                Text(problem).font(.caption).foregroundStyle(.red)
            }
        }
    }

    private var keyboard: UIKeyboardType {
        switch field.kind {
        case "integer": .numberPad
        case "number": .decimalPad
        default: field.format == "email" ? .emailAddress : field.format == "uri" ? .URL : .default
        }
    }

    private func pick(_ value: String) {
        if field.kind == "multi" {
            values[field.key] = current.contains(value) ? current.filter { $0 != value } : current + [value]
        } else {
            values[field.key] = [value]
        }
    }
}
