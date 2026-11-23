package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 聊天页的对话走一整条链路：[Conversation] 经 [ConvLinks] 在临时 HOME 上起真的 zeta-helper serve，核心起 zeta_host.sh，
 * 托管脚本起按剧本回话的假 Claude（tools/fake-claude），回答、标题、一轮的结束一路推回来照到对话上。 只换掉怎么连上主机（这里没有 Linux
 * 环境，直接在本机起），其余和手机上一样。协议本身的那条见 conv.ConvEndToEndTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ConversationEndToEndTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val home = Files.createTempDirectory("zeta-chat").toFile().canonicalFile

    /** 核心的套接字放在短路径下（unix 套接字路径有长度上限）。 */
    private val coreDir = Files.createTempDirectory("zc").toFile().canonicalFile

    private val shell =
        object : LinuxShell {
            override val home: String = this@ConversationEndToEndTest.home.path

            override fun start(command: String): Process =
                ProcessBuilder("bash", "-c", command)
                    .directory(this@ConversationEndToEndTest.home)
                    .apply {
                        environment()["HOME"] = home
                        environment()[AppVariant.LINUX_NAME_ENV] = NAME
                        environment()["ZETA_CORE_DIR"] = coreDir.path
                    }
                    .start()

            override fun kill(process: Process) = process.destroy()
        }

    private val serves = mutableListOf<HelperServe>()

    @After
    fun cleanup() {
        serves.forEach { it.close() }
        removeTestDir(home, coreDir)
    }

    @Test
    fun aTurnThroughTheCore() {
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

        val hub = Fixtures.readyHub(app)
        val program = TestHelper.program(HelperProgram.ZETA_HELPER, NAME)
        hub.convLinks.connect = { backend, onMessage, onProblem ->
            val serve =
                HelperServe(
                    name = "conv-$backend",
                    open = { HelperServe.open(shell, program) },
                    subscriptions = emptyList(),
                    onMessage = onMessage,
                    onProblem = onProblem,
                    backoff = { 0L },
                )
            serves += serve
            object : ConvConnection, ServeRequests by serve {
                override fun close() = serve.close()
            }
        }

        val chat = Conversation(hub, home.path, sessionId = null, title = "", historyFile = null)
        chat.send("你好，介绍一下自己")
        awaitCore(60_000) {
            chat.items.size == 3 && !chat.busy && chat.title == "假 Claude 起的标题"
        }
        assertEquals("你好，介绍一下自己", (chat.items[0] as UserItem).text)
        assertEquals(
            "想一想",
            ((chat.items[1] as WorkItem).steps.single() as ThinkingStep).text,
        )
        assertEquals("你好，我是按剧本回话的假 Claude", (chat.items[2] as AnswerItem).text)
        assertNotNull(chat.sessionId)
        assertEquals(true, chat.isProcessAlive)

        // 结束进程（conv.end）：核心那边进程没了，这边放开
        chat.shutdown()
        awaitCore(30_000) { !chat.isProcessAlive && !chat.busy }
    }

    private companion object {
        const val NAME = "zeta-dev"
    }
}
