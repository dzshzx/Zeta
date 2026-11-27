import SwiftUI

// 占位：设备页的「Claude Code 配置」一行链接的 ClaudeConfigView(backend:) 由 claudecfg 那一块做（ios/Zeta/Views/ClaudeConfig/）。
// 这里只为让设置这一块能单独编译。两块合并时删掉这个文件。

/// Claude Code 配置的占位页。
struct ClaudeConfigView: View {
    let backend: String

    var body: some View {
        ContentUnavailableView("Claude Code 配置", systemImage: "slider.horizontal.3")
            .navigationTitle("Claude Code 配置")
    }
}
