import SwiftUI
import UIKit

/// 聊天里的图取来以后记着（按 ChatImage.cacheKey），同一张图只取一次；取法照桌面版（核心桥的 image：
/// 实时收到的经 conv.image，会话记录里的经 sessions.image，回答里写的主机路径经 images.file，网址从这台手机下载）。
@MainActor @Observable
final class ImageStore {
    static let shared = ImageStore()

    enum Entry {
        case loading
        case loaded(UIImage)
        case failed(String)
    }

    private(set) var entries: [String: Entry] = [:]

    func entry(_ image: ChatImage) -> Entry? { entries[image.cacheKey] }

    /// 一张图的原始数据（base64）：实时收到的带着数据就地用，别的经核心桥的 image 取。
    static func base64(_ image: ChatImage, model: AppModel) async throws -> String {
        if let data = image.data { return data }
        return try await model.request("image", image.params, as: Picture.self).data
    }

    func load(_ image: ChatImage, model: AppModel) {
        let key = image.cacheKey
        switch entries[key] {
        case .loading, .loaded: return
        default: break
        }
        if let data = image.data, let decoded = Data(base64Encoded: data), let picture = UIImage(data: decoded) {
            entries[key] = .loaded(picture)
            return
        }
        entries[key] = .loading
        Task {
            do {
                let got = try await Self.base64(image, model: model)
                guard let data = Data(base64Encoded: got), let picture = UIImage(data: data) else {
                    entries[key] = .failed(String(localized: "不是图片"))
                    return
                }
                entries[key] = .loaded(picture)
            } catch {
                entries[key] = .failed(error.localizedDescription)
            }
        }
    }
}

/// 一张聊天里的图：缩略图，点了看大图。网络上的图等用户点一下才下载（设置里开了自动加载时直接下载）。
struct ChatImageView: View {
    @Environment(AppModel.self) private var model
    let image: ChatImage
    var maxHeight: CGFloat = 240
    @State private var viewing = false

    private var store: ImageStore { ImageStore.shared }
    private var autoLoad: Bool { !image.remote || model.state.chat?.autoImages == true }

    var body: some View {
        Group {
            switch store.entry(image) {
            case .loaded(let picture):
                Button { viewing = true } label: {
                    Image(uiImage: picture)
                        .resizable()
                        .scaledToFit()
                        .frame(maxHeight: maxHeight)
                        .clipShape(.rect(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .fullScreenCover(isPresented: $viewing) { ImageViewer(image: picture) }
            case .loading:
                placeholder { ProgressView() }
            case .failed(let why):
                placeholder {
                    Button("重新加载", systemImage: "arrow.clockwise") { retry() }
                        .help(why)
                }
            case nil:
                placeholder {
                    if autoLoad {
                        ProgressView()
                    } else {
                        Button("点按加载网络图片", systemImage: "photo") { store.load(image, model: model) }
                            .font(.footnote)
                    }
                }
                .task { if autoLoad { store.load(image, model: model) } }
            }
        }
    }

    private func retry() {
        store.forget(image)
        store.load(image, model: model)
    }

    private func placeholder(@ViewBuilder _ content: () -> some View) -> some View {
        content()
            .frame(width: 160, height: 100)
            .background(.fill.tertiary, in: .rect(cornerRadius: 12))
    }
}

extension ImageStore {
    func forget(_ image: ChatImage) { entries[image.cacheKey] = nil }
}

/// 看大图：双指缩放、拖动，双击复原；保存到相册、分享。
struct ImageViewer: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var saved = false

    var body: some View {
        NavigationStack {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .scaleEffect(scale)
                .offset(offset)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(.black)
                .gesture(
                    MagnifyGesture()
                        .onChanged { scale = max(1, lastScale * $0.magnification) }
                        .onEnded { _ in lastScale = scale }
                        .simultaneously(with: DragGesture()
                            .onChanged {
                                offset = CGSize(
                                    width: lastOffset.width + $0.translation.width,
                                    height: lastOffset.height + $0.translation.height)
                            }
                            .onEnded { _ in lastOffset = offset })
                )
                .onTapGesture(count: 2) {
                    withAnimation {
                        scale = 1
                        lastScale = 1
                        offset = .zero
                        lastOffset = .zero
                    }
                }
                .ignoresSafeArea()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("关闭", systemImage: "xmark") { dismiss() }
                    }
                    ToolbarItemGroup(placement: .primaryAction) {
                        Button(
                            saved ? String(localized: "已保存") : String(localized: "保存到相册"),
                            systemImage: saved ? "checkmark" : "square.and.arrow.down"
                        ) {
                            UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
                            saved = true
                        }
                        .disabled(saved)
                        ShareLink(
                            item: Image(uiImage: image),
                            preview: SharePreview(String(localized: "图片"), image: Image(uiImage: image)))
                    }
                }
        }
    }
}
