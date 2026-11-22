package com.dzshzx.zeta.core.ssh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Zeta 直连远程主机用的那把密钥：Ed25519。手机上私钥在 Android Keystore 里生成、存放，取不出来，签名也在里面做（app 的 keystore）；
 * 测试里用内存里的（[generate]）。用户把 [authorizedKey] 这一行加进远程主机的 ~/.ssh/authorized_keys，Zeta 就能登录。
 * 连接、验对方主机的签名都在 zeta-ssh（Go）里，它要签名时请应用调 [sign]。
 */
class SshKey(private val private: PrivateKey, public: PublicKey, private val comment: String) {
    /** 公钥的 32 字节（X.509 编码的最后 32 字节，前面是固定的算法标识）。 */
    private val raw: ByteArray =
        public.encoded.let { encoded ->
            require(
                encoded.size == SPKI_PREFIX.size + 32 &&
                    encoded.copyOfRange(0, SPKI_PREFIX.size).contentEquals(SPKI_PREFIX),
            ) {
                "不是 Ed25519 公钥"
            }
            encoded.copyOfRange(SPKI_PREFIX.size, encoded.size)
        }

    /** SSH 线上格式的公钥：string 算法名、string 32 字节的公钥。 */
    val blob: ByteArray = sshBytes {
        string(ALGORITHM)
        string(raw)
    }

    /** authorized_keys 里的一行。 */
    val authorizedKey: String
        get() = "$ALGORITHM ${Base64.getEncoder().encodeToString(blob)} $comment"

    /** 和 ssh-keygen -l 一样的指纹：SHA256: 加去掉补位的 base64。 */
    val fingerprint: String
        get() = fingerprint(blob)

    /** 给 [data] 签名，返回原始的 64 字节 Ed25519 签名（不带 SSH 的外层）。手机上在 Keystore 里做。阻塞。 */
    fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(private)
            update(data)
            sign()
        }

    companion object {
        const val ALGORITHM = "ssh-ed25519"

        /** Ed25519 公钥 X.509 编码（SubjectPublicKeyInfo）的开头：算法 1.3.101.112，后面跟 32 字节公钥。 */
        private val SPKI_PREFIX =
            byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        /** 存得下的主机公钥类型（OpenSSH 的主机密钥只有这几种）。 */
        private val HostKeyTypes =
            setOf(
                ALGORITHM,
                "ecdsa-sha2-nistp256",
                "ecdsa-sha2-nistp384",
                "ecdsa-sha2-nistp521",
                "ssh-rsa",
                "ssh-dss",
            )

        /** 内存里新生成一把（测试用，要 Java 15 起的 JDK）。 */
        fun generate(comment: String = "zeta-test"): SshKey {
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            return SshKey(pair.private, pair.public, comment)
        }

        /** 公钥 [blob]（SSH 线上格式）的指纹。 */
        fun fingerprint(blob: ByteArray): String =
            "SHA256:" +
                Base64.getEncoder()
                    .withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

        /**
         * 主机公钥 [blob]（SSH 线上格式）的类型；不是一个完整的、认得的主机公钥（截断了、多出字节、类型不认得）时是 null。 只看格式： 连接时 zeta-ssh
         * 还会整个解析、照它核对。
         */
        fun hostKeyType(blob: ByteArray): String? {
            val buffer = ByteBuffer.wrap(blob)
            val fields = mutableListOf<ByteArray>()
            while (buffer.hasRemaining()) {
                if (buffer.remaining() < 4) return null
                val size = buffer.getInt()
                if (size < 0 || size > buffer.remaining()) return null
                fields += ByteArray(size).also { buffer.get(it) }
            }
            val type = fields.firstOrNull()?.toString(Charsets.UTF_8) ?: return null
            return type.takeIf { it in HostKeyTypes && fields.size > 1 }
        }
    }
}

/** 拼 SSH 线上格式：string 是长度加内容。 */
class SshWriter {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)

    fun string(value: String) = string(value.toByteArray(Charsets.UTF_8))

    fun string(value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

fun sshBytes(build: SshWriter.() -> Unit): ByteArray = SshWriter().apply(build).toByteArray()
