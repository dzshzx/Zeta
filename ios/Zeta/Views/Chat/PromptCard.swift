import SwiftUI
import UIKit

/// 等你回应的请求，在输入栏上面（同桌面版 prompts.go、手机版 PromptSheet.kt、ElicitationSheet.kt）：工具确认、计划审批、
/// Claude 的提问、MCP 服务器的表单或网址。回应的格式是 helper/prompt 的 Reply；MCP 的经核心桥的 elicit。
/// 只有明确点按钮才算回应。后台子代理发起的，上面写「来自子代理：说明」。
struct PromptCard: View {
    @Environment(AppModel.self) private var model
    let prompt: Prompt

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let agent = prompt.agentLabel, !agent.isEmpty {
                Label("来自子代理：\(agent)", systemImage: "person.2")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if !prompt.isPermission {
                ElicitPrompt(prompt: prompt)
            } else if let questions = prompt.questions, !questions.isEmpty {
                QuestionsPrompt(questions: questions, answer: answer)
            } else if prompt.isPlan == true {
                PlanPrompt(prompt: prompt, answer: answer)
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

/// 一段等宽的原文（命令、参数、预览），太长时在框里滚动。
struct DetailBlock: View {
    let text: String

    var body: some View {
        ScrollView {
            Text(text)
                .font(.footnote.monospaced())
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
        }
        .frame(maxHeight: 160)
        .fixedSize(horizontal: false, vertical: true)
        .background(.fill.tertiary, in: .rect(cornerRadius: 12))
    }
}

/// 工具确认：命令或参数；Claude Code 给了「以后不再问」的建议时可以勾上；拒绝时可以写理由让 Claude 换个做法。
private struct ToolPrompt: View {
    let prompt: Prompt
    let answer: ([String: any Sendable]) -> Void
    @State private var remember = false
    @State private var feedback = ""

    var body: some View {
        Text("允许 \(ToolLabels.title(prompt.toolName ?? ""))？").font(.headline)
        if let detail {
            DetailBlock(text: detail)
        }
        if let reason = prompt.reason, !reason.isEmpty {
            Text(reason).font(.footnote).foregroundStyle(.secondary)
        }
        if !(prompt.suggestions ?? []).isEmpty {
            Toggle(isOn: $remember) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("以后不再询问")
                    ForEach(prompt.suggestionLines ?? [], id: \.self) { line in
                        Text(line).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .font(.footnote)
        }
        TextField("拒绝时可向 Claude 说明替代方案", text: $feedback, axis: .vertical)
            .lineLimit(1...4)
            .textFieldStyle(.roundedBorder)
        HStack {
            Button("拒绝", role: .cancel) { answer(["kind": "deny", "feedback": feedback]) }
                .buttonStyle(.glass)
            Spacer()
            Button("允许") { answer(["kind": remember ? "allow_always" : "allow"]) }
                .buttonStyle(.glassProminent)
        }
    }

    /// 命令照原样，别的工具列出参数里最能说明的一项。
    private var detail: String? {
        if let command = ToolLabels.command(prompt.input) { return command }
        let summary = ToolLabels.summary(name: prompt.toolName ?? "", input: prompt.input)
        return summary.isEmpty ? nil : summary
    }
}

private struct PlanPrompt: View {
    let prompt: Prompt
    let answer: ([String: any Sendable]) -> Void
    @State private var feedback = ""

    var body: some View {
        Text("批准这个计划？").font(.headline)
        ScrollView {
            MarkdownView(text: prompt.input?["plan"]?.string ?? "")
                .font(.footnote)
        }
        .frame(maxHeight: 240)
        TextField("拒绝时可向 Claude 说明修改意见", text: $feedback, axis: .vertical)
            .lineLimit(1...4)
            .textFieldStyle(.roundedBorder)
        HStack {
            Button("不批准") { answer(["kind": "deny", "feedback": feedback]) }
                .buttonStyle(.glass)
                .disabled(feedback.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Spacer()
            Button("逐项确认") { answer(["kind": "approve_plan", "mode": "default"]) }
                .buttonStyle(.glass)
            Button("批准并自动接受改动") { answer(["kind": "approve_plan", "mode": "acceptEdits"]) }
                .buttonStyle(.glassProminent)
        }
    }
}

/// 一个问题的作答：选中的选项、自己写的（「其他」）和备注（同桌面版 questionAnswer）。
private struct QuestionAnswer {
    var chosen: Set<String> = []
    var otherOn = false
    var other = ""
    var notes = ""

    /// 交回去的回答：多选按选项的顺序用 ", " 连起来，自己写的排在最后。
    func text(_ question: Question) -> String {
        var parts = question.options.map(\.label).filter {
            chosen.contains($0) && (question.multiSelect == true || !otherOn)
        }
        let own = other.trimmingCharacters(in: .whitespacesAndNewlines)
        if otherOn, !own.isEmpty { parts.append(own) }
        return parts.joined(separator: ", ")
    }
}

/// AskUserQuestion：每题选一个或几个，也可以自己写（其他）；带预览的单选题显示选中那项的预览、能写备注；
/// 也可以先不答，和 Claude 聊聊这些问题。
private struct QuestionsPrompt: View {
    let questions: [Question]
    let answer: ([String: any Sendable]) -> Void
    @State private var answers: [String: QuestionAnswer] = [:]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(questions, id: \.question) { question in
                    QuestionView(question: question, answer: binding(question))
                }
            }
        }
        .frame(maxHeight: 360)
        HStack {
            Button("先讨论") { submit("clarify") }
                .buttonStyle(.glass)
            Spacer()
            Button("提交") { submit("answer") }
                .buttonStyle(.glassProminent)
                .disabled(questions.contains { (answers[$0.question] ?? QuestionAnswer()).text($0).isEmpty })
        }
    }

    private func binding(_ question: Question) -> Binding<QuestionAnswer> {
        Binding(
            get: { answers[question.question] ?? QuestionAnswer() },
            set: { answers[question.question] = $0 })
    }

    private func submit(_ kind: String) {
        var texts: [String: String] = [:]
        var notes: [String: String] = [:]
        for question in questions {
            let given = answers[question.question] ?? QuestionAnswer()
            let text = given.text(question)
            if !text.isEmpty { texts[question.question] = text }
            let note = given.notes.trimmingCharacters(in: .whitespacesAndNewlines)
            if !note.isEmpty { notes[question.question] = note }
        }
        answer(["kind": kind, "answers": texts, "notes": notes])
    }
}

private struct QuestionView: View {
    let question: Question
    @Binding var answer: QuestionAnswer

    private var multi: Bool { question.multiSelect == true }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(question.header).font(.caption).foregroundStyle(.secondary)
            Text(question.question).font(.headline)
            ForEach(question.options, id: \.label) { option in
                ChoiceRow(
                    picked: answer.chosen.contains(option.label) && (multi || !answer.otherOn),
                    multi: multi, label: option.label, detail: option.description
                ) { pick(option.label) }
            }
            ChoiceRow(picked: answer.otherOn, multi: multi, label: String(localized: "其他"), detail: "") {
                answer.otherOn.toggle()
                if answer.otherOn, !multi { answer.chosen = [] }
            }
            if answer.otherOn {
                TextField("自定义输入", text: $answer.other, axis: .vertical)
                    .lineLimit(1...4)
                    .textFieldStyle(.roundedBorder)
            }
            if let preview {
                DetailBlock(text: preview)
            }
            if question.takesNotes == true, !answer.chosen.isEmpty {
                TextField("备注（可选）", text: $answer.notes, axis: .vertical)
                    .lineLimit(1...3)
                    .textFieldStyle(.roundedBorder)
            }
        }
    }

    private var preview: String? {
        guard !answer.otherOn else { return nil }
        return question.options.first { answer.chosen.contains($0.label) }?.preview
    }

    private func pick(_ label: String) {
        if multi {
            if answer.chosen.contains(label) { answer.chosen.remove(label) } else { answer.chosen.insert(label) }
        } else {
            answer.chosen = [label]
            answer.otherOn = false
        }
    }
}

/// 一行选项：单选是圆圈，多选是方框。
struct ChoiceRow: View {
    let picked: Bool
    let multi: Bool
    let label: String
    let detail: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top) {
                Image(systemName: icon)
                    .foregroundStyle(picked ? Color.accentColor : Color.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                    if !detail.isEmpty {
                        Text(detail).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private var icon: String {
        switch (picked, multi) {
        case (true, true): "checkmark.square.fill"
        case (true, false): "checkmark.circle.fill"
        case (false, true): "square"
        case (false, false): "circle"
        }
    }
}
