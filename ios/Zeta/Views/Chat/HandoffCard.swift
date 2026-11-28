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
        return String(localized: "等待对方空闲")
    }

    private func summary(_ occupant: Occupancy?) -> String {
        if let problem = chat.handoffProblem, !problem.isEmpty { return problem }
        guard let occupant else { return String(localized: "正在检查对方状态…") }
        let zeta = occupant.app == "zeta" || occupant.app == "zeta-dev"
        switch occupant.status {
        case "idle":
            return zeta
                ? String(localized: "当前处于空闲状态。接管后另一端的 Zeta 将释放此会话，并由本机继续。")
                : String(localized: "当前处于空闲状态。接管后另一端将如同执行 /exit 般退出，并由本机继续。")
        case "waiting":
            if let what = occupant.waitingFor, !what.isEmpty {
                return String(localized: "当前正在等待确认：\(what)。接管后该确认请求将失效。")
            }
            return String(localized: "当前正在等待确认。接管后该确认请求将失效。")
        case "unknown":
            return String(localized: "无法判断当前是否空闲。")
        case "shell":
            return String(localized: "当前正在执行 shell 命令，完成后方可接管。")
        default:
            return String(localized: "当前正在回复，请等待本轮结束后再接管。")
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
        case "agy": what = "Antigravity CLI"
        case "claude-vscode": what = "VS Code"
        case "claude-desktop": what = String(localized: "Claude 桌面版")
        default: return String(localized: "会话正在别处运行")
        }
        if occupant.local == true {
            return String(localized: "会话正在设备本机的\(what)中运行")
        }
        if let device = occupant.device, !device.isEmpty {
            return String(localized: "会话正在 \(device) 的\(what)中运行")
        }
        return String(localized: "会话正在\(what)中运行")
    }
}
