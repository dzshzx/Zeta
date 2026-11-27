import SwiftUI

/// 会话正在别处运行时换掉输入栏的卡片（同桌面版 handoff.go、手机版 ExternalSessionNotice.kt）：照核心推来的那边的样子
/// （conv.state 的 occupant）说话，闲着时一键接过来（handover）。接过来、或那边自己退出以后核心解开 blocked，卡片收起。
struct HandoffCard: View {
    @Environment(AppModel.self) private var model
    let chat: ChatState

    var body: some View {
        let occupant = chat.occupant
        VStack(alignment: .leading, spacing: 6) {
            Text(Self.title(occupant)).font(.headline)
            Text(summary(occupant))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
            Button(button(occupant)) { model.perform("handover") }
                .buttonStyle(.glassProminent)
                .disabled(chat.handing == true || occupant?.handsOver != true)
                .padding(.top, 4)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassEffect(.regular, in: .rect(cornerRadius: 24))
    }

    private func button(_ occupant: Occupancy?) -> String {
        if chat.handing == true { return String(localized: "正在接管…") }
        if occupant == nil || occupant?.handsOver == true { return String(localized: "接管") }
        return String(localized: "等那边空闲")
    }

    private func summary(_ occupant: Occupancy?) -> String {
        if let problem = chat.handoffProblem, !problem.isEmpty { return problem }
        guard let occupant else { return String(localized: "正在查看那边的状态…") }
        let zeta = occupant.app == "zeta" || occupant.app == "zeta-dev"
        switch occupant.status {
        case "idle":
            return zeta
                ? String(localized: "那边闲着。接管后那边的 Zeta 会放开这个会话，在这里接着。")
                : String(localized: "那边闲着。接管后那边会像 /exit 那样退出，在这里接着。")
        case "waiting":
            if let what = occupant.waitingFor, !what.isEmpty {
                return String(localized: "那边在等确认：\(what)。接管后那个确认作废。")
            }
            return String(localized: "那边在等确认。接管后那个确认作废。")
        case "unknown":
            return String(localized: "看不出那边是否空闲。")
        case "shell":
            return String(localized: "那边在跑 shell 命令，跑完才能接管。")
        default:
            return String(localized: "那边正在回答，等这一轮结束才能接管。")
        }
    }

    /// 标题说会话在哪里运行（同桌面版 occupantPlace）。
    static func title(_ occupant: Occupancy?) -> String {
        guard let occupant, let app = occupant.app else { return String(localized: "会话正在别处运行") }
        let what: String
        switch app {
        case "terminal": what = String(localized: "终端")
        case "zeta": what = "Zeta"
        case "zeta-dev": what = String(localized: "Zeta 测试版")
        case "claude-vscode": what = "VS Code"
        case "claude-desktop": what = String(localized: "Claude 桌面版")
        default: return String(localized: "会话正在别处运行")
        }
        if occupant.local == true {
            return String(localized: "会话正在设备本机的\(what)里运行")
        }
        if let device = occupant.device, !device.isEmpty {
            return String(localized: "会话正在 \(device) 的\(what)里运行")
        }
        return String(localized: "会话正在\(what)里运行")
    }
}
