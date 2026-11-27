import Foundation
import Security

/// 钥匙串里 Zeta 登录设备用的密钥（都只在这台设备上、首次解锁后可读，不进备份）：
/// Zeta 自己的 Ed25519 私钥只存 32 字节的种子，第一次用时生成；用户导入的私钥存核心去掉密码后的 OpenSSH 格式，
/// 起核心后用 importkey 交回去。公钥由核心算。
enum KeyStore {
    private static func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.dzshzx.zeta.ssh",
            kSecAttrAccount as String: account
        ]
    }

    private static let seedAccount = "ed25519-seed"
    private static let importedAccount = "imported-private-key"

    private static func read(_ account: String) throws -> Data? {
        var lookup = query(account)
        lookup[kSecReturnData as String] = true
        var found: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &found)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = found as? Data else {
            throw Core.Failure(message: "keychain read failed: \(status)")
        }
        return data
    }

    private static func write(_ account: String, _ data: Data) throws {
        SecItemDelete(query(account) as CFDictionary)
        var item = query(account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(item as CFDictionary, nil)
        guard added == errSecSuccess else { throw Core.Failure(message: "keychain write failed: \(added)") }
    }

    static func seed() throws -> Data {
        if let data = try read(seedAccount), data.count == 32 { return data }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw Core.Failure(message: "no random bytes for the key")
        }
        let seed = Data(bytes)
        try write(seedAccount, seed)
        return seed
    }

    /// 导入的私钥，没导入过时是 nil。
    static func imported() throws -> String? {
        try read(importedAccount).flatMap { String(bytes: $0, encoding: .utf8) }
    }

    static func saveImported(_ pem: String) throws {
        try write(importedAccount, Data(pem.utf8))
    }

    static func clearImported() {
        SecItemDelete(query(importedAccount) as CFDictionary)
    }
}
