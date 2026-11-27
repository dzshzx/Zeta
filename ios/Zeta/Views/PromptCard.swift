import SwiftUI

/// 等你回应的请求，在输入栏上面：工具确认、计划审批、Claude 的提问。回应的格式是 helper/prompt 的 Reply。
/// MCP 的表单和网址（elicitation）这里不做，提示到别的客户端上处理。
struct PromptCard: View {
    @Environment(AppModel.self) private var model
    let prompt: Prompt

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let agent = prompt.agentLabel, !agent.isEmpty {
                Text(agent).font(.caption).foregroundStyle(.secondary)
            }
            if !prompt.isPermission {
                Text("MCP 服务器在等你填写或打开网页").font(.headline)
                Text(prompt.message ?? "").font(.footnote).foregroundStyle(.secondary)
                Text("这一种请在桌面版或手机版上处理。").font(.footnote).foregroundStyle(.secondary)
            } else if prompt.isPlan == true {
                PlanPrompt(prompt: prompt, answer: answer)
            } else if let questions = prompt.questions, !questions.isEmpty {
                QuestionsPrompt(questions: questions, answer: answer)
            } else {
                ToolPrompt(prompt: prompt, answer: answer)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassEffect(.regular, in: .rect(cornerRadius: 24))
    }

    private func answer(_ reply: [String: any Sendable]) {
        model.perform("answer", ["request_id": prompt.requestID, "reply": reply])
    }
}

private struct ToolPrompt: View {
    let prompt: Prompt
    let answer: ([String: any Sendable]) -> Void

    var body: some View {
        Text("允许 \(prompt.toolName ?? "")？").font(.headline)
        if let summary = prompt.input?.summary {
            Text(summary)
                .font(.footnote.monospaced())
                .lineLimit(6)
                .textSelection(.enabled)
        }
        if let reason = prompt.reason, !reason.isEmpty {
            Text(reason).font(.footnote).foregroundStyle(.secondary)
        }
        HStack {
            Button("拒绝", role: .cancel) { answer(["kind": "deny"]) }
                .buttonStyle(.glass)
            Spacer()
            if !(prompt.suggestions ?? []).isEmpty {
                Button("总是允许") { answer(["kind": "allow_always"]) }
                    .buttonStyle(.glass)
            }
            Button("允许") { answer(["kind": "allow"]) }
                .buttonStyle(.glassProminent)
        }
    }
}

private struct PlanPrompt: View {
    let prompt: Prompt
    let answer: ([String: any Sendable]) -> Void
    @State private var feedback = ""

    var body: some View {
        Text("批准这个计划？").font(.headline)
        ScrollView {
            MarkdownText(text: prompt.input?["plan"]?.string ?? "")
                .font(.footnote)
        }
        .frame(maxHeight: 240)
        TextField("不批准时，告诉 Claude 要怎么改", text: $feedback, axis: .vertical)
            .lineLimit(1...4)
            .textFieldStyle(.roundedBorder)
        HStack {
            Button("不批准") { answer(["kind": "deny", "feedback": feedback]) }
                .buttonStyle(.glass)
                .disabled(feedback.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Spacer()
            Button("批准并自动接受改动") { answer(["kind": "approve_plan", "mode": "acceptEdits"]) }
                .buttonStyle(.glass)
            Button("批准") { answer(["kind": "approve_plan", "mode": "default"]) }
                .buttonStyle(.glassProminent)
        }
    }
}

private struct QuestionsPrompt: View {
    let questions: [Question]
    let answer: ([String: any Sendable]) -> Void
    @State private var chosen: [String: Set<String>] = [:]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(questions, id: \.question) { question in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(question.header).font(.caption).foregroundStyle(.secondary)
                        Text(question.question).font(.headline)
                        ForEach(question.options, id: \.label) { option in
                            optionRow(question, option)
                        }
                    }
                }
            }
        }
        .frame(maxHeight: 320)
        HStack {
            Button("先聊聊") { answer(["kind": "clarify", "answers": answers]) }
                .buttonStyle(.glass)
            Spacer()
            Button("提交") { answer(["kind": "answer", "answers": answers]) }
                .buttonStyle(.glassProminent)
                .disabled(questions.contains { (chosen[$0.question] ?? []).isEmpty })
        }
    }

    private func optionRow(_ question: Question, _ option: Question.Option) -> some View {
        let picked = chosen[question.question, default: []].contains(option.label)
        return Button {
            var set = chosen[question.question, default: []]
            if question.multiSelect == true {
                if picked { set.remove(option.label) } else { set.insert(option.label) }
            } else {
                set = [option.label]
            }
            chosen[question.question] = set
        } label: {
            HStack(alignment: .top) {
                Image(systemName: picked ? "checkmark.circle.fill" : "circle")
                VStack(alignment: .leading, spacing: 2) {
                    Text(option.label)
                    if !option.description.isEmpty {
                        Text(option.description).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    /// 问题 → 回答，多选用 ", " 连起来（同 TUI）；选项按问题里的先后。
    private var answers: [String: String] {
        var out: [String: String] = [:]
        for question in questions {
            let set = chosen[question.question, default: []]
            let labels = question.options.map(\.label).filter { set.contains($0) }
            if !labels.isEmpty { out[question.question] = labels.joined(separator: ", ") }
        }
        return out
    }
}
