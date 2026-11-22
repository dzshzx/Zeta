package com.dzshzx.zeta.core.ssh

import com.dzshzx.zeta.core.RemoteHosts
import com.jcraft.jsch.Identity

/**
 * Zeta 直连（[SshLinks]）时，地址实际连到哪、用哪些密钥登录。手机上是 Zeta 自己的那把（[OwnKeyAuth]），地址要写明用户； 桌面上照这台电脑 ~/.ssh
 * 的配置和密钥（desktop 的 OpenSshConfigAuth），地址可以是 ~/.ssh/config 里的别名。
 */
interface SshAuth {
    /** 地址可以是 ~/.ssh/config 里的别名（[RemoteHosts.targetFor]）；不可以时只认写明用户的 [SshTarget]。 */
    val aliases: Boolean

    /** 设置页上给人复制公钥的 Zeta 自己的密钥；用这台电脑现成密钥的是 null。阻塞：手机上第一次要进 Keystore。 */
    fun ownKey(): SshKey?

    /**
     * 地址 [target]（[com.dzshzx.zeta.core.RemoteHost.target]）实际连到哪、用哪些密钥：[key] 是用户选的私钥（[keys] 里的一项），
     * null 照默认的（~/.ssh/config 给这台配的）。用不了时抛 [SshProblem]。阻塞。
     */
    fun resolve(target: String, key: String?): SshRoute

    /** 对方不认 [route] 的密钥时给人看的一句话。 */
    fun rejected(route: SshRoute): String

    /** ~/.ssh/config 里能选的主机（[aliases] 时，[RemoteHosts.parseAliases] 的规矩）；读不到时抛出，消息给人看。阻塞。 */
    fun configHosts(): List<String> = emptyList()

    /** 能选的私钥（存进 [com.dzshzx.zeta.core.RemoteHost.key] 的写法）；只有一把、不能选的是空的。阻塞。 */
    fun keys(): List<String> = emptyList()

    /** 私钥 [key]（[keys] 里的一项）给人看的名字。 */
    fun keyName(key: String): String = key
}

/** 连接的目的地，和登录时依次试的密钥；[keys] 是给人看的密钥名。 */
class SshRoute(val target: SshTarget, val identities: List<Identity>, val keys: String)

/** 已经是一句给人看的话的连接问题（[SshLinks.problem] 原样给出）。 */
class SshProblem(message: String) : Exception(message)

/** 手机上：Zeta 自己的密钥（[key] 第一次要时才取），地址只认写明用户的 [SshTarget]。 */
class OwnKeyAuth(key: () -> SshKey) : SshAuth {
    private val key: SshKey by lazy(key)

    override val aliases = false

    override fun ownKey(): SshKey = key

    override fun resolve(target: String, key: String?): SshRoute =
        SshRoute(
            SshTarget.parse(target) ?: throw SshProblem(RemoteHosts.DIRECT_TARGET_HINT),
            listOf(this.key),
            "Zeta 的密钥",
        )

    override fun rejected(route: SshRoute) = "对方不认 Zeta 的公钥：把它加进那台设备的 ~/.ssh/authorized_keys"
}
