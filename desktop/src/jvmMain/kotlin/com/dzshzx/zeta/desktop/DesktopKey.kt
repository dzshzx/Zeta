package com.dzshzx.zeta.desktop

import com.dzshzx.zeta.core.ssh.SshKey
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * 桌面版直连远程主机用的 Ed25519 密钥：私钥（PKCS#8）存在 [dir] 下的 ssh_ed25519，公钥（X.509）在 ssh_ed25519.x509，
 * authorized_keys 那一行写在 ssh_ed25519.pub 里给人复制。没有就生成。Windows 上 %APPDATA% 只有本用户能读；
 * 别的系统上把私钥文件设成只有本用户能读写。
 */
object DesktopKey {
    fun loadOrCreate(dir: File, comment: String): SshKey {
        val private = File(dir, "ssh_ed25519")
        val public = File(dir, "ssh_ed25519.x509")
        val factory = KeyFactory.getInstance("Ed25519")
        if (!private.isFile || !public.isFile) {
            dir.mkdirs()
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            private.writeBytes(pair.private.encoded)
            runCatching {
                Files.setPosixFilePermissions(
                    private.toPath(),
                    PosixFilePermissions.fromString("rw-------"),
                )
            }
            public.writeBytes(pair.public.encoded)
        }
        val key =
            SshKey(
                factory.generatePrivate(PKCS8EncodedKeySpec(private.readBytes())),
                factory.generatePublic(X509EncodedKeySpec(public.readBytes())),
                comment,
            )
        runCatching { File(dir, "ssh_ed25519.pub").writeText(key.authorizedKey + "\n") }
        return key
    }
}
