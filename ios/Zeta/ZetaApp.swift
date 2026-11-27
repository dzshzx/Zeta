import SwiftUI

@main
struct ZetaApp: App {
    @State private var model = AppModel.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .onAppear {
                    Notifier.shared.install()
                    model.start()
                }
                .onChange(of: phase) { _, now in
                    if now == .background { Notifier.shared.enteredBackground(busy: model.state.chat?.busy == true) }
                }
        }
    }
}

/// 侧栏是会话列表，右边（iPhone 上推进来）是聊天页。选中的是会话的键，或新对话。
struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var selection: Route?
    @State private var settingsShown = false

    var body: some View {
        if let problem = model.startProblem {
            ContentUnavailableView("Zeta 起不来", systemImage: "exclamationmark.triangle", description: Text(problem))
        } else {
            NavigationSplitView {
                SessionListView(selection: $selection, settingsShown: $settingsShown)
            } detail: {
                if selection != nil, model.state.chat != nil {
                    ChatView()
                } else {
                    ContentUnavailableView("选一个对话", systemImage: "bubble.left.and.text.bubble.right",
                                           description: Text("或者开一个新对话"))
                }
            }
            .onChange(of: selection) { _, route in open(route) }
            .onOpenURL { url in
                // 点灵动岛或锁屏上的实时活动：打开那个会话
                if let route = ZetaActivityAttributes.route(url) {
                    Notifier.shared.opening = .session(backend: route.backend, id: route.id)
                }
            }
            .onChange(of: Notifier.shared.opening) { _, route in
                guard let route else { return }
                Notifier.shared.opening = nil
                selection = route
            }
            .sheet(isPresented: $settingsShown) { SettingsView() }
            .alert("没有成功", isPresented: Binding { model.alert != nil } set: { if !$0 { model.alert = nil } }) {
                Button("好") { model.alert = nil }
            } message: {
                Text(model.alert ?? "")
            }
        }
    }

    private func open(_ route: Route?) {
        switch route {
        case .session(let backend, let id):
            model.perform("open", ["backend": backend, "id": id])
        case .new(let backend):
            model.perform("new", ["backend": backend])
        case nil:
            model.perform("close")
        }
    }
}

enum Route: Hashable {
    case session(backend: String, id: String)
    case new(backend: String)
}
