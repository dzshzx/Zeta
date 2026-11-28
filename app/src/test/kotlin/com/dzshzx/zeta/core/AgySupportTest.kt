package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.claude.ClaudeConfigCore
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.ui.setup.applyProbedHost
import com.dzshzx.zeta.ui.setup.probeSummary
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Antigravity CLI（agy）探测、环境安装脚本、默认工具设置、会话与对话切换、模型与账号配置的单元测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AgySupportTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val temp = Files.createTempDirectory("zeta-agy-test").toFile().canonicalFile

    @After
    fun tearDown() {
        removeTestDir(temp)
    }

    @Test
    fun envStatusAndInstallCliSteps() {
        val base = EnvStatus(base = true, tools = true, user = "zeta", claude = false, agy = false)
        assertFalse(base.complete)
        assertTrue(base.copy(claude = true).complete)
        assertTrue(base.copy(agy = true).complete)
        assertTrue(base.copy(claude = true, agy = true).complete)
        assertEquals(
            InstallStep.entries.filter { it != InstallStep.Agy },
            InstallCli.Claude.steps(),
        )
        assertEquals(
            InstallStep.entries.filter { it != InstallStep.Claude },
            InstallCli.Agy.steps(),
        )
        assertEquals(InstallStep.entries, InstallCli.Both.steps())
    }

    @Test
    fun androidSetupStatusReportsClaudeAndAgy() {
        val rootfs = File(temp, "rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "etc").mkdirs()
        for (tool in listOf("bin/bash", "usr/bin/setpriv", "usr/bin/sudo")) {
            File(rootfs, tool).apply {
                writeText("#!/bin/sh\n")
                setExecutable(true)
            }
        }
        File(rootfs, "etc/passwd").writeText("zeta:x:5000:5000::/home/zeta:/bin/bash\n")
        val localBin = File(rootfs, "home/zeta/.local/bin").apply { mkdirs() }
        val agyBin =
            File(localBin, "agy").apply {
                writeText("#!/bin/sh\n")
                setExecutable(true)
            }
        val bb =
            File(temp, "bb").apply {
                writeText("#!/bin/sh\ncmd=\$1; shift; exec \"\$cmd\" \"\$@\"\n")
                setExecutable(true)
            }
        val outOnlyAgy = runAsset("zeta_setup_android.sh", bb.path, "status", temp.path)
        assertTrue(outOnlyAgy, outOnlyAgy.contains("claude=0\n"))
        assertTrue(outOnlyAgy, outOnlyAgy.contains("agy=1\n"))

        File(localBin, "claude").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        agyBin.delete()
        val outOnlyClaude = runAsset("zeta_setup_android.sh", bb.path, "status", temp.path)
        assertTrue(outOnlyClaude, outOnlyClaude.contains("claude=1\n"))
        assertTrue(outOnlyClaude, outOnlyClaude.contains("agy=0\n"))
    }

    @Test
    fun debianAgySettingsHookMergePreservesExistingKeys() {
        TestDeps.require("jq", runCatching { ProcessBuilder("jq", "--version").start() }.isSuccess)
        val settingsFile = File(temp, "settings.json")
        settingsFile.writeText(
            """{"model":"gemini-2.5-pro","hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"custom-guard"}]}]}}""",
        )
        val debianScript = File("src/main/assets/zeta_setup_debian.sh").readText()
        assertTrue(debianScript.contains("https://antigravity.google/cli/install.sh"))
        assertTrue(debianScript.contains(".gemini/antigravity-cli/settings.json"))
        // 提取并运行 zeta_setup_debian.sh 里写 settings.json 的同一段 jq 合并逻辑（连跑两遍验证幂等）
        val hookCmd = "/home/zeta/.local/bin/zeta-helper session-start"
        val mergeScript =
            """
            set -eu
            settings=${LinuxEnv.quote(settingsFile.path)}
            hook_cmd=${LinuxEnv.quote(hookCmd)}
            current=$(cat "${'$'}settings")
            printf '%s\n' "${'$'}current" | jq --arg cmd "${'$'}hook_cmd" '
              .hooks = (.hooks // {}) |
              .hooks.SessionStart = (
                ((.hooks.SessionStart // []) | map(
                  if type == "object" and (.hooks | type) == "array" then
                    .hooks |= map(select((.command // "" | tostring | test("zeta(-dev)?-helper session-start")) | not))
                  else . end
                ) | map(select((type == "object" and (.hooks | type) == "array" and (.hooks | length) == 0) | not)))
                + [{"hooks": [{"type": "command", "command": ${'$'}cmd}]}]
              )
            ' > "${'$'}settings.tmp" && mv "${'$'}settings.tmp" "${'$'}settings"
            """
                .trimIndent()
        repeat(2) {
            val proc = ProcessBuilder("bash", "-c", mergeScript).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            assertEquals(out, 0, proc.waitFor())
        }
        val merged = Json.parseToJsonElement(settingsFile.readText()).jsonObject
        assertEquals("gemini-2.5-pro", merged.str("model"))
        val hooks = merged.obj("hooks")!!
        assertEquals(1, hooks.arr("PreToolUse")!!.size)
        val sessionStart = hooks.arr("SessionStart")!!
        assertEquals(1, sessionStart.size)
        assertEquals(
            hookCmd,
            sessionStart[0].jsonObject.arr("hooks")!![0].jsonObject.str("command"),
        )
    }

    @Test
    fun remoteHostProbeAcceptsAgyOnlyOrBothAndSetsDefaultHarness() {
        val agyOnly =
            RemoteHosts.parseProbe(
                CommandResult(
                    0,
                    "zeta_home /home/user\nzeta_claude \nzeta_agy 1.5.18\nzeta_system Linux x86_64\nzeta_hostname box\n",
                    "",
                ),
            )
        assertEquals(
            RemoteHosts.Probe.Ok(
                home = "/home/user",
                claude = "",
                system = "Linux x86_64",
                hostname = "box",
                agy = "1.5.18",
            ),
            agyOnly,
        )
        val okAgy = agyOnly as RemoteHosts.Probe.Ok
        assertTrue(probeSummary(okAgy).contains("Antigravity 1.5.18"))

        val both =
            RemoteHosts.parseProbe(
                CommandResult(
                    0,
                    "zeta_home /home/user\nzeta_claude 2.1.330\nzeta_agy antigravity 1.5.18\nzeta_system Linux aarch64\n",
                    "",
                ),
            ) as RemoteHosts.Probe.Ok
        assertEquals("2.1.330", both.claude)
        assertEquals("1.5.18", both.agy)
        assertTrue(probeSummary(both).contains("Claude Code 2.1.330"))
        assertTrue(probeSummary(both).contains("Antigravity 1.5.18"))

        val hub = Fixtures.readyHub(app)
        val addedAgy = applyProbedHost(hub, "user@example.com", RemoteHost.Via.Debian, okAgy)
        assertEquals(Harnesses.AGY, hub.settings.defaultHarness(addedAgy.id))

        val addedBoth = applyProbedHost(hub, "both@example.com", RemoteHost.Via.Debian, both)
        assertEquals(Harnesses.CLAUDE, hub.settings.defaultHarness(addedBoth.id))
    }

    @Test
    fun settingsDefaultHarnessAndSessionSummaryHarness() {
        val hub = Fixtures.readyHub(app)
        assertEquals(Harnesses.CLAUDE, hub.settings.defaultHarness(Backends.LOCAL))
        assertFalse(hub.settings.hasDefaultHarness(Backends.LOCAL))
        hub.settings.setDefaultHarness(Backends.LOCAL, Harnesses.AGY)
        assertTrue(hub.settings.hasDefaultHarness(Backends.LOCAL))
        assertEquals(Harnesses.AGY, hub.settings.defaultHarness(Backends.LOCAL))

        val agySession =
            sessionSummaryOf(
                Json.parseToJsonElement(
                        """{"id":"agy-1","file":"/f/agy-1.jsonl","cwd":"/workspace","title":"Agy 会话","harness":"agy"}""",
                    )
                    .jsonObject,
                Backends.LOCAL,
            )!!
        assertEquals(Harnesses.AGY, agySession.harness)
        val defaultSession =
            sessionSummaryOf(
                Json.parseToJsonElement("""{"id":"c-1","file":"/f/c-1.jsonl"}""").jsonObject,
                Backends.LOCAL,
            )!!
        assertEquals(Harnesses.CLAUDE, defaultSession.harness)
    }

    @Test
    fun newConversationSwitchesHarnessAndPassesPhoneMcpOptions() {
        val hub = Fixtures.readyHub(app)
        hub.settings.phoneTools = true
        hub.renewBlank()
        val core = FakeCore().install(hub)
        val conv = hub.current
        assertEquals(Harnesses.CLAUDE, conv.harness)
        assertTrue(conv.canSwitchHarness)

        conv.setHarness(Harnesses.AGY)
        assertEquals(Harnesses.AGY, conv.harness)
        val open = core.next("conv.open")
        assertEquals(Harnesses.AGY, open.str("harness"))
        assertEquals(Harnesses.AGY, open.params.obj("choices")?.str("harness"))
        assertEquals(JsonPrimitive(true), open.params.obj("options")?.get("phone"))
        assertEquals(Harnesses.AGY, core.next("conv.harness").str("value"))
        awaitCore { conv.view.state.key.isNotEmpty() }

        // 已经在核心打开的空白新对话再切回 Claude，发 conv.harness
        conv.setHarness(Harnesses.CLAUDE)
        val switched = core.next("conv.harness")
        assertEquals(Harnesses.CLAUDE, switched.str("value"))
        awaitCore { conv.harness == Harnesses.CLAUDE }

        // 一旦有了消息条目，不能再切工具
        conv.coreItems(listOf(UserItem("u1", "你好")))
        assertFalse(conv.canSwitchHarness)
    }

    @Test
    fun effortIncludesXhighAndModelMenuPreservesAgyFullIds() {
        assertTrue(EffortLevels.any { it.first == "xhigh" })
        val agyModels =
            listOf(
                ModelOption("default", "默认", "", "gemini-2.5-pro", EffortLevels.map { it.first }),
                ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro", "", "gemini-2.5-pro"),
                ModelOption("gemini-2.5-flash", "Gemini 2.5 Flash", "", "gemini-2.5-flash"),
                ModelOption("gpt-5", "GPT-5", "", "gpt-5"),
                ModelOption("claude-sonnet-4-6", "Claude Sonnet 4.6", "", "claude-sonnet-4-6"),
            )
        val menu = modelMenu(agyModels)
        val values = (menu.latest + menu.older).map { it.value }
        assertTrue(values.contains("default"))
        assertTrue(values.contains("gemini-2.5-flash"))
        assertTrue(values.contains("gpt-5"))
        assertTrue(values.contains("claude-sonnet-4-6"))
    }

    @Test
    fun configAccountsLoginRelayAndSystemAgyProtocol() {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val cfg =
            ClaudeConfigCore(Harnesses.AGY) { method, params, _ ->
                calls += method to params
                Json.parseToJsonElement("""{"model":"gemini-2.5-pro","permissionMode":"auto"}""")
            }
        assertEquals("gemini-2.5-pro", cfg.defaults().model)
        assertEquals(Harnesses.AGY, calls.single().second.str("harness"))

        calls.clear()
        val accounts =
            AccountsCore(
                HelperProgram({ ByteArray(0) }, "zeta", HelperProgram.CLAUDE_SWITCH),
                call = { method, params, _ ->
                    calls += method to params
                    when (method) {
                        "accounts.login_start" ->
                            Json.parseToJsonElement(
                                """{"url":"https://accounts.google.com/o/oauth2/auth","name":"agy-1"}""",
                            )
                        "accounts.login_code" -> Json.parseToJsonElement("""{"name":"agy-1"}""")
                        else -> Json.parseToJsonElement("""{"current":null,"accounts":[]}""")
                    }
                },
                shell = { error("agy 不需要安装 claude-switch") },
                harness = Harnesses.AGY,
            )
        assertEquals("https://accounts.google.com/o/oauth2/auth", accounts.loginStart().url)
        // agy 支持纯授权码或完整回调 URL（不含 #）
        assertEquals(
            "agy-1",
            accounts.loginCode("http://localhost:51121/oauth-callback?code=4/0Abc"),
        )
        assertTrue(
            calls.all { it.second.str("harness") == Harnesses.AGY && "program" !in it.second },
        )

        // agy 中转支持留空 Base URL（直连 Gemini 官方默认地址）或自定义 https 地址
        val geminiDraft = RelayDraft("gemini-key", "", "AIzaSyTestKey0001", "gemini-2.5-pro")
        assertNull(geminiDraft.problem(create = true, taken = emptySet(), harness = Harnesses.AGY))
        assertTrue(
            geminiDraft.problem(create = true, taken = emptySet(), harness = Harnesses.CLAUDE) !=
                null,
        )

        // system.agy 与 system.agy_update
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        core.respond = { call ->
            when (call.method) {
                "system.agy" -> """{"version":"1.5.18"}"""
                "system.agy_update" ->
                    """{"ok":true,"before":"1.5.17","version":"1.5.18","output":"updated"}"""
                else -> core.defaultReply(call)
            }
        }
        assertEquals("1.5.18", hub.convLinks.agyVersion(Backends.LOCAL))
        val updated = hub.convLinks.updateAgy(Backends.LOCAL)
        assertTrue(updated.ok)
        assertEquals("1.5.18", updated.version)
    }

    private fun runAsset(name: String, vararg args: String): String {
        val cmd = listOf("sh", "src/main/assets/$name") + args
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(15, TimeUnit.SECONDS))
        assertEquals(out, 0, proc.exitValue())
        return out
    }
}
