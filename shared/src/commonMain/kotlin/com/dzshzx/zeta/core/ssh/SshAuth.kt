package com.dzshzx.zeta.core.ssh

/** Zeta 直连（[SshLinks]）时用哪把密钥：Zeta 自己的那把（[OwnKeyAuth]），地址要写明用户。 */
interface SshAuth {
    /** 设置页上给人复制公钥的 Zeta 自己的密钥；没有时是 null。阻塞：第一次要进 Keystore。 */
    fun ownKey(): SshKey?
}

/** 已经是一句给人看的话的连接问题。 */
class SshProblem(message: String) : Exception(message)

/** Zeta 自己的密钥（[key] 第一次要时才取），地址只认写明用户的 [SshTarget]。 */
class OwnKeyAuth(key: () -> SshKey) : SshAuth {
    private val key: SshKey by lazy(key)

    override fun ownKey(): SshKey = key
}
