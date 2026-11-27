import SwiftUI
import UIKit

/// 输入栏上方的排队条（同手机版 QueueStrip.kt）：回答中发的消息排在这里。点正文撤回到输入框接着改，
/// 「立即插话」把排队的马上送进这一轮，删除键撤回不要。
struct QueueStrip: View {
    @Environment(AppModel.self) private var model
    let queued: [QueuedMessage]
    let steering: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Circle().fill(.tint).frame(width: 6, height: 6)
                Text(title).font(.footnote).foregroundStyle(.tint)
                Spacer()
                if !steering {
                    Button("立即插话", systemImage: "bolt.fill") { model.perform("composer.steer") }
                        .font(.footnote)
                        .buttonStyle(.borderless)
                }
            }
            ForEach(queued) { message in
                HStack {
                    Button {
                        model.perform("composer.cancel", ["uuid": message.uuid, "recall": true])
                    } label: {
                        Text(preview(message)).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(Text("撤回到输入框"))
                    Button("删除", systemImage: "trash", role: .destructive) {
                        model.perform("composer.cancel", ["uuid": message.uuid, "recall": false])
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                }
                .font(.subheadline)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .glassEffect(.regular, in: .rect(cornerRadius: 20))
    }

    private var title: String {
        if steering { return String(localized: "正在插话") }
        if queued.count > 1 { return String(localized: "\(queued.count) 条排队中") }
        return String(localized: "排队中")
    }

    private func preview(_ message: QueuedMessage) -> String {
        switch (message.preview.isEmpty, message.extra > 0) {
        case (false, true): message.preview + " +\(message.extra)"
        case (false, false): message.preview
        default: message.images > 0 ? String(localized: "\(message.images) 张图片") : String(localized: "附件")
        }
    }
}

/// 输入栏上方的斜杠命令提示（同手机版 SlashSuggestions）：「/命令」和参数提示一行，说明在下一行；点一下换掉光标处的那个词。
struct SlashList: View {
    let commands: [SlashCommand]
    let pick: (SlashCommand) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(commands) { command in
                    Button { pick(command) } label: { row(command) }
                        .buttonStyle(.plain)
                }
            }
        }
        .frame(maxHeight: 232)
        .fixedSize(horizontal: false, vertical: true)
        .glassEffect(.regular, in: .rect(cornerRadius: 20))
    }

    private func row(_ command: SlashCommand) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Text("/" + command.name).font(.subheadline.weight(.medium)).foregroundStyle(.tint)
                if !command.argumentHint.isEmpty {
                    Text(command.argumentHint).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            if !command.description.isEmpty {
                Text(command.description).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .contentShape(.rect)
    }
}

/// 输入框上方一排待发的附件：图片是缩略图（点一下看大图），文件是小标签；正在传的带进度，没传成的标红；各带移除键。
struct AttachmentsRow: View {
    @Environment(AppModel.self) private var model
    let attachments: [Attachment]

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(attachments) { item in
                    ZStack(alignment: .topTrailing) {
                        if item.picture {
                            Thumbnail(item: item)
                        } else {
                            FileChip(item: item)
                        }
                        Button("移除", systemImage: "xmark.circle.fill") {
                            model.perform("composer.remove", ["id": item.id])
                        }
                        .labelStyle(.iconOnly)
                        .foregroundStyle(.white, .black.opacity(0.6))
                        .padding(2)
                    }
                }
            }
            .padding(.horizontal, 6)
            .padding(.top, 6)
        }
        .scrollIndicators(.hidden)
    }
}

/// 待发图片的缩略图。点一下全屏看：读的是本机那个文件（这次选的是原图，撤回放回的、草稿里恢复的是缩小图），不经设备。
private struct Thumbnail: View {
    let item: Attachment
    @State private var full: UIImage?
    @State private var viewing = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(.quaternary)
            if let image = ThumbCache.shared.image(item.thumb) {
                Image(uiImage: image).resizable().scaledToFill()
            }
            if !item.failed.isEmpty {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
            } else if item.uploading {
                ProgressView()
            }
        }
        .frame(width: 64, height: 64)
        .clipShape(.rect(cornerRadius: 12))
        .contentShape(.rect)
        .onTapGesture {
            // 原图在后台解好再弹出，大照片不卡界面
            Task {
                guard let image = UIImage(contentsOfFile: item.thumb) else { return }
                full = await image.byPreparingForDisplay() ?? image
                viewing = true
            }
        }
        .fullScreenCover(
            isPresented: $viewing, onDismiss: { full = nil },
            content: {
                if let full { ImageViewer(image: full) }
            })
        .accessibilityLabel(item.failed.isEmpty ? item.name : item.failed)
        .accessibilityAddTraits(.isButton)
    }
}

private struct FileChip: View {
    let item: Attachment

    var body: some View {
        Label {
            Text(label).lineLimit(1)
        } icon: {
            Image(systemName: item.failed.isEmpty ? "doc" : "exclamationmark.triangle")
                .foregroundStyle(item.failed.isEmpty ? Color.accentColor : .red)
        }
        .font(.footnote)
        .padding(.leading, 10)
        .padding(.trailing, 28)
        .frame(height: 40)
        .frame(maxWidth: 240)
        .background(.quaternary, in: .rect(cornerRadius: 12))
    }

    private var label: String {
        if !item.failed.isEmpty { return String(localized: "没传成：\(item.name)") }
        if item.uploading {
            return item.percent >= 0
                ? String(localized: "正在传 \(item.percent)%：\(item.name)")
                : String(localized: "正在传：\(item.name)")
        }
        return item.name
    }
}

/// 缩略图按路径缓存（只解一次、按 128 点的边缩小，大照片不留整张的像素）。
@MainActor
final class ThumbCache {
    static let shared = ThumbCache()
    private var images: [String: UIImage] = [:]

    func image(_ path: String) -> UIImage? {
        guard !path.isEmpty else { return nil }
        if let image = images[path] { return image }
        guard let full = UIImage(contentsOfFile: path),
            let small = full.preparingThumbnail(of: Self.fit(full.size))
        else { return nil }
        images[path] = small
        return small
    }

    private static func fit(_ size: CGSize) -> CGSize {
        let scale = 128 / max(size.width, size.height, 1)
        return scale >= 1 ? size : CGSize(width: size.width * scale, height: size.height * scale)
    }
}
