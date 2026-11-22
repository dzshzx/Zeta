package com.dzshzx.zeta.core.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

private const val ALIAS = "zeta-ssh-ed25519"

/** Android Keystore 里的那把，没有就生成。[comment] 写在公钥行末尾，让人在 authorized_keys 里认得出。 */
fun SshKey.Companion.keystore(comment: String): SshKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!store.containsAlias(ALIAS)) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
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
