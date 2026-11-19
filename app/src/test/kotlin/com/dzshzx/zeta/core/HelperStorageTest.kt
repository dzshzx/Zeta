package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * zeta_helper.py 的 storage / storage-sessions / clean：在临时 HOME 和临时“/tmp”（ZETA_STORAGE_TEST_TMP）上
 * 用造出来的数据真跑 python3，不碰任何人真实的 ~/.claude 和 /tmp。
 * 重点是删除的边界：只删预期那一层、不跟符号链接（链接本身和它指向的外面都不动）、正在进行的会话的一律不删。
 */
class HelperStorageTest {
    private lateinit var home: File
    private lateinit var outside: File
    private lateinit var tmp: File
    private val helper = File("src/main/assets/zeta_helper.py").absoluteFile
    private val processes = ArrayList<Process>()
    private val claude get() = File(home, ".claude")
    private val project get() = File(claude, "projects/-workspace-test")

    // 三天前：比所有“刚写过”“一天内动过”的门槛都早
    private val oldSeconds = System.currentTimeMillis() / 1000 - 3 * 86400

    private val alive = "aaaaaaaa-1111-4222-8333-444444444444"
    private val gone = "dddddddd-1111-4222-8333-444444444444"
    private val gone2 = "eeeeeeee-1111-4222-8333-444444444444"
    private val kept = "cccccccc-1111-4222-8333-444444444444"
    private val running = "ffffffff-1111-4222-8333-444444444444"

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 python3、find、touch、mkfifo", File("/proc/self/stat").exists() &&
            runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        home = Files.createTempDirectory("zeta-storage-home").toFile().canonicalFile
        outside = Files.createTempDirectory("zeta-storage-outside").toFile().canonicalFile
        tmp = Files.createTempDirectory("zeta-test-tmp").toFile().canonicalFile
        File(outside, "keep.txt").writeText("keep")
        File(outside, gone).mkdirs()
        File(outside, "$gone/inside.txt").writeText("keep")
        project.mkdirs()
        File(project, "$alive.jsonl").writeText("{\"type\":\"user\",\"cwd\":\"/workspace/test\",\"sessionId\":\"$alive\"}\n")
    }

    @After
    fun tearDown() {
        processes.forEach { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        for (dir in listOf(if (::home.isInitialized) home else null, if (::outside.isInitialized) outside else null, if (::tmp.isInitialized) tmp else null)) {
            dir?.deleteRecursively()
        }
    }

    private fun helper(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("python3", "-I", "-") + args).apply {
            environment()["HOME"] = home.path
            environment()["ZETA_STORAGE_TEST_TMP"] = tmp.path
            environment().remove("ZETA_NAME")
        }.start()
        process.outputStream.use { it.write(helper.readBytes()) }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS))
        assertFalse("助手报错：$err", err.contains("Traceback"))
        return process.exitValue() to out
    }

    private fun write(file: File, text: String = "x".repeat(5000)): File {
        file.parentFile!!.mkdirs()
        file.writeText(text)
        return file
    }

    private fun link(at: File, target: File) {
        at.parentFile!!.mkdirs()
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }

    /** 把这些路径（含里面的，链接只改链接本身）的修改时刻都拨到三天前。 */
    private fun age(vararg paths: File) {
        val command = listOf("find") + paths.map { it.path } + listOf("-exec", "touch", "-h", "-d", "@$oldSeconds", "{}", "+")
        assertEquals(0, ProcessBuilder(command).redirectErrorStream(true).start().waitFor())
    }

    /** 起一个一直等着标准输入的进程，命令行里带 [name]（比如 claude、npm）；返回它的 pid。 */
    private fun fakeProcess(name: String, cwd: File? = null): Long {
        val script = File(home, "bin/$name")
        if (!script.exists()) {
            write(script, "#!/bin/bash\nread -r line\n")
            script.setExecutable(true)
        }
        val process = ProcessBuilder(script.path).apply { if (cwd != null) directory(cwd) }.start()
        processes += process
        val pid = process.pid()
        repeat(100) {
            if (runCatching { File("/proc/$pid/cmdline").readText() }.getOrDefault("").contains(name)) return pid
            Thread.sleep(20)
        }
        return pid
    }

    /** 像 Claude Code 那样在 ~/.claude/sessions/<pid>.json 登记一个进程正在用会话 [session]。 */
    private fun register(pid: Long, session: String) {
        write(File(claude, "sessions/$pid.json"), "{\"pid\":$pid,\"sessionId\":\"$session\",\"cwd\":\"/workspace/test\"}")
    }

    private fun report(out: String, key: String): StorageUsage {
        val usage = Storage.parseReport(out).linux[key]
        assertNotNull("缺 $key", usage)
        return usage!!
    }

    private fun cleaned(out: String): CleanOutcome = Storage.parseCleaned(out)!!

    @Test
    fun residueRemovesOnlyLeftoversOfDeletedSessions() {
        // 还有记录的会话的东西都留着
        write(File(project, "$alive/tool-results/a.txt"))
        write(File(claude, "file-history/$alive/backup@v1"))
        write(File(claude, "todos/$alive-agent-$alive.json"))
        // 已删会话的
        write(File(project, "$gone/tool-results/b.txt"))
        write(File(claude, "file-history/$gone/backup@v1"))
        write(File(claude, "session-env/$gone/hook-1.sh"))
        write(File(claude, "tasks/$gone/1.json"))
        write(File(claude, "uploads/$gone/pic.png"))
        write(File(claude, "todos/$gone-agent-$gone.json"))
        // 已删会话的目录里有指到外面的链接：目录删掉，外面不动
        write(File(claude, "file-history/$gone2/backup@v1"))
        link(File(claude, "file-history/$gone2/escape"), outside)
        // 名字像会话 id 的“目录”本身是指到外面的链接：不删
        link(File(claude, "session-env/$gone2"), File(outside, gone))
        // 名字不是会话 id 的（团队任务清单）不动
        write(File(claude, "tasks/my-team/1.json"))
        // 应用说要保护的会话
        write(File(claude, "session-env/$kept/hook.sh"))
        age(home)
        // 刚写的不删
        write(File(claude, "file-history/$running/backup@v1"))

        val (code, out) = helper("storage", kept)
        assertEquals(0, code)
        val residue = report(out, "claude-residue")
        assertEquals(7, residue.items)
        assertTrue(residue.freeable > 0)
        assertEquals(setOf("file-history", "session-env", "tasks", "uploads", "todos", "projects"), residue.parts.map { it.name }.toSet())

        val (cleanCode, cleanOut) = helper("clean", "claude-residue", kept)
        assertEquals(0, cleanCode)
        val outcome = cleaned(cleanOut)
        assertEquals(7, outcome.removed)
        assertEquals(0, outcome.failed)
        for (path in listOf("projects/-workspace-test/$gone", "file-history/$gone", "session-env/$gone", "tasks/$gone", "uploads/$gone",
            "todos/$gone-agent-$gone.json", "file-history/$gone2")) {
            assertFalse("应删掉 $path", File(claude, path).exists())
        }
        for (path in listOf("projects/-workspace-test/$alive.jsonl", "projects/-workspace-test/$alive/tool-results/a.txt",
            "file-history/$alive/backup@v1", "todos/$alive-agent-$alive.json", "tasks/my-team/1.json", "session-env/$kept/hook.sh",
            "file-history/$running/backup@v1")) {
            assertTrue("应留着 $path", File(claude, path).exists())
        }
        assertTrue(Files.isSymbolicLink(File(claude, "session-env/$gone2").toPath()))
        assertTrue(File(outside, "keep.txt").exists())
        assertTrue(File(outside, "$gone/inside.txt").exists())
    }

    @Test
    fun symlinkedCategoryRootIsLeftAlone() {
        // ~/.claude/session-env 整个被换成指到外面的链接：这一类一项都不删
        link(File(claude, "session-env"), outside)
        age(home, outside)
        val (_, out) = helper("clean", "claude-residue")
        assertEquals(0, cleaned(out).removed)
        assertTrue(File(outside, "$gone/inside.txt").exists())
    }

    @Test
    fun liveSessionsAreProtectedEverywhere() {
        val pid = fakeProcess("claude")
        register(pid, running)
        write(File(claude, "session-env/$running/hook.sh"))
        write(File(claude, "debug/$running.txt"))
        write(File(claude, "debug/$gone.txt"))
        link(File(claude, "debug/latest"), File(claude, "debug/$running.txt"))
        write(File(claude, "image-cache/$running/1.png"))
        write(File(claude, "image-cache/$gone/1.png"))
        write(File(claude, "telemetry/1p_failed_events.$running.$alive.json"))
        write(File(claude, "telemetry/1p_failed_events.$gone.$alive.json"))
        // 快照：最早那个活着的进程启动之前建的可以删，之后建的（名字里的时刻）不删
        val before = (oldSeconds) * 1000
        val after = System.currentTimeMillis()
        write(File(claude, "shell-snapshots/snapshot-bash-$before-abc123.sh"))
        write(File(claude, "shell-snapshots/snapshot-bash-$after-def456.sh"))
        // Zeta 托管的对话：进程还在的不删，进程没了的删
        write(File(home, ".cache/zeta/hosts/live-host/pid"), "$pid\n")
        write(File(home, ".cache/zeta/hosts/live-host/out.jsonl"))
        val finished = ProcessBuilder("true").start().apply { waitFor() }.pid()
        write(File(home, ".cache/zeta/hosts/dead-host/pid"), "$finished\n")
        write(File(home, ".cache/zeta/hosts/dead-host/out.jsonl"))
        // 另一个包的目录不归这个包管
        write(File(home, ".cache/zeta-dev/hosts/other/pid"), "$finished\n")
        // 附件：进行中的会话提到的不删
        write(File(project, "$kept.jsonl"), "{\"type\":\"user\",\"message\":{\"content\":\"[Attached file: ${home.path}/.cache/zeta/files/abcd1234/pic.png]\"}}\n")
        write(File(home, ".cache/zeta/files/abcd1234/pic.png"))
        write(File(home, ".cache/zeta/files/0f0f0f0f/old.png"))
        write(File(home, ".cache/zeta/files/not-an-upload/x"))
        age(home)
        write(File(home, ".cache/zeta/files/12345678/just-attached.png"))

        assertEquals(0, cleaned(helper("clean", "claude-residue").second).removed)
        assertTrue(File(claude, "session-env/$running/hook.sh").exists())

        assertEquals(1, cleaned(helper("clean", "claude-debug").second).removed)
        assertTrue(File(claude, "debug/$running.txt").exists())
        assertFalse(File(claude, "debug/$gone.txt").exists())
        assertTrue(Files.isSymbolicLink(File(claude, "debug/latest").toPath()))

        assertEquals(1, cleaned(helper("clean", "claude-image-cache").second).removed)
        assertTrue(File(claude, "image-cache/$running/1.png").exists())
        assertEquals(1, cleaned(helper("clean", "claude-telemetry").second).removed)
        assertTrue(File(claude, "telemetry/1p_failed_events.$running.$alive.json").exists())

        assertEquals(1, cleaned(helper("clean", "claude-snapshots").second).removed)
        assertFalse(File(claude, "shell-snapshots/snapshot-bash-$before-abc123.sh").exists())
        assertTrue(File(claude, "shell-snapshots/snapshot-bash-$after-def456.sh").exists())

        assertEquals(1, cleaned(helper("clean", "zeta-hosts").second).removed)
        assertTrue(File(home, ".cache/zeta/hosts/live-host/out.jsonl").exists())
        assertFalse(File(home, ".cache/zeta/hosts/dead-host").exists())
        assertTrue(File(home, ".cache/zeta-dev/hosts/other/pid").exists())

        assertEquals(1, cleaned(helper("clean", "zeta-files", kept).second).removed)
        assertTrue(File(home, ".cache/zeta/files/abcd1234/pic.png").exists())
        assertFalse(File(home, ".cache/zeta/files/0f0f0f0f").exists())
        assertTrue(File(home, ".cache/zeta/files/not-an-upload/x").exists())
        assertTrue(File(home, ".cache/zeta/files/12345678/just-attached.png").exists())
    }

    @Test
    fun tmpKeepsWhatIsInUseRecentOrSpecial() {
        val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
        val pid = fakeProcess("claude")
        register(pid, running)
        write(File(tmp, "old.txt"))
        write(File(tmp, "old-dir/a/b.txt"))
        link(File(tmp, "old-dir-with-link/escape"), outside)
        write(File(tmp, "old-dir-with-link/c.txt"))
        link(File(tmp, "link-to-outside"), outside)
        write(File(tmp, "tmux-$uid/x"))
        File(tmp, "with-fifo").mkdirs()
        assertEquals(0, ProcessBuilder("mkfifo", File(tmp, "with-fifo/pipe").path).start().waitFor())
        write(File(tmp, "busy/work.txt"))
        val claudeTmp = File(tmp, "claude-$uid")
        write(File(claudeTmp, "-workspace-test/$running/tasks/1.output"))
        write(File(claudeTmp, "-workspace-test/$gone/tasks/1.output"))
        write(File(claudeTmp, "-workspace-old/$gone2/scratchpad/notes.md"))
        age(tmp)
        // 有进程在里面工作的目录（工作目录指向它）不删
        fakeProcess("sleeper", cwd = File(tmp, "busy"))
        write(File(tmp, "new.txt"))

        val (code, out) = helper("clean", "tmp")
        assertEquals(0, code)
        assertEquals(5, cleaned(out).removed)
        for (gone in listOf("old.txt", "old-dir", "old-dir-with-link", "claude-$uid/-workspace-test/$gone", "claude-$uid/-workspace-old")) {
            assertFalse("应删掉 $gone", File(tmp, gone).exists())
        }
        for (kept in listOf("new.txt", "tmux-$uid/x", "with-fifo/pipe", "busy/work.txt", "claude-$uid/-workspace-test/$running/tasks/1.output")) {
            assertTrue("应留着 $kept", File(tmp, kept).exists())
        }
        assertTrue(Files.isSymbolicLink(File(tmp, "link-to-outside").toPath()))
        assertTrue(File(outside, "keep.txt").exists())
    }

    @Test
    fun packageCachesSkipWhatMayBeRunning() {
        write(File(home, ".npm/_cacache/content-v2/sha512/aa"))
        write(File(home, ".npm/_logs/debug.log"))
        write(File(home, ".npm/_npx/123/node_modules/server/index.js"))
        write(File(home, ".cache/pip/http-v2/a/b"))
        write(File(home, ".cache/pip/wheels/c.whl"))
        age(home)
        assertEquals(2, cleaned(helper("clean", "pip").second).removed)
        assertFalse(File(home, ".cache/pip/http-v2").exists())
        assertTrue(File(home, ".cache/pip").isDirectory)

        // npm 刚启动时整类不清
        fakeProcess("npm")
        val busy = cleaned(helper("clean", "npm").second)
        assertEquals("npm", busy.busy)
        assertTrue(File(home, ".npm/_cacache/content-v2/sha512/aa").exists())
        processes.forEach { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        processes.clear()

        assertEquals(2, cleaned(helper("clean", "npm").second).removed)
        assertFalse(File(home, ".npm/_cacache").exists())
        assertFalse(File(home, ".npm/_logs").exists())
        assertTrue(File(home, ".npm/_npx/123/node_modules/server/index.js").exists())
    }

    @Test
    fun storageReportsProjectsAndOnlyTheCleanupSetting() {
        write(File(claude, "settings.json"), "{\"cleanupPeriodDays\": 7, \"env\": {\"API_KEY\": \"fake-token-1\"}}")
        write(File(project, "$gone/tool-results/b.txt"))
        val (code, out) = helper("storage")
        assertEquals(0, code)
        assertFalse("不输出设置里别的值", out.contains("fake-token-1"))
        val parsed = Storage.parseReport(out)
        assertTrue(parsed.complete)
        assertEquals(7, parsed.cleanupPeriodDays)
        assertEquals(listOf("-workspace-test"), parsed.projects.map { it.dir })
        assertEquals("/workspace/test", parsed.projects.single().cwd)
        assertEquals(1, parsed.projects.single().sessions)
        for (kind in StorageKind.entries.filter { it.inLinux }) assertNotNull("缺 ${kind.key}", parsed.linux[kind.key])
    }

    @Test
    fun storageSessionsCountsEachSessionWithItsDirectory() {
        write(File(project, "$alive/tool-results/a.txt"), "y".repeat(20000))
        write(File(project, "$kept.jsonl"), "{}\n")
        write(File(project, "memory/MEMORY.md"))
        val (code, out) = helper("storage-sessions", "-workspace-test", kept)
        assertEquals(0, code)
        val (sessions, rest) = Storage.parseSessions(out)
        assertEquals(listOf(alive, kept), sessions.map { it.id })
        assertTrue(sessions[0].bytes > sessions[1].bytes)
        assertTrue(sessions.single { it.id == kept }.live)
        assertFalse(sessions.single { it.id == alive }.live)
        assertTrue(rest > 0)
    }

    @Test
    fun badArgumentsAreRefusedAsAWhole() {
        write(File(outside, "x.jsonl"), "{}\n")
        link(File(claude, "projects/escape"), outside)
        for (args in listOf(
            listOf("storage-sessions", ".."),
            listOf("storage-sessions", "../../" + outside.name),
            listOf("storage-sessions", "escape"),
            listOf("clean", "apt"),
            listOf("clean", "nope"),
            listOf("clean", "tmp", "../x"),
            listOf("storage", "\$(id)"),
        )) {
            assertNotEquals("应拒绝 $args", 0, helper(*args.toTypedArray()).first)
        }
        assertTrue(File(outside, "x.jsonl").exists())
    }
}
