import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 选好的图片和文件：写进 App 临时目录（各放一个单独的目录，文件名就是传上去的名字），把路径交给核心（composer.attach），
/// 传到设备上、做缩小图、查大小上限都由核心做。
enum Picked {
    /// 核心认得出、能做缩小图的图片格式；别的（HEIC、AVIF 这些相册常见的）先转成 JPEG，不然模型看不到图。
    private static let decodable: Set<String> = ["png", "jpg", "jpeg", "gif", "webp", "bmp"]

    @MainActor
    static func attach(photos: [PhotosPickerItem], model: AppModel) async {
        var paths: [String] = []
        for (index, item) in photos.enumerated() {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let ext = item.supportedContentTypes.first?.preferredFilenameExtension?.lowercased() ?? "jpg"
            let base = "IMG-\(Int(Date.now.timeIntervalSince1970))-\(index + 1)"
            if let path = await Task.detached(operation: { Self.write(data, base: base, ext: ext) }).value {
                paths.append(path)
            }
        }
        send(paths, model: model)
    }

    /// 相机拍的照片：存成 JPEG 交给核心。
    @MainActor
    static func attach(photo: UIImage, model: AppModel) async {
        let base = "IMG-\(Int(Date.now.timeIntervalSince1970))"
        guard let data = photo.jpegData(compressionQuality: 0.9) else { return }
        if let path = await Task.detached(operation: { Self.write(data, base: base, ext: "jpg") }).value {
            send([path], model: model)
        }
    }

    @MainActor
    static func attach(files: [URL], model: AppModel) async {
        let paths = await Task.detached { files.compactMap(Self.copy) }.value
        send(paths, model: model)
    }

    @MainActor
    private static func send(_ paths: [String], model: AppModel) {
        guard !paths.isEmpty else { return }
        model.perform("composer.attach", ["files": paths.map { ["path": $0] }])
    }

    private nonisolated static func folder() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appending(path: "attachments", directoryHint: .isDirectory)
            .appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private nonisolated static func write(_ data: Data, base: String, ext: String) -> String? {
        var data = data
        var ext = ext
        if !decodable.contains(ext), let jpeg = UIImage(data: data)?.jpegData(compressionQuality: 0.9) {
            data = jpeg
            ext = "jpg"
        }
        guard let dir = try? folder() else { return nil }
        let file = dir.appending(path: base + "." + ext)
        return (try? data.write(to: file)) == nil ? nil : file.path
    }

    /// 把 fileImporter 选的文件拷进临时目录（选的文件在沙盒外，要先拿到访问权）。拷不成的照样交给核心，由它说读不了。
    private nonisolated static func copy(_ url: URL) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let dir = try? folder() else { return nil }
        let target = dir.appending(path: url.lastPathComponent)
        do {
            try FileManager.default.copyItem(at: url, to: target)
        } catch {
            return url.path
        }
        return target.path
    }
}
