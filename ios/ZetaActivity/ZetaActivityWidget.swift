import ActivityKit
import SwiftUI
import WidgetKit

/// 扩展 ZetaActivity：画灵动岛和锁屏上的实时活动（定义在 Shared/ZetaActivity.swift）。要显示的字都由 App 按界面语言出好交过来，
/// 这里只摆图标、标题、那一句和走秒。
@main
struct ZetaActivityBundle: WidgetBundle {
    var body: some Widget {
        ZetaActivityWidget()
    }
}

struct ZetaActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ZetaActivityAttributes.self) { context in
            LockScreenView(attributes: context.attributes, state: context.state)
                .padding()
                .activityBackgroundTint(nil)
                .widgetURL(context.attributes.openURL(scheme: Self.scheme))
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    PhaseIcon(phase: state.phase).font(.title2)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Elapsed(state: state).font(.callout.monospacedDigit())
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.attributes.title).font(.headline).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(state.line).font(.subheadline).lineLimit(2)
                        if !context.attributes.device.isEmpty {
                            Text(context.attributes.device).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } compactLeading: {
                PhaseIcon(phase: state.phase)
            } compactTrailing: {
                Elapsed(state: state).font(.caption2.monospacedDigit()).frame(maxWidth: 44)
            } minimal: {
                PhaseIcon(phase: state.phase)
            }
            .widgetURL(context.attributes.openURL(scheme: Self.scheme))
            .keylineTint(PhaseIcon.tint(state.phase))
        }
    }

    /// App 的网址 scheme（就是 App 的包名，见 project.yml 的 ZetaURLScheme）。
    static let scheme = Bundle.main.object(forInfoDictionaryKey: "ZetaURLScheme") as? String ?? "zeta"
}

/// 锁屏上那一条：图标、标题和设备、那一句，右边走秒。
private struct LockScreenView: View {
    let attributes: ZetaActivityAttributes
    let state: ZetaActivityAttributes.ContentState

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            PhaseIcon(phase: state.phase).font(.title2)
            VStack(alignment: .leading, spacing: 2) {
                Text(attributes.title).font(.headline).lineLimit(1)
                Text(state.line).font(.subheadline).lineLimit(2)
                if !attributes.device.isEmpty {
                    Text(attributes.device).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Elapsed(state: state).font(.callout.monospacedDigit())
        }
    }
}

/// 在回答：转着的圈；等你回应：橙色的感叹号；答完了：绿色的勾。
private struct PhaseIcon: View {
    let phase: ZetaActivityPhase

    static func tint(_ phase: ZetaActivityPhase) -> Color {
        switch phase {
        case .running: .accentColor
        case .waiting: .orange
        case .finished: .green
        }
    }

    var body: some View {
        Group {
            switch phase {
            case .running: Image(systemName: "sparkle").symbolEffect(.pulse)
            case .waiting: Image(systemName: "exclamationmark.bubble.fill")
            case .finished: Image(systemName: "checkmark.circle.fill")
            }
        }
        .foregroundStyle(Self.tint(phase))
    }
}

/// 在回答时从这一轮开始走秒；等你和答完了不走。
private struct Elapsed: View {
    let state: ZetaActivityAttributes.ContentState

    var body: some View {
        if state.phase == .running {
            Text(timerInterval: state.started...Date.distantFuture, countsDown: false)
                .multilineTextAlignment(.trailing)
        }
    }
}
