package com.dzshzx.zeta.core.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jcraft.jsch.Identity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Zeta 直连远程主机用的那把密钥：Ed25519。手机上私钥在 Android Keystore 里生成、存放，取不出来，签名也在里面做（[keystore]）；
 * 测试里用内存里的（[generate]）。用户把 [authorizedKey] 这一行加进远程主机的 ~/.ssh/authorized_keys，Zeta 就能登录。 验对方主机的
 * Ed25519 签名不归这里：Android 自带的加密库没有 Ed25519，JSch 用 BouncyCastle 验。
 */
class SshKey(private val private: PrivateKey, public: PublicKey, private val comment: String) :
    Identity {
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

    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun getPublicKeyBlob(): ByteArray = blob

    /** JSch 要的签名：string 算法名、string 64 字节的签名。 */
    override fun getSignature(data: ByteArray): ByteArray {
        val signature =
            Signature.getInstance("Ed25519").run {
                initSign(private)
                update(data)
                sign()
            }
        return sshBytes {
            string(ALGORITHM)
            string(signature)
        }
    }

    override fun getAlgName(): String = ALGORITHM

    override fun getName(): String = comment

    override fun isEncrypted(): Boolean = false

    override fun clear() = Unit

    companion object {
        const val ALGORITHM = "ssh-ed25519"
        private const val ALIAS = "zeta-ssh-ed25519"

        /** Ed25519 公钥 X.509 编码（SubjectPublicKeyInfo）的开头：算法 1.3.101.112，后面跟 32 字节公钥。 */
        private val SPKI_PREFIX =
            byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        /** Keystore 里的那把，没有就生成。[comment] 写在公钥行末尾，让人在 authorized_keys 里认得出。 */
        fun keystore(comment: String): SshKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                    .run {
                        initialize(
                            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                                .setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
                                .setDigests(KeyProperties.DIGEST_NONE)
                                .build(),
                        )
                        generateKeyPair()
                    }
            }
            // 分开取私钥和证书：getEntry 拼 PrivateKeyEntry 时要私钥和证书公钥的算法名一样，Ed25519 的两边对不上，会抛异常
            val private = store.getKey(ALIAS, null) as PrivateKey
            return SshKey(private, store.getCertificate(ALIAS).publicKey, comment)
        }

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
    }
}

/** 拼 SSH 线上格式：string 是长度加内容。 */
internal class SshWriter {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)

    fun string(value: String) = string(value.toByteArray(Charsets.UTF_8))

    fun string(value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

internal fun sshBytes(build: SshWriter.() -> Unit): ByteArray =
    SshWriter().apply(build).toByteArray()
