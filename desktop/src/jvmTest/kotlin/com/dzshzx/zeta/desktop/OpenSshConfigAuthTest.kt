package com.dzshzx.zeta.desktop

import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.ssh.SshLinks
import com.dzshzx.zeta.core.ssh.SshProblem
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面版直连照这台电脑的 ~/.ssh：真的 `ssh -G` 读一份临时的配置（Include、%d、~ 都走一遍），用 ssh-keygen 生成的 OpenSSH 私钥文件登录 JVM 里的
 * SSH 服务器。要本机有 OpenSSH 客户端（ssh、ssh-keygen），没有就失败。
 */
class OpenSshConfigAuthTest {
    private val home = Files.createTempDirectory("zeta-desktop-home").toFile()
    private val dir = File(home, ".ssh").apply { mkdirs() }
    private val key = File(dir, "k").also { keygen(it) }
    private val stranger = File(dir, "stranger").also { keygen(it) }
    private val server = server(fingerprint(key))
    private val config = File(dir, "config")
    private val auth = OpenSshConfigAuth(home, config = config)

    init {
        File(dir, "extra").writeText("Host box\n  IdentityFile %d/.ssh/k\n  IdentitiesOnly yes\n")
        config.writeText(
            """
            Include ${File(dir, "extra").path}
            Host box
              HostName 127.0.0.1
              Port ${server.port}
              User zeta
            Host 127.0.0.1
              IdentityFile ~/.ssh/k
            Host other
              HostName 127.0.0.1
              Port ${server.port}
              User zeta
              IdentityFile ~/.ssh/stranger
            Host nokey
              HostName 127.0.0.1
              IdentityFile ~/.ssh/missing
            Host jump
              HostName 10.0.0.1
              ProxyJump box
              IdentityFile ~/.ssh/k
            Host *
              ServerAliveInterval 30
            """
                .trimIndent(),
        )
    }

    @After
    fun tearDown() {
        server.stop(true)
        home.deleteRecursively()
    }

    @Test
    fun aliasAndAddressLogInWithTheConfiguredKey() {
        val links = SshLinks(auth)
        val route = auth.resolve("box", null)
        assertEquals("zeta@127.0.0.1:${server.port}", route.target.toString())
        assertEquals("k", route.keys)
        val alias = links.probe("box", null, "anything")
        assertNull(alias.problem)
        assertEquals("ok\n", alias.output?.stdout)
        assertNotNull(alias.hostKey)
        // 写明用户和端口的地址：用户、端口照写的，私钥照配置里那个主机的
        val address = links.probe("zeta@127.0.0.1:${server.port}", alias.hostKey, "anything")
        assertNull(address.problem)
        assertEquals("ok\n", address.output?.stdout)
    }

    @Test
    fun keyTheHostDoesNotTrustIsExplained() {
        val result = SshLinks(auth).probe("other", null, "anything")
        assertNull(result.output)
        assertTrue(result.problem, result.problem!!.contains("不认这台电脑 ~/.ssh 里的私钥（stranger）"))
    }

    @Test
    fun chosenKeyReplacesTheConfiguredOne() {
        assertEquals(listOf("~/.ssh/k", "~/.ssh/stranger"), auth.keys())
        assertEquals("k", auth.keyName("~/.ssh/k"))
        // 配置里给 other 的是对方不认的 stranger，选了 k 就只用 k
        val links = SshLinks(auth)
        val chosen = links.probe("other", null, "anything", key = "~/.ssh/k")
        assertNull(chosen.problem)
        assertEquals("ok\n", chosen.output?.stdout)
        // 选了对方不认的那把：配置里的好钥匙也不再试
        val wrong = links.probe("box", null, "anything", key = "~/.ssh/stranger")
        assertTrue(wrong.problem, wrong.problem!!.contains("（stranger）"))
        assertTrue(
            assertThrows(SshProblem::class.java) { auth.resolve("box", "~/.ssh/gone") }
                .message!!
                .contains("选的私钥 gone 不在了"),
        )
    }

    @Test
    fun missingKeysAndJumpsAreRefusedBeforeConnecting() {
        assertTrue(
            assertThrows(SshProblem::class.java) { auth.resolve("nokey", null) }
                .message!!
                .contains("没有「nokey」要用的私钥"),
        )
        assertTrue(
            assertThrows(SshProblem::class.java) { auth.resolve("jump", null) }
                .message!!
                .contains("跳板"),
        )
        assertTrue(
            assertThrows(SshProblem::class.java) { auth.resolve("bad name", null) }
                .message!!
                .contains("~/.ssh/config 里的别名"),
        )
    }

    @Test
    fun configHostsSkipWildcards() {
        assertEquals(listOf("box", "127.0.0.1", "other", "nokey", "jump"), auth.configHosts())
    }

    @Test
    fun parseKeepsEveryIdentityFileInOrder() {
        val options =
            OpenSshConfigAuth.parse(
                "user Alex Ting\nhostname h\nidentityfile ~/.ssh/a\nIdentityFile ~/.ssh/b\n\n",
            )
        assertEquals(listOf("Alex Ting"), options["user"])
        assertEquals(listOf("~/.ssh/a", "~/.ssh/b"), options["identityfile"])
    }

    private fun keygen(file: File) {
        val process =
            ProcessBuilder(
                    "ssh-keygen",
                    "-q",
                    "-t",
                    "ed25519",
                    "-N",
                    "",
                    "-C",
                    "test",
                    "-f",
                    file.path,
                )
                .redirectErrorStream(true)
                .start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
    }

    /** 私钥文件 [file] 旁边 .pub 里公钥的指纹。 */
    private fun fingerprint(file: File): String =
        SshKey.fingerprint(
            Base64.getDecoder().decode(File(file.path + ".pub").readText().split(' ')[1]),
        )

    /** 只认指纹是 [trusted] 的客户端，什么命令都回一行 ok。 */
    private fun server(trusted: String): SshServer =
        SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider =
                KeyPairProvider.wrap(KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256))
            setPublickeyAuthenticator { _, key, _ -> KeyUtils.getFingerPrint(key) == trusted }
            setPasswordAuthenticator(null)
            setKeyboardInteractiveAuthenticator(null)
            setCommandFactory { _, _ -> Ok() }
            start()
        }

    private class Ok : Command {
        private lateinit var output: OutputStream
        private var exit: ExitCallback? = null

        override fun setInputStream(`in`: java.io.InputStream) = Unit

        override fun setOutputStream(out: OutputStream) {
            output = out
        }

        override fun setErrorStream(err: OutputStream) = Unit

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            output.write("ok\n".toByteArray())
            output.flush()
            exit?.onExit(0)
        }

        override fun destroy(channel: ChannelSession) = Unit
    }
}
