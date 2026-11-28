import SwiftUI

/// 关于（同桌面版的关于页）：版本、这个版本的源码在 GitHub 上的 tag 或提交、鸣谢、开源许可。
struct AboutView: View {
    @Environment(AppModel.self) private var model
    @State private var about: AboutInfo?

    /// 鸣谢：iOS 版参照和依靠的项目，只列 iOS 版实际用到的（Go 依赖照 hub/go.mod 里核心桥链接的）。
    private static let credits: [Credit] = [
        Credit(
            name: "Eta", summary: "早期参考项目，部分代码改写自此项目（GPL-3.0）",
            url: "https://github.com/Mangi-11/Eta"),
        Credit(
            name: "Claude Code", summary: "Anthropic 的 Code Agent，Zeta 支持接入",
            url: "https://github.com/anthropics/claude-code"),
        Credit(
            name: "Antigravity CLI", summary: "Google DeepMind 的 Code Agent，Zeta 支持接入",
            url: "https://antigravity.google"),
        Credit(name: "Go x/crypto", summary: "用于连接设备的 SSH 客户端库（BSD-3-Clause）", url: "https://github.com/golang/crypto"),
        Credit(name: "Go x/text", summary: "界面多语言文本支持（BSD-3-Clause）", url: "https://github.com/golang/text"),
        Credit(name: "mygo", summary: "与桌面版共用的本地化文本表与读取库（MIT）", url: "https://github.com/egoist/mygo"),
        Credit(name: "RaTeX", summary: "渲染回复中的数学公式，与 Android 版一致（MIT）", url: "https://github.com/erweixin/RaTeX"),
        Credit(name: "KaTeX", summary: "数学公式渲染字体（OFL-1.1）", url: "https://github.com/KaTeX/KaTeX"),
        Credit(
            name: "wazero", summary: "用于运行 RaTeX 的 WebAssembly 运行时（Apache-2.0）",
            url: "https://github.com/tetratelabs/wazero")
    ]

    var body: some View {
        Form {
            Section {
                LabeledContent("版本", value: versionText)
                if let about, let url = URL(string: about.url) {
                    Link(destination: url) {
                        LabeledContent("源码", value: about.ref.isEmpty ? "GitHub" : about.ref)
                    }
                }
            }
            Section("鸣谢") {
                ForEach(Self.credits, id: \.name) { credit in
                    if let url = URL(string: credit.url) {
                        Link(destination: url) {
                            VStack(alignment: .leading) {
                                Text(credit.name).foregroundStyle(.primary)
                                Text(credit.summary).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            licensesSections
        }
        .navigationTitle("关于")
        .task { about = try? await model.request("about", as: AboutInfo.self) }
    }

    private var versionText: String {
        guard let about, !about.version.isEmpty else { return String(localized: "未知") }
        return "\(about.version)（\(about.code)）"
    }

    @ViewBuilder
    private var licensesSections: some View {
        Section {
            NavigationLink("Zeta（GPL-3.0）") { LicenseTextView(title: "Zeta", file: "Zeta") }
            if let problem = about?.problem, !problem.isEmpty {
                Text(problem).foregroundStyle(.red)
            }
        } header: {
            Text("开源许可")
        } footer: {
            Text("Zeta 采用 GPL-3.0 许可协议。应用中包含以下第三方组件，分别按各自的许可证分发。")
        }
        licenseGroup("ios", title: "iOS 端依赖的组件")
        licenseGroup("host", title: "设备端 zeta-helper 与 claude-switch 依赖的组件")
    }

    private func licenseGroup(_ place: String, title: LocalizedStringKey) -> some View {
        Section(title) {
            ForEach((about?.licenses ?? []).filter { $0.place == place }) { item in
                NavigationLink {
                    LicenseTextView(title: item.module, file: item.file)
                } label: {
                    Text(item.module == "Go" ? String(localized: "Go 标准库与运行时 \(goVersion(item))") : item.module)
                    Text(item.version)
                }
            }
        }
    }

    private func goVersion(_ item: LicenseItem) -> String {
        item.version.hasPrefix("go") ? String(item.version.dropFirst(2)) : item.version
    }
}

private struct Credit {
    let name: String
    let summary: LocalizedStringResource
    let url: String
}

/// 一项许可的全文。
private struct LicenseTextView: View {
    @Environment(AppModel.self) private var model
    let title: String
    let file: String
    @State private var text: String?
    @State private var problem: String?

    var body: some View {
        ScrollView {
            Group {
                if let text {
                    Text(text).font(.caption.monospaced()).textSelection(.enabled)
                } else if let problem {
                    Text(problem).foregroundStyle(.red)
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do {
                text = try await model.request("license", ["file": file], as: TextResult.self).text
            } catch {
                problem = error.localizedDescription
            }
        }
    }
}
