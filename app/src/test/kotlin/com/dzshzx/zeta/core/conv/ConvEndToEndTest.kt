package com.dzshzx.zeta.core.conv

import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.HelperProgram
import com.dzshzx.zeta.core.HelperServe
import com.dzshzx.zeta.core.LinuxShell
import com.dzshzx.zeta.core.TestHelper
import com.dzshzx.zeta.core.ThoughtItem
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.removeTestDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 整条链路（照 helper/conv_e2e_test.go）：临时 HOME 上经 [HelperServe] 起真的 zeta-helper serve，[ConvClient] 发
 * conv.*，核心起 zeta_host.sh，托管脚本起按剧本回话的假
 * Claude（tools/fake-claude），回答一路推回来。用测试版的目录名（zeta-dev）：只有它的托管脚本认假 Claude 的剧本。
 */
class ConvEndToEndTest {
    private val home = Files.createTempDirectory("zeta-conv").toFile().canonicalFile

    /** 核心的套接字放在短路径下（unix 套接字路径有长度上限）。 */
    private val coreDir = Files.createTempDirectory("zc").toFile().canonicalFile

    private val shell =
        object : LinuxShell {
            override val home: String = this@ConvEndToEndTest.home.path

            override fun start(command: String): Process =
                ProcessBuilder("bash", "-c", command)
                    .directory(this@ConvEndToEndTest.home)
                    .apply {
                        environment()["HOME"] = home
                        environment()[AppVariant.LINUX_NAME_ENV] = NAME
                        environment()["ZETA_CORE_DIR"] = coreDir.path
                    }
                    .start()

            override fun kill(process: Process) = process.destroy()
        }

    private var serve: HelperServe? = null

    @After
    fun cleanup() {
        serve?.close()
        removeTestDir(home, coreDir)
    }

    private class Events : ConvListener {
        val turns = LinkedBlockingQueue<ConvTurn>()

        override fun changed(conv: CoreConversation) {}

        override fun turn(conv: CoreConversation, turn: ConvTurn) {
            turns.put(turn)
        }
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "30 秒内没等到：$what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun conversationThroughTheCore() {
        for (tool in listOf("jq", "bash")) {
            val found =
                System.getenv("PATH").orEmpty().split(':').any { File(it, tool).canExecute() }
            check(found) { "假 Claude 要 $tool，PATH 上没有" }
        }
        val fake = File(home, ".config/$NAME/fake-claude").apply { mkdirs() }
        File("../tools/fake-claude/fake-claude.sh")
            .copyTo(File(fake, "fake-claude.sh"))
            .setExecutable(true)
        File(fake, "scenario.jsonl")
            .writeText(
                """{"title":"假 Claude 起的标题","steps":[{"thinking":"想一想"},""" +
                    """{"text":"你好，我是按剧本回话的假 Claude","chunk":4,"delay":5}]}""" +
                    "\n",
            )

        val events = Events()
        lateinit var client: ConvClient
        val program = TestHelper.program(HelperProgram.ZETA_HELPER, NAME)
        val s =
            HelperServe(
                name = "conv-e2e",
                open = { HelperServe.open(shell, program) },
                subscriptions = emptyList(),
                onMessage = { client.handle(it) },
                onProblem = { if (it == null) client.connected() else client.disconnected() },
                backoff = { 0L },
            )
        serve = s
        client = ConvClient(s, events)

        val conv = client.open(ConvSpec(cwd = home.path, options = ConvOptions(autoTitle = true)))
        waitFor("打开后的整份状态") { conv.view.connected && conv.view.state.cwd == home.path }

        conv.send("你好，介绍一下自己")
        assertEquals(ConvTurn(false), events.turns.poll(30, TimeUnit.SECONDS))
        val finished = events.turns.poll(30, TimeUnit.SECONDS)
        assertEquals(ConvTurn.Outcome.Done, finished?.outcome)
        waitFor("标题") { conv.view.state.title == "假 Claude 起的标题" }

        val items = conv.view.items
        assertEquals(3, items.size)
        assertEquals("你好，介绍一下自己", (items[0] as UserItem).text)
        assertEquals("想一想", (items[1] as ThoughtItem).text)
        assertEquals(AnswerItem(items[2].id, "你好，我是按剧本回话的假 Claude", false), items[2])
        val state = conv.view.state
        assertFalse(state.busy)
        assertEquals(true, state.process)
        assertNotNull(state.session)

        conv.end()
        waitFor("进程结束") { !conv.view.state.process }
        conv.close()
    }

    private companion object {
        const val NAME = "zeta-dev"
    }
}
