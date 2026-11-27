import SwiftUI

/// 用量弹框（同手机版 UsageControls.kt、桌面版 usageDetails）：订阅额度（在用中转时没有这一节）、上下文（按程度变色和说明、
/// 自动压缩点）、本对话累计、各轮明细，最下面是压缩上下文。打开时让核心看一眼额度够不够新。
struct UsageSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let composer: ComposerState
    let busy: Bool
    let connected: Bool

    /// 上下文用量按程度的颜色：快满了红、该注意了黄、平时是主色。
    static func color(_ level: String) -> Color {
        switch level {
        case "critical": .red
        case "warning": .orange
        default: .accentColor
        }
    }

    private var usage: UsageInfo { composer.usage }

    var body: some View {
        NavigationStack {
            List {
                if let quota = composer.quota {
                    QuotaSection(quota: quota) { model.perform("composer.quota", ["force": true]) }
                }
                contextSection
                totalsSection
                if !usage.turns.isEmpty {
                    turnsSection
                }
                Section {
                    Button("压缩上下文", systemImage: "arrow.down.right.and.arrow.up.left") {
                        model.perform("composer.compact")
                        dismiss()
                    }
                    .disabled(!connected || busy)
                }
            }
            .navigationTitle("用量")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear { model.perform("composer.quota", ["force": false]) }
    }

    private var contextSection: some View {
        Section("上下文") {
            LabeledContent("上下文", value: usage.context.isEmpty ? "—" : usage.context)
            if let fraction = usage.fraction {
                ProgressView(value: min(max(fraction, 0), 1))
                    .tint(Self.color(usage.level))
            }
            ForEach(notes, id: \.self) { note in
                Text(note).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    /// 上下文一节的说明（同桌面版 contextNotes）：现在怎样、数是估的还是实测的，自动压缩点，压缩过几次，「模型 · 思考强度」。
    private var notes: [String] {
        let critical = usage.level == "critical"
        var notes: [String] = []
        if !usage.contextKnown {
            notes.append(String(localized: "发第一条消息后才知道上下文用了多少。"))
        } else if usage.autoCompactOff {
            notes.append(
                critical
                    ? String(localized: "上下文快满了，自动压缩关着，可以手动压缩或开新对话。")
                    : String(localized: "自动压缩关着，满了以后要手动压缩。"))
        } else if critical {
            notes.append(String(localized: "上下文快满了，很快会自动压缩。"))
        } else if usage.contextEstimated {
            notes.append(String(localized: "这是 Claude Code 的估算，下一轮后换成实测的。"))
        } else {
            notes.append(String(localized: "这是上一轮实测的。"))
        }
        if !usage.compactAt.isEmpty {
            notes.append(
                usage.compactPercent.map { String(localized: "到 \(usage.compactAt)（窗口的 \($0)%）时自动压缩。") }
                    ?? String(localized: "到 \(usage.compactAt) 时自动压缩。"))
        }
        if usage.compactions > 0 {
            notes.append(String(localized: "这个对话压缩过 \(usage.compactions) 次。"))
        }
        var meta: [String] = []
        if !usage.model.isEmpty { meta.append(usage.model) }
        if !usage.appliedEffort.isEmpty {
            meta.append(String(localized: "思考 \(EffortMenu.name(usage.appliedEffort))"))
        }
        if !meta.isEmpty { notes.append(meta.joined(separator: " · ")) }
        return notes
    }

    private var totalsSection: some View {
        Section {
            LabeledContent("输入", value: usage.input)
            LabeledContent("缓存读取", value: usage.cacheRead)
            LabeledContent("缓存写入", value: usage.cacheWrite)
            LabeledContent("输出", value: usage.output)
            LabeledContent("费用", value: usage.cost)
        } header: {
            Text("本对话累计（\(usage.turnCount) 轮）")
        } footer: {
            if !totalsNote.isEmpty { Text(totalsNote) }
        }
    }

    /// 累计下面的说明（同桌面版 totalsNote）。
    private var totalsNote: String {
        var notes: [String] = []
        if usage.costEstimated { notes.append(String(localized: "带 ≈ 的费用是按价目表估的。")) }
        if usage.costPartial { notes.append(String(localized: "有的轮次算不出费用，没算进去。")) }
        if usage.earlier { notes.append(String(localized: "更早的记录还没载入，没算进去。")) }
        return notes.joined(separator: " ")
    }

    private func turnTitle(_ turn: UsageTurn) -> String {
        turn.duration.isEmpty
            ? String(localized: "第 \(turn.number) 轮") : String(localized: "第 \(turn.number) 轮 · \(turn.duration)")
    }

    private var turnsSection: some View {
        Section("各轮") {
            ForEach(usage.turns) { turn in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(turnTitle(turn))
                        Spacer()
                        Text(turn.cost).monospacedDigit()
                    }
                    .font(.subheadline)
                    Text("输入 \(turn.input) · 缓存读 \(turn.cacheRead) · 缓存写 \(turn.cacheWrite) · 输出 \(turn.output)")
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}
