package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 重新打开旧对话时恢复模型和思考强度：会话记录里的完整模型 ID 对上选择器的选项、思考强度的取舍、zeta_helper.py 给出的最后一次回答。 */
class SessionChoiceTest {
    /** 2.1.328 的 initialize 应答里 models 的样子（只留用得到的键）。 */
    private val options = listOf(
        ModelOption("default", "Default (recommended)", "", "claude-opus-5-5"),
        ModelOption("opus", "Opus", "", "claude-opus-5-5"),
        ModelOption("opus[1m]", "Opus (1M context)", "", "claude-opus-5-5[1m]"),
        ModelOption("fable", "Fable", "", "claude-fable-5-2"),
        ModelOption("sonnet", "Sonnet", "", "claude-sonnet-5-5"),
        ModelOption("haiku", "Haiku", "", "claude-haiku-5-5"),
    )

    private fun model(recorded: String?, fallback: String?, list: List<ModelOption> = options) = SessionChoice.model(recorded, list, fallback)

    @Test
    fun recordedIdMapsToItsAlias() {
        assertEquals(SessionChoice.Model("fable"), model("claude-fable-5-2", fallback = null))
        assertEquals(SessionChoice.Model("sonnet"), model("claude-sonnet-5-5", fallback = "opus"))
        assertEquals(SessionChoice.Model("haiku"), model("claude-haiku-5-5", fallback = "fable"))
    }

    /** 默认模型本来就是记录里那个时照旧用默认，不换成显式的别名（Claude Code 的默认是 null，选择器里叫 default）。 */
    @Test
    fun defaultThatAlreadyResolvesToItStays() {
        assertEquals(SessionChoice.Model(null), model("claude-opus-5-5", fallback = null))
        assertEquals(SessionChoice.Model("opus"), model("claude-opus-5-5", fallback = "opus"))
        // 平时用 1M 窗口的：记录里的 ID 不带 [1m]，也算对上
        assertEquals(SessionChoice.Model("opus[1m]"), model("claude-opus-5-5", fallback = "opus[1m]"))
        // 默认写的是完整 ID
        assertEquals(SessionChoice.Model("claude-fable-5-2"), model("claude-fable-5-2", fallback = "claude-fable-5-2"))
    }

    /** 默认是别的模型时：取 resolvedModel 一字不差的那项，不取 default（Claude Code 换了默认模型这个对话就会跟着换），也不取 [1m] 变体。 */
    @Test
    fun exactAliasBeatsDefaultAndVariants() {
        assertEquals(SessionChoice.Model("opus"), model("claude-opus-5-5", fallback = "fable"))
        // 只有 default 和 [1m] 变体对得上时，先取变体
        val noPlainOpus = options.filter { it.value != "opus" }
        assertEquals(SessionChoice.Model("opus[1m]"), model("claude-opus-5-5", fallback = "fable", list = noPlainOpus))
        // 只有 default 对得上
        val onlyDefault = options.filter { !it.value.startsWith("opus") }
        assertEquals(SessionChoice.Model(null), model("claude-opus-5-5", fallback = "fable", list = onlyDefault))
    }

    /** 记录里的模型现在选不到（下线了、换了供应商）：用默认，带上要提示的模型名。 */
    @Test
    fun unavailableFallsBackWithNotice() {
        assertEquals(SessionChoice.Model("fable", missing = "claude-opus-4-8"), model("claude-opus-4-8", fallback = "fable"))
        assertEquals(SessionChoice.Model(null, missing = "gpt-6-astra"), model("gpt-6-astra", fallback = null))
        assertEquals(
            "这个对话上次用的 claude-opus-4-8 现在选不到了，先改用默认模型",
            SessionChoice.missingNotice("claude-opus-4-8", null, options),
        )
        assertEquals("这个对话上次用的 x 现在选不到了，先改用Fable", SessionChoice.missingNotice("x", "fable", options))
    }

    /** 没有记录、合成的回答、不知道选项对应什么模型时：照用默认，也不提示。 */
    @Test
    fun nothingToRestoreKeepsDefaultQuietly() {
        assertEquals(SessionChoice.Model("opus"), model(null, fallback = "opus"))
        assertEquals(SessionChoice.Model("opus"), model("", fallback = "opus"))
        assertEquals(SessionChoice.Model("opus"), model("<synthetic>", fallback = "opus"))
        // 还没起过进程，没有选项清单
        assertEquals(SessionChoice.Model(null), model("claude-fable-5-2", fallback = null, list = emptyList()))
        // 老版本的 Claude Code 不报 resolvedModel
        val bare = options.map { it.copy(resolved = null) }
        assertEquals(SessionChoice.Model(null), model("claude-fable-5-2", fallback = null, list = bare))
        // 选项的值本身就是完整 ID 时直接对上
        assertEquals(SessionChoice.Model("claude-fable-5-2"), model("claude-fable-5-2", fallback = null, list = bare + ModelOption("claude-fable-5-2", "", "")))
    }

    @Test
    fun effortOnlyKnownLevels() {
        for (level in listOf("low", "medium", "high", "xhigh", "max")) assertEquals(level, SessionChoice.effort(level))
        assertNull(SessionChoice.effort(null))
        assertNull(SessionChoice.effort("ultra"))
        assertNull(SessionChoice.effort(""))
    }

    /** 存在设置里的模型选项缓存：读回来和 Claude Code 报的一样，resolvedModel 也在。 */
    @Test
    fun optionsRoundTrip() {
        val reported = Json.parseToJsonElement(
            """[{"value":"default","resolvedModel":"claude-opus-5-5","displayName":"Default (recommended)","description":"Use the default model","supportsEffort":true},""" +
                """{"value":"fable","displayName":"Fable","description":"Fable 5.2"},{"displayName":"没有 value 的不要"}]""",
        ) as JsonArray
        val parsed = SessionChoice.parseOptions(reported)
        assertEquals(
            listOf(
                ModelOption("default", "Default (recommended)", "Use the default model", "claude-opus-5-5"),
                ModelOption("fable", "Fable", "Fable 5.2", null),
            ),
            parsed,
        )
        assertEquals(parsed, SessionChoice.parseOptions(Json.parseToJsonElement(SessionChoice.encodeOptions(parsed)) as JsonArray))
    }

    private fun user(uuid: String, text: String) = """{"type":"user","uuid":"$uuid","message":{"role":"user","content":"$text"}}"""

    private fun answer(uuid: String, model: String, effort: String?, sidechain: Boolean = false) =
        """{"type":"assistant","uuid":"$uuid",""" + (if (sidechain) """"isSidechain":true,""" else "") +
            (effort?.let { """"effort":"$it",""" } ?: "") +
            """"message":{"id":"m-$uuid","model":"$model","content":[{"type":"text","text":"答"}],"usage":{"input_tokens":3,"output_tokens":5}}}"""

    /** zeta_helper.py history 的第一行：主线上最后一次真实回答的模型和思考强度。跳过子代理、合成的回答，照剪切记号去掉被剪的。 */
    @Test
    fun helperReportsLastRealAnswer() {
        assumeTrue("要有 python3", runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val home = Files.createTempDirectory("zeta-helper-choice").toFile()
        try {
            val dir = File(home, ".claude/projects/-p").apply { mkdirs() }
            fun write(name: String, vararg lines: String) = File(dir, "$name.jsonl").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
            val plain = write(
                "plain",
                user("u1", "第一问"),
                answer("a1", "claude-opus-5-5", "high"),
                user("u2", "第二问"),
                answer("a2", "claude-fable-5-2", "xhigh"),
                answer("s1", "claude-haiku-5-5", "low", sidechain = true),
                """{"type":"assistant","uuid":"a3","isApiErrorMessage":true,"message":{"id":"m-a3","model":"<synthetic>","content":[{"type":"text","text":"API Error"}]}}""",
            )
            val cut = write(
                "cut",
                user("u1", "第一问"),
                answer("a1", "claude-opus-5-5", "high"),
                user("u2", "第二问"),
                answer("a2", "claude-fable-5-2", "xhigh"),
                """{"type":"last-prompt","explicit":true,"rewound":true,"cutUuid":"u2","leafUuid":"a1"}""",
            )
            // 老版本的记录不带 effort；只有合成回答的没有模型
            val old = write("old", user("u1", "第一问"), answer("a1", "claude-sonnet-5-5", null))
            val none = write(
                "none",
                user("u1", "第一问"),
                """{"type":"assistant","uuid":"a1","message":{"id":"m","model":"<synthetic>","content":[{"type":"text","text":"Prompt is too long"}]}}""",
            )

            assertEquals("claude-fable-5-2" to "xhigh", header(home, plain))
            assertEquals("claude-opus-5-5" to "high", header(home, cut))
            assertEquals("claude-sonnet-5-5" to null, header(home, old))
            assertEquals(null to null, header(home, none))
        } finally {
            home.deleteRecursively()
        }
    }

    private fun header(home: File, file: File): Pair<String?, String?> {
        val process = ProcessBuilder("python3", "-I", "-", "history", file.path).directory(home).apply {
            environment().clear()
            environment()["HOME"] = home.path
            environment()["PATH"] = System.getenv("PATH") ?: "/usr/bin:/bin"
            environment()["LANG"] = "C.UTF-8"
        }.start()
        process.outputStream.use { it.write(File("src/main/assets/zeta_helper.py").readBytes()) }
        val out = process.inputStream.bufferedReader().readText()
        process.errorStream.readBytes()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        val first = Json.parseToJsonElement(out.lineSequence().first()) as JsonObject
        assertEquals("zeta_history", first.str("type"))
        return first.str("model") to first.str("effort")
    }
}
