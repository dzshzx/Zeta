package com.dzshzx.zeta.core.ssh

/**
 * 用户名、主机、端口都写明的目的地：`用户@主机`，可以带端口（`用户@主机:端口`、`用户@[IPv6]:端口`），前面可以带 `ssh://`。 Zeta 直连只认这种（直连不读
 * ~/.ssh/config，所以用户名不能省）；经手机 Debian 连时交给 OpenSSH 的是 [openSsh]。
 */
data class SshTarget(val user: String, val host: String, val port: Int) {
    private val bracketed: String
        get() = if (':' in host) "[$host]" else host

    override fun toString(): String =
        if (port == 22) "$user@$bracketed" else "$user@$bracketed:$port"

    /** 交给 OpenSSH 的写法：它不认 `主机:端口`，端口不是 22 或主机是 IPv6 时写成 `ssh://用户@主机:端口`。 */
    val openSsh: String
        get() = if (port == 22 && ':' !in host) "$user@$host" else "ssh://$user@$bracketed:$port"

    companion object {
        private val UserChars = Regex("[A-Za-z0-9._][A-Za-z0-9._-]*")
        private val HostChars = Regex("[A-Za-z0-9._%:-]+")

        fun parse(text: String): SshTarget? {
            val t = text.trim().removePrefix("ssh://").removeSuffix("/")
            val at = t.lastIndexOf('@')
            if (at <= 0) return null
            val user = t.substring(0, at).takeIf { UserChars.matches(it) } ?: return null
            val rest = t.substring(at + 1)
            val (host, port) =
                when {
                    rest.startsWith("[") -> {
                        val close = rest.indexOf(']')
                        if (close < 0) return null
                        val after = rest.substring(close + 1)
                        rest.substring(1, close) to
                            if (after.isEmpty()) 22 else after.removePrefix(":").toIntOrNull()
                    }
                    rest.count { it == ':' } == 1 ->
                        rest.substringBefore(':') to rest.substringAfter(':').toIntOrNull()
                    else -> rest to 22
                }
            if (
                port == null ||
                    port !in 1..65535 ||
                    host.isEmpty() ||
                    host.startsWith("-") ||
                    !HostChars.matches(host)
            )
                return null
            return SshTarget(user, host, port)
        }
    }
}
