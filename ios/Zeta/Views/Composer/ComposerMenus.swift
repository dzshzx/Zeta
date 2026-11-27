import SwiftUI

/// 左下角的「+」：图片、文件一组，常用指令一组（点了填进输入框，最后一项管理）。
struct AttachMenu: View {
    @Environment(AppModel.self) private var model
    let enabled: Bool
    let pickPhotos: () -> Void
    let pickFiles: () -> Void
    let insert: (String) -> Void
    let manage: () -> Void

    var body: some View {
        Menu {
            Section {
                Button("图片", systemImage: "photo", action: pickPhotos)
                Button("文件", systemImage: "paperclip", action: pickFiles)
            }
            .disabled(!enabled)
            Section("常用指令") {
                ForEach(model.savedPrompts().list) { prompt in
                    Button(prompt.title) { insert(prompt.text) }
                }
                Button("管理常用指令", systemImage: "star", action: manage)
            }
        } label: {
            Label("添加", systemImage: "plus")
                .labelStyle(.iconOnly)
                .frame(width: 32, height: 32)
        }
    }
}

/// 权限模式：这个对话选的，没选过时是进程此刻用的（同桌面版 modeMenu）。
struct ModeMenu: View {
    @Environment(AppModel.self) private var model
    let composer: ComposerState

    var body: some View {
        Menu {
            Picker("权限模式", selection: choice) {
                ForEach(composer.modes, id: \.value) { mode in
                    Text(mode.name).tag(mode.value)
                }
            }
        } label: {
            Label(label, systemImage: icon)
                .labelStyle(.iconOnly)
                .foregroundStyle(tint)
                .frame(width: 32, height: 32)
        }
    }

    private var label: String {
        composer.modes.first { $0.value == composer.mode }?.name ?? String(localized: "权限模式")
    }

    private var choice: Binding<String> {
        Binding(get: { composer.mode }, set: { model.choose("composer.mode", $0) })
    }

    /// 同桌面版 modeIcon：逐项确认和没指定时灰色，计划青色，自动接受编辑紫色，自动判断蓝色，完全放行红色。
    private var icon: String {
        switch composer.mode {
        case "plan": "map"
        case "acceptEdits": "pencil.circle"
        case "auto": "a.circle"
        case "bypassPermissions": "exclamationmark.shield"
        default: "checkmark.shield"
        }
    }

    private var tint: Color {
        switch composer.mode {
        case "plan": .cyan
        case "acceptEdits": .purple
        case "auto": .blue
        case "bypassPermissions": .red
        default: .secondary
        }
    }
}

/// 模型：Claude Code 时最上面是「默认」，各系列最新的直接列，旧版本收进「更多模型」；中转只列它报的（同桌面版 modelMenu）。
struct ModelMenuButton: View {
    @Environment(AppModel.self) private var model
    let menu: ModelMenu

    var body: some View {
        Menu {
            if menu.harness {
                choice(String(localized: "默认"), value: nil, chosen: menu.chosen.isEmpty)
            }
            ForEach(menu.latest, id: \.value) { option in
                choice(option.name, value: option.value, chosen: option.value == menu.chosen)
            }
            if !menu.older.isEmpty {
                Menu("更多模型") {
                    ForEach(menu.older, id: \.value) { option in
                        choice(option.name, value: option.value, chosen: option.value == menu.chosen)
                    }
                }
            }
        } label: {
            Text(label).font(.footnote).lineLimit(1)
        }
        .disabled(!menu.available || (!menu.harness && menu.latest.isEmpty))
    }

    private var label: String {
        if !menu.chosenName.isEmpty { return menu.chosenName }
        if !menu.inUse.isEmpty { return String(localized: "默认（\(menu.inUse)）") }
        return String(localized: "默认")
    }

    private func choice(_ name: String, value: String?, chosen: Bool) -> some View {
        Button {
            model.choose("composer.model", value)
        } label: {
            if chosen {
                Label(name, systemImage: "checkmark")
            } else {
                Text(name)
            }
        }
    }
}

/// 思考强度：「默认」（实际生效的写在括号里）和几档（同桌面版 effortMenu）。
struct EffortMenu: View {
    @Environment(AppModel.self) private var model
    let composer: ComposerState

    static let levels = ["low", "medium", "high", "xhigh", "max"]

    static func name(_ value: String) -> String {
        switch value {
        case "low": String(localized: "低")
        case "medium": String(localized: "中")
        case "high": String(localized: "高")
        case "xhigh": String(localized: "超高")
        case "max": String(localized: "最高")
        default: value
        }
    }

    var body: some View {
        Menu {
            Picker("思考强度", selection: choice) {
                Text("默认").tag("")
                ForEach(Self.levels, id: \.self) { level in
                    Text(Self.name(level)).tag(level)
                }
            }
        } label: {
            Text(label).font(.footnote).lineLimit(1)
        }
    }

    private var label: String {
        if !composer.effort.isEmpty { return String(localized: "思考 \(Self.name(composer.effort))") }
        if !composer.appliedEffort.isEmpty { return String(localized: "思考 默认（\(Self.name(composer.appliedEffort))）") }
        return String(localized: "思考 默认")
    }

    private var choice: Binding<String> {
        Binding(
            get: { composer.effort },
            set: { model.choose("composer.effort", $0.isEmpty ? nil : $0) })
    }
}

/// 上下文用量的圆环：按程度变色，点开是用量弹框。
struct UsageRing: View {
    let usage: UsageInfo
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            Gauge(value: min(max(usage.fraction ?? 0, 0), 1)) {
                Text("上下文")
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .tint(UsageSheet.color(usage.level))
            .scaleEffect(0.45)
            .frame(width: 28, height: 28)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var label: String {
        guard let fraction = usage.fraction else { return String(localized: "上下文") }
        return String(localized: "上下文 \(Int(fraction * 100))%")
    }
}
