package com.dzshzx.zeta.core.ssh

import com.dzshzx.zeta.core.RemoteHosts
import com.jcraft.jsch.Identity

/** Zeta 直连（[SshLinks]）时，地址实际连到哪、用哪些密钥登录。手机上是 Zeta 自己的那把（[OwnKeyAuth]），地址要写明用户。 */
interface SshAuth {
    /** 设置页上给人复制公钥的 Zeta 自己的密钥；没有时是 null。阻塞：第一次要进 Keystore。 */
    fun ownKey(): SshKey?

    /**
     * 地址 [target]（[com.dzshzx.zeta.core.RemoteHost.target]）实际连到哪、用哪些密钥：[key] 见
     * [com.dzshzx.zeta.core.RemoteHost.key]。用不了时抛 [SshProblem]。阻塞。
     */
    fun resolve(target: String, key: String?): SshRoute

    /** 对方不认 [route] 的密钥时给人看的一句话。 */
    fun rejected(route: SshRoute): String
}

/** 连接的目的地，和登录时依次试的密钥；[keys] 是给人看的密钥名。 */
class SshRoute(val target: SshTarget, val identities: List<Identity>, val keys: String)

/** 已经是一句给人看的话的连接问题（[SshLinks.problem] 原样给出）。 */
class SshProblem(message: String) : Exception(message)

/** 手机上：Zeta 自己的密钥（[key] 第一次要时才取），地址只认写明用户的 [SshTarget]。 */
class OwnKeyAuth(key: () -> SshKey) : SshAuth {
    private val key: SshKey by lazy(key)

    override fun ownKey(): SshKey = key

    override fun resolve(target: String, key: String?): SshRoute =
        SshRoute(
            SshTarget.parse(target) ?: throw SshProblem(RemoteHosts.DIRECT_TARGET_HINT),
            listOf(this.key),
            "Zeta 的密钥",
        )

    override fun rejected(route: SshRoute) = "对方不认 Zeta 的公钥：把它加进那台设备的 ~/.ssh/authorized_keys"
}
