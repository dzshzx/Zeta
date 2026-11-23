package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 远程主机的清单、连接检查的结果、ssh 那行命令怎么拼（经一个假 ssh 真跑一遍，看引号过两层 shell 后还对不对）。 */
class RemoteHostsTest {
    private val temp = Files.createTempDirectory("zeta-remote").toFile()

    @After
    fun tearDown() {
        removeTestDir(temp)
    }

    @Test
    fun hostsRoundTripAndBadEntriesAreSkipped() {
        val hosts =
            listOf(
                RemoteHost(
                    "r-00000001",
                    "工作站",
                    "dev-box",
                    "/home/user",
                    "/home/user/workspace",
                    hostname = "DEVBOX",
                ),
                RemoteHost("r-00000002", null, "me@example.com", "/root", "/root"),
            )
        assertEquals(hosts, RemoteHosts.decode(RemoteHosts.encode(hosts)))
        val mixed =
            """[
            {"id":"r-1","name":"","target":"a","home":"/h"},
            {"id":"local","name":"x","target":"b","home":"/h"},
            {"id":"r-2","name":"y","target":"-oProxyCommand=x","home":"/h"},
            {"id":"r-3","name":"z","target":"c","home":"relative"},
            {"id":"r-1","name":"dup","target":"d","home":"/h"},
            "junk"
        ]"""
        // 没名字的不算起了名字，没有默认目录的用家目录；编号不对、地址像选项、家目录不是绝对路径、编号重复的都丢掉
        assertEquals(listOf(RemoteHost("r-1", null, "a", "/h", "/h")), RemoteHosts.decode(mixed))
        assertEquals(emptyList<RemoteHost>(), RemoteHosts.decode("not json"))
        // 直连的：地址要是 用户@主机[:端口]，要有记下的主机指纹
        val key = RemoteHosts.encodeKey(com.dzshzx.zeta.core.ssh.SshKey.generate().blob)
        val direct =
            RemoteHost(
                "r-3",
                "vps",
                "me@example.com:2222",
                "/root",
                "/root",
                RemoteHost.Via.Direct,
                key,
            )
        assertEquals(listOf(direct), RemoteHosts.decode(RemoteHosts.encode(listOf(direct))))
        // 直连的地址不能是别名（Zeta 直连不读 ~/.ssh/config）
        val alias = direct.copy(id = "r-7", target = "dev-box")
        assertEquals(emptyList<RemoteHost>(), RemoteHosts.decode(RemoteHosts.encode(listOf(alias))))
        // 以前存的 key（桌面版另选的私钥）不再认：照常读出这台，下次存时没有这一项
        val withKey =
            """[{"id":"r-3","name":"vps","target":"me@example.com:2222","home":"/root","via":"direct","hostKey":"$key","key":"~/.ssh/dev"}]"""
        assertEquals(listOf(direct), RemoteHosts.decode(withKey))
        assertFalse("\"key\"" in RemoteHosts.encode(RemoteHosts.decode(withKey)))
        val broken =
            """[
            {"id":"r-4","target":"me@example.com","home":"/h","via":"direct"},
            {"id":"r-5","target":"dev box","home":"/h","via":"direct","hostKey":"$key"},
            {"id":"r-6","target":"me@example.com","home":"/h","via":"direct","hostKey":"not-a-key"}
        ]"""
        assertEquals(emptyList<RemoteHost>(), RemoteHosts.decode(broken))
        // 经 Debian 的不带主机指纹（那边的 known_hosts 管）
        assertEquals(
            null,
            RemoteHosts.decode("""[{"id":"r-7","target":"dev-box","home":"/h","hostKey":"$key"}]""")
                .single()
                .hostKey,
        )
        assertEquals(emptyList<RemoteHost>(), RemoteHosts.decode(null))
    }

    /** 置顶按主机分开记：手机上的就是会话号（和以前存的一样），远程主机的带编号；在一台上查不到不会动别的主机的置顶。 */
    @Test
    fun pinKeysKeepHostsApart() {
        val local = SessionSummary("s1", "/f", "/w", "", "", 0L, backend = Backends.LOCAL)
        val remote = SessionSummary("s1", "/f", "/w", "", "", 0L, backend = "r-1")
        assertEquals("s1", local.pinKey)
        assertEquals("r-1/s1", remote.pinKey)
        assertEquals("s1", SessionSummary.pinnedId("s1", Backends.LOCAL))
        assertNull(SessionSummary.pinnedId("r-1/s1", Backends.LOCAL))
        assertEquals("s1", SessionSummary.pinnedId("r-1/s1", "r-1"))
        assertNull(SessionSummary.pinnedId("s1", "r-1"))
        assertNull(SessionSummary.pinnedId("r-2/s1", "r-1"))
    }

    /** 设备的名字：起了名字用它，没起时用设备自己报的主机名，还没记下时是地址。早先没起名字时存的是地址，读回来不算起了名字。 起的名字不能和别的设备现在的名字重复，留空是不起。 */
    @Test
    fun namesFallBackToTheDevicesOwn() {
        assertEquals(
            "工作站",
            RemoteHost("r-1", "工作站", "dev-box", "/h", "/h", hostname = "DEVBOX").name,
        )
        assertEquals(
            "DEVBOX",
            RemoteHost("r-1", null, "dev-box", "/h", "/h", hostname = "DEVBOX").name,
        )
        assertEquals("dev-box", RemoteHost("r-1", null, "dev-box", "/h", "/h").name)
        val shown = RemoteHosts.displayTarget("ssh://me@example.com:2222")
        val old =
            """[{"id":"r-1","name":"dev-box","target":"dev-box","home":"/h"},{"id":"r-2","name":"$shown","target":"ssh://me@example.com:2222","home":"/h"}]"""
        assertEquals(listOf(null, null), RemoteHosts.decode(old).map { it.customName })
        val others = listOf("工作站", "Xiaomi 17")
        assertNull(RemoteHosts.nameProblem("vps", others))
        assertNull(RemoteHosts.nameProblem("  ", others))
        assertTrue(RemoteHosts.nameProblem("工作站", others) != null)
        assertTrue(RemoteHosts.nameProblem(" Xiaomi 17 ", others) != null)
        assertTrue(RemoteHosts.nameProblem("x".repeat(41), others) != null)
    }

    @Test
    fun targetsThatSshCouldMisreadAreRejected() {
        for (ok in listOf("dev-box", "me@example.com", "host.example:2222", "[::1]")) assertTrue(
            ok,
            DebianSshShell.validTarget(ok),
        )
        for (bad in
            listOf("", "-oProxyCommand=sh", "a b", "a'b", "a\"b", "a\\b", "a\nb")) assertFalse(
            bad,
            DebianSshShell.validTarget(bad),
        )
    }

    /** 地址按连法换写法：直连要写明用户；交给 OpenSSH 的端口放进 ssh://（它不认 主机:端口）；别名、主机名只能经 Debian。 两种连法之间来回换，地址不变样。 */
    @Test
    fun targetsConvertBetweenVias() {
        val debian = RemoteHost.Via.Debian
        val direct = RemoteHost.Via.Direct
        assertEquals("me@example.com", RemoteHosts.targetFor(" me@example.com ", debian))
        assertEquals("me@example.com", RemoteHosts.targetFor("me@example.com", direct))
        assertEquals(
            "ssh://me@example.com:2222",
            RemoteHosts.targetFor("me@example.com:2222", debian),
        )
        assertEquals(
            "me@example.com:2222",
            RemoteHosts.targetFor("ssh://me@example.com:2222", direct),
        )
        assertEquals("ssh://me@[::1]:22", RemoteHosts.targetFor("me@[::1]", debian))
        assertEquals("me@[::1]", RemoteHosts.targetFor("ssh://me@[::1]:22", direct))
        assertEquals("dev-box", RemoteHosts.targetFor("dev-box", debian))
        for (text in listOf("dev-box", "host.example:2222", "")) assertNull(
            text,
            RemoteHosts.targetFor(text, direct),
        )
        // 主机:端口 交给 OpenSSH 会被当成主机名的一部分；像选项的、带空白的不行
        for (text in listOf("host.example:2222", "-oProxyCommand=sh", "a b", "")) assertNull(
            text,
            RemoteHosts.targetFor(text, debian),
        )
        for (text in listOf("me@example.com", "me@example.com:2222", "me@[2001:db8::1]:2200")) {
            val viaDebian = RemoteHosts.targetFor(text, debian)!!
            assertEquals(text, RemoteHosts.targetFor(viaDebian, direct))
            assertEquals(text, RemoteHosts.displayTarget(viaDebian))
        }
        assertEquals("dev-box", RemoteHosts.displayTarget("dev-box"))
    }

    /** 换网络时关掉 Debian 里各台的 ssh 主连接：每台各调一次 ssh -O exit，ControlPath 和平常连的一样；ssh 报错也以 0 结束。 */
    @Test
    fun closingMastersAsksSshForEachTarget() {
        val bin = File(temp, "bin").apply { mkdirs() }
        val log = File(temp, "argv")
        File(bin, "ssh").apply {
            writeText(
                "#!/bin/bash\nprintf '%s ' \"\$@\" >> \"${log.path}\"\necho >> \"${log.path}\"\necho 'Control socket connect: No such file' >&2\nexit 255\n",
            )
            setExecutable(true)
        }
        val process =
            ProcessBuilder(
                    "bash",
                    "-c",
                    DebianSshShell.closeMasters(listOf("dev-box", "ssh://me@example.com:2222")),
                )
                .apply {
                    environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
                }
                .redirectErrorStream(true)
                .start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertEquals("", out)
        val controlPath = DebianSshShell.Options.single { it.startsWith("ControlPath=") }
        assertEquals(
            listOf(
                "-o $controlPath -O exit -- dev-box",
                "-o $controlPath -O exit -- ssh://me@example.com:2222",
            ),
            log.readLines().map { it.trim() },
        )
    }

    @Test
    fun probeResults() {
        fun probe(stdout: String, code: Int = 0, stderr: String = "") =
            RemoteHosts.parseProbe(CommandResult(code, stdout, stderr))
        val ok =
            "欢迎\nzeta_home /home/user\nzeta_claude 2.1.329 (Claude Code)\nzeta_system Linux x86_64\n"
        assertEquals(RemoteHosts.Probe.Ok("/home/user", "2.1.329", "Linux x86_64"), probe(ok))
        assertEquals(
            RemoteHosts.Probe.Ok("/h", "2.1.329", "Linux aarch64"),
            probe("zeta_home /h\nzeta_claude 2.1.329\nzeta_system Linux aarch64\n"),
        )
        // 主机名查到了就带上，查不到（空的）是 null
        assertEquals(
            "DEVBOX",
            (probe(
                    "zeta_home /h\nzeta_claude 2.1.329\nzeta_system Linux aarch64\nzeta_hostname DEVBOX\n",
                )
                    as RemoteHosts.Probe.Ok)
                .hostname,
        )
        assertNull(
            (probe("zeta_home /h\nzeta_claude 2.1.329\nzeta_system Linux aarch64\nzeta_hostname \n")
                    as RemoteHosts.Probe.Ok)
                .hostname,
        )
        assertEquals(
            RemoteHosts.Probe.Failed("对方不认手机 Debian 里的 SSH 密钥（Permission denied）"),
            probe(
                "",
                RemoteShell.CONNECTION_FAILED,
                "me@example.com: Permission denied (publickey).\n",
            ),
        )
        assertTrue(
            (probe("zeta_home /h\nzeta_claude \nzeta_system Linux x86_64\n")
                    as RemoteHosts.Probe.Failed)
                .reason
                .contains("claude"),
        )
        // zeta-helper 只有 Linux 的 arm64、amd64 两份
        for (system in listOf("Linux riscv64", "Darwin arm64", "")) {
            val failed =
                probe("zeta_home /h\nzeta_claude 2.1.329\nzeta_system $system\n")
                    as RemoteHosts.Probe.Failed
            assertTrue(failed.reason, failed.reason.contains("arm64、x86-64 的 Linux"))
        }
        assertEquals(RemoteHosts.Probe.Failed("连接超时"), probe("", -1, CommandResult.TIMEOUT))
        assertEquals(
            "找不到这台设备（地址解析失败）",
            RemoteHosts.sshProblem(
                "ssh: Could not resolve hostname nope: Name or service not known",
            ),
        )
        // 直连的原因已经是一句话
        assertEquals("网络不通", RemoteHosts.sshProblem("前面的输出\nzeta-ssh: 网络不通\n"))
    }

    /** 在临时 HOME 上用本机 bash 跑命令的一台 Linux；[broken] 时扮进不去 Debian（su 被拒）。 */
    private fun shell(broken: Boolean = false) =
        object : LinuxShell {
            override val home: String = temp.path

            override fun start(command: String): Process =
                ProcessBuilder(
                        "bash",
                        "-c",
                        if (broken) "echo 'su: permission denied' >&2; exit 1" else command,
                    )
                    .apply { environment()["HOME"] = temp.path }
                    .start()

            override fun kill(process: Process) {
                process.destroyForcibly()
            }
        }

    @Test
    fun aliasesComeFromHostLinesWithoutPatterns() {
        val config =
            """
            Host personal-vps
              HostName 1.2.3.4
            host dev-box dev-box-gate
            Host *
              ConnectTimeout 30
            Host *.example.com !bad
            Host dev-box
            Hostname not-a-host-line
            """
                .trimIndent()
        File(temp, ".ssh").mkdirs()
        File(temp, ".ssh/config").writeText(config)
        File(temp, ".ssh/config.local").writeText("Host extra personal-vps\n")
        assertEquals(
            listOf("personal-vps", "dev-box", "dev-box-gate", "extra"),
            RemoteHosts.readAliases(shell()),
        )
    }

    /** 进不去 Debian 时是失败、带着原因，不当成没有别名；没有 ~/.ssh/config 才是没有。 */
    @Test
    fun aliasesThatCannotBeReadAreAFailure() {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                RemoteHosts.readAliases(shell(broken = true))
            }
        assertEquals("su: permission denied", failure.message)
        assertEquals(emptyList<String>(), RemoteHosts.readAliases(shell()))
    }

    /**
     * 拼出来的那行交给本机的 bash（扮手机的 Debian），bash 起的是一个假 ssh：选项和目的地之后的那一段交给 sh -c（扮远端的登录 shell）。
     * 命令里的空白、单引号、双引号、$ 都要原样到达远端的 bash，环境变量要带过去。登录的读那边的登录配置，不登录的（startPlain）不读。
     */
    @Test
    fun commandSurvivesBothShells() {
        File(temp, ".bash_profile").writeText("ZETA_PROFILE=read\n")
        val bin = File(temp, "bin").apply { mkdirs() }
        val log = File(temp, "argv")
        File(bin, "ssh").apply {
            writeText(
                """
                #!/bin/bash
                printf '%s\n' "${'$'}@" > "${log.path}"
                while [ ${'$'}# -gt 0 ]; do case "${'$'}1" in -T) shift ;; -o) shift 2 ;; --) shift; break ;; *) break ;; esac; done
                shift
                exec sh -c "${'$'}*"
                """
                    .trimIndent() + "\n",
            )
            setExecutable(true)
        }
        val tricky = "a b'c\"d\$e"
        val command =
            "printf '%s|' \"\$ZETA_NAME\" \"\$TZ\" " +
                LinuxEnv.quote(tricky) +
                " \"\$0\" \"\${ZETA_PROFILE:-not-read}\""
        for ((login, profile) in listOf(true to "read", false to "not-read")) {
            val line =
                DebianSshShell.sshCommand(
                    "me@example.com",
                    RemoteShell.remoteCommand(command, "zeta-dev", "Asia/Shanghai", login),
                )
            val process =
                ProcessBuilder("bash", "-c", line)
                    .apply {
                        environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
                        environment()["HOME"] = temp.path
                    }
                    .redirectErrorStream(true)
                    .start()
            val out = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            assertEquals(
                "login=$login",
                "zeta-dev|Asia/Shanghai|$tricky|bash|$profile|",
                out.lines().last { it.isNotBlank() },
            )
        }
        // 目的地在 -- 之后，ssh 不会把它当选项
        val argv = log.readLines()
        assertEquals("me@example.com", argv[argv.indexOf("--") + 1])
        assertTrue(argv.contains("BatchMode=yes"))
    }
}
