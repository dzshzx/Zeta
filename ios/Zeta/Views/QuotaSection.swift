import SwiftUI

/// 订阅额度一节（设置的账号与额度页、输入栏的用量弹框共用）：各窗口用了多少、什么时候重置，额外用量，说明和刷新。
/// 在用中转、还不知道时核心不给额度（accounts.Quota.Shown），调用方就不放这一节。
struct QuotaSection: View {
    let quota: QuotaView
    /// 点「刷新」时做的事：向 Claude Code 要一次新的数。
    let refresh: () -> Void

    var body: some View {
        Section {
            if quota.blocked {
                let until = quota.blockedUntil
                Text(until.isEmpty ? String(localized: "额度已用尽") : String(localized: "额度已用尽，\(until)恢复"))
                    .foregroundStyle(.red)
            }
            if !quota.unavailable {
                ForEach(quota.windows) { window in
                    VStack(alignment: .leading, spacing: 4) {
                        LabeledContent(window.label, value: "\(window.percent)%")
                        ProgressView(value: min(max(window.used, 0), 1)).tint(Self.color(window.severity))
                        if !window.reset.isEmpty {
                            Text(window.reset).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            if let extra = quota.extra {
                VStack(alignment: .leading) {
                    LabeledContent("额外用量", value: extra.amount.isEmpty ? String(localized: "已开启") : extra.amount)
                    if let used = extra.used { ProgressView(value: min(max(used, 0), 1)) }
                }
            }
            Button(quota.refreshing ? String(localized: "正在刷新") : String(localized: "刷新"),
                   systemImage: "arrow.clockwise", action: refresh)
                .disabled(quota.refreshing)
        } header: {
            HStack {
                Text("订阅额度")
                Spacer()
                Text(quota.plan)
            }
        } footer: {
            Text(quota.footnote)
        }
    }

    /// 程度的颜色：快满了红、该注意了橙。
    static func color(_ severity: String) -> Color {
        switch severity {
        case "critical": .red
        case "warning": .orange
        default: .accentColor
        }
    }
}
