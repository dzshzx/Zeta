import Foundation
import Security

/// Zeta 登录设备用的 Ed25519 私钥：只存 32 字节的种子，放在钥匙串里（只在这台设备上、首次解锁后可读，不进备份），
/// 第一次用时生成。公钥由核心算（方法 key）。
enum KeyStore {
    private static var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.dzshzx.zeta.ssh",
            kSecAttrAccount as String: "ed25519-seed"
        ]
    }

    static func seed() throws -> Data {
        var lookup = query
        lookup[kSecReturnData as String] = true
        var found: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &found)
        if status == errSecSuccess, let data = found as? Data, data.count == 32 {
            return data
        }
        guard status == errSecItemNotFound else { throw Core.Failure(message: "keychain read failed: \(status)") }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw Core.Failure(message: "no random bytes for the key")
        }
        let seed = Data(bytes)
        var item = query
        item[kSecValueData as String] = seed
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(item as CFDictionary, nil)
        guard added == errSecSuccess else { throw Core.Failure(message: "keychain write failed: \(added)") }
        return seed
    }
}
