import Foundation
import ZetaCore

/// 核心（Go，windows/internal/mobile）的 Swift 一侧：起核心、按方法名调用。协议见 docs/ios.md「核心桥」。
/// 核心在进程里只有一份，状态都在 Go 那边，这里没有存东西，所以能在任意线程上用。
struct Core: Sendable {
    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// 起核心：dir 是设置目录，seed 是登录用密钥的种子，lang 是界面语言，device 是报给主机的这台设备的名字；状态有变化时在 Go 的线程上调 changed。
    /// 再调一次不会再起一份，直接可用。
    static func start(
        dir: URL, seed: Data, lang: String, device: String, changed: @escaping @convention(c) () -> Void
    ) throws -> Core {
        let failed: UnsafeMutablePointer<CChar>? = dir.path.withCString { path in
            lang.withCString { language in
                device.withCString { name in
                    seed.withUnsafeBytes { bytes in
                        ZetaStart(
                            UnsafeMutablePointer(mutating: path),
                            UnsafeMutableRawPointer(mutating: bytes.baseAddress),
                            Int32(bytes.count),
                            UnsafeMutablePointer(mutating: language),
                            UnsafeMutablePointer(mutating: name),
                            changed)
                    }
                }
            }
        }
        if let failed {
            defer { ZetaFree(failed) }
            throw Failure(message: String(cString: failed))
        }
        return Core()
    }

    /// 调方法 method，参数是 JSON 对象；答的是出错（{"error": …}）时抛 Failure。
    func call(_ method: String, _ params: [String: any Sendable] = [:]) throws -> Data {
        let body = try JSONSerialization.data(withJSONObject: params)
        let answer: UnsafeMutablePointer<CChar>? = method.withCString { name in
            body.withUnsafeBytes { bytes in
                ZetaCall(
                    UnsafeMutablePointer(mutating: name),
                    UnsafeMutableRawPointer(mutating: bytes.baseAddress),
                    Int32(bytes.count))
            }
        }
        guard let answer else { throw Failure(message: "core returned nothing for \(method)") }
        defer { ZetaFree(answer) }
        let data = Data(bytes: answer, count: strlen(answer))
        if let failed = try? JSONDecoder().decode(Refusal.self, from: data), let message = failed.error {
            throw Failure(message: message)
        }
        return data
    }

    func call<T: Decodable>(_ method: String, _ params: [String: any Sendable] = [:], as type: T.Type) throws -> T {
        try JSONDecoder().decode(type, from: call(method, params))
    }

    private struct Refusal: Decodable {
        let error: String?
    }
}
