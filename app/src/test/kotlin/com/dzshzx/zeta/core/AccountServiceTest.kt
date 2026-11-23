package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 账号页背后的 [AccountService] 和添加账号的 [LoginFlow] 经核心做事（docs/core.md「账号与额度」）：对着临时 HOME 上真的 `zeta-helper
 * serve` 和应用里带的那份 claude-switch（本机架构的一份，第一次用时核心说没装，[AccountsCore] 装上再试）。登录状态是假的（邮箱用 example.com，
 * 令牌是随手写的字符串）；`claude auth login` 是一个照实测样子说话的假 claude（同 helper/accountslogin_test.go），不碰任何人真实的登录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AccountServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var dir: File
    private lateinit var serve: HelperServe
    private lateinit var hub: ConversationHub
    private lateinit var service: AccountService
    private val spawned = mutableListOf<Process>()

    /** 临时 HOME 上的 bash；PATH 只有系统目录，核心起登录命令时找到的是 ~/.local/bin 里的假 claude，不是本机真的。 */
    private val shell =
        object : LinuxShell {
            override val home: String
                get() = dir.path

            override fun start(command: String): Process {
                val builder = ProcessBuilder("bash", "-c", command).directory(dir)
                builder.environment().apply {
                    clear()
                    put("HOME", dir.path)
                    put("PATH", "/usr/bin:/bin")
                    put("LANG", "C.UTF-8")
                    put(AppVariant.LINUX_NAME_ENV, AppVariant.current.linuxName)
                }
                return builder.start()
            }

            override fun kill(process: Process) {
                process.destroyForcibly()
            }
        }

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 bash", File("/proc/self/stat").exists())
        dir = Files.createTempDirectory("zeta-accounts").toFile().canonicalFile
        fakeLogin("uuid-a", "a@example.com")
        fakeLoginCommand()
        serve =
            HelperServe(
                name = "accounts-test",
                open = { HelperServe.open(shell, TestHelper.program(HelperProgram.ZETA_HELPER)) },
                subscriptions = emptyList(),
                onMessage = {},
                onProblem = {},
            )
        hub = Fixtures.readyHub(app)
        service = hub.accounts(Backends.LOCAL)
        service.core =
            AccountsCore(
                TestHelper.program(HelperProgram.CLAUDE_SWITCH, AppVariant.current.linuxName),
                { method, params, timeoutMs -> serve.call(method, params, timeoutMs) },
                { shell },
            )
    }

    @After
    fun tearDown() {
        spawned.forEach { it.destroyForcibly() }
        if (::serve.isInitialized) serve.close()
        if (::dir.isInitialized) removeTestDir(dir)
    }

    private fun write(path: String, text: String) {
        File(dir, path).apply {
            parentFile.mkdirs()
            writeText(text)
        }
    }

    private fun fakeLogin(uuid: String, email: String) {
        write(
            ".claude.json",
            """{"oauthAccount":{"accountUuid":"$uuid","emailAddress":"$email"}}""",
        )
        write(
            ".claude/.credentials.json",
            """{"claudeAiOauth":{"accessToken":"fake-$uuid","refreshToken":"fake-r-$uuid","subscriptionType":"max"}}""",
        )
    }

    /**
     * ~/.local/bin 里的假 claude：auth login 打印网址和不换行的提示，逐行读授权码，「good#code」登录成 uuid-b 并以 0
     * 退出，别的码说没被接受、以 1 退出。进程号写进 ~/login.pid，好看它有没有被结束。
     */
    private fun fakeLoginCommand() {
        val dollar = '$'
        write(
            ".local/bin/claude",
            """
            #!/bin/bash
            [ "${dollar}1 ${dollar}2" = "auth login" ] || exit 2
            echo ${dollar}${dollar} > "${dollar}HOME/login.pid"
            echo "If the browser didn't open, visit:"
            echo "https://claude.ai/oauth/authorize?code=true&state=fake"
            printf "Paste code here if prompted > "
            while read -r code; do
              if [ "${dollar}code" = "good#code" ]; then
                echo '{"oauthAccount":{"accountUuid":"uuid-b","emailAddress":"b@example.com"}}' > "${dollar}HOME/.claude.json"
                echo '{"claudeAiOauth":{"accessToken":"fake-uuid-b","refreshToken":"fake-r-uuid-b","subscriptionType":"max"}}' > "${dollar}HOME/.claude/.credentials.json"
                echo "Login successful."
                exit 0
              fi
              echo "Couldn't log in: That code wasn't accepted. It may be incomplete, expired, already used, or from an earlier sign-in attempt." >&2
              exit 1
            done
            """
                .trimIndent() + "\n",
        )
        File(dir, ".local/bin/claude").setExecutable(true)
    }

    private val login
        get() = service.login

    private fun awaitIdle() = awaitMain(60_000) { service.working == null }

    /** 开始登录，等到核心给出授权网址。 */
    private fun startLogin(): LoginState.WaitingCode {
        login.start()
        awaitMain(60_000) { login.state !is LoginState.Starting }
        return login.state as? LoginState.WaitingCode
            ?: throw AssertionError("没等到授权网址：${login.state}")
    }

    @Test
    fun addSwitchAndRemoveAccountsThroughTheCore() {
        // 第一次读：核心说主机上还没装 claude-switch，装上再读
        service.refresh()
        awaitMain(60_000) { service.state != null }
        val first = service.state!!
        assertEquals("a@example.com", first.current?.email)
        // 没有切换记录时，默认配置目录里的那个号就是 claude-switch 固定的 claude
        assertEquals("claude", first.current?.id)
        val bin = File(dir, ".cache/${AppVariant.current.linuxName}/bin")
        assertTrue(bin.list().orEmpty().any { it.startsWith("claude-switch-") })

        // 添加账号：核心把在用的号存档并退出，再给出授权网址
        val waiting = startLogin()
        assertEquals("https://claude.ai/oauth/authorize?code=true&state=fake", waiting.url)
        awaitMain(60_000) { service.state?.loggedIn == false }
        assertEquals(listOf("claude"), service.state!!.all.map { it.id })
        // 少了 # 和后半段：这边先退回，登录接着等
        login.submit("only-half")
        assertEquals(waiting.url, (login.state as LoginState.WaitingCode).url)
        assertTrue((login.state as LoginState.WaitingCode).notice!!.contains("授权码不完整"))
        login.submit("good#code")
        awaitMain(60_000) { login.state == LoginState.Done }
        awaitMain(60_000) { service.state?.current?.email == "b@example.com" }
        assertEquals(listOf("claude-b", "claude"), service.state!!.all.map { it.id })

        // 换回 a
        service.switchTo(service.state!!.all.single { it.id == "claude" })
        awaitMain(60_000) { service.working == null && service.state?.current?.id == "claude" }
        assertNull(service.problem)
        assertEquals("a@example.com", service.state!!.current!!.email)

        // 移除 b 的存档
        service.remove(service.state!!.all.single { it.id == "claude-b" })
        awaitMain(60_000) { service.state?.all?.none { it.id == "claude-b" } == true }
        assertNull(service.problem)
    }

    /** 别处开着 Claude Code：切换、添加账号都不做，说的是核心给的原因（accounts.list 的 blocker）。 */
    @Test
    fun otherClaudeCodeProcessesBlockWithTheCoresReason() {
        // 不带记号（测试进程自己可能就在 Zeta 托管的 Claude Code 里，身上有 ZETA_HOST）
        val other =
            HostTesting.marked(ProcessBuilder("sleep", "300"), null).start().also { spawned += it }
        val stat = File("/proc/${other.pid()}/stat").readText()
        val start = stat.substringAfterLast(") ").split(' ')[19]
        write(
            ".claude/sessions/${other.pid()}.json",
            """{"pid":${other.pid()},"procStart":"$start","sessionId":"s-1","kind":"interactive","status":"busy"}""",
        )
        val blocker = "还有 1 个别处开着的 Claude Code（比如终端里的 claude），其中 1 个在进行"
        service.switchTo(ClaudeAccount("claude-b", "", "", "", "", null))
        awaitIdle()
        assertEquals(blocker, service.problem)
        assertEquals(blocker, service.state?.blocker)

        var opened = false
        service.prepareLogin { opened = true }
        awaitIdle()
        assertEquals(blocker, service.problem)
        assertTrue("被挡着时不该打开登录页", !opened)
    }

    /** 授权码不对：登录以失败结束（核心那边的登录命令也结束了），要重新开始。 */
    @Test
    fun aRejectedCodeEndsTheLogin() {
        startLogin()
        login.submit("bad#code")
        awaitMain(60_000) { login.state is LoginState.Failed }
        assertTrue(
            login.state.toString(),
            (login.state as LoginState.Failed).message.contains("没有被接受"),
        )
        val ended = assertThrows(ServeError::class.java) { service.core.loginCode("good#code") }
        assertEquals(ended.message, AccountsCore.NO_LOGIN, ended.code)
    }

    /** 取消：核心里在等授权码的登录命令被结束，再交授权码核心说没有在等的登录。 */
    @Test
    fun cancelEndsTheCoresLogin() {
        startLogin()
        val pid = File(dir, "login.pid").readText().trim()
        login.cancel()
        assertEquals(LoginState.Idle, login.state)
        assertTrue("登录命令没被结束", waitUntil { !File("/proc/$pid").exists() })
        val ended = assertThrows(ServeError::class.java) { service.core.loginCode("good#code") }
        assertEquals(ended.message, AccountsCore.NO_LOGIN, ended.code)
    }
}
