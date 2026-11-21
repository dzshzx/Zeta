package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限模式的规则（[PermissionModeState]），不起进程。Conversation 怎么用它、Claude Code 那一侧见 RunPermissionsTest、
 * UnattendedRulesContractTest 和设备脚本 mode-per-sender.sh、mode-reattach.sh。
 */
class PermissionModeStateTest {
    private var claudeDefault: String? = null
    private val remembered = ArrayList<String>()

    private fun state(initial: String? = null) =
        PermissionModeState(initial, { claudeDefault }, { remembered += it })

    @Test
    fun newConversationLeavesTheModeToClaudeCode() {
        val modes = state()
        // 进程还没起、Claude Code 的默认还没读到：起进程时不指定，输入栏也不知道显示什么
        assertNull(modes.forUser)
        assertNull(modes.shown)
        claudeDefault = "auto"
        assertEquals("auto", modes.shown)
        // 默认读到了也不指定：不带 --permission-mode 起的进程本来就用 settings.json 的
        assertNull(modes.forUser)
        modes.processStarted(null)
        // init 报的是 Claude Code 照设置定的：这个对话从此用它，并记在进程上
        modes.reportedAtStart("auto")
        assertEquals("auto", modes.user)
        assertEquals("auto", modes.process)
        assertEquals(listOf("auto"), remembered)
        // 之后每轮的 init 不再改
        modes.reportedAtStart("plan")
        assertEquals("auto", modes.user)
    }

    @Test
    fun runModeOnlyCoversTheRunsMessages() {
        val modes = state("acceptEdits")
        modes.processStarted("acceptEdits")
        assertTrue(modes.switchTo("bypassPermissions"))
        assertFalse("已经是了不再发", modes.switchTo("bypassPermissions"))
        // 后台运行的这一轮里 Claude Code 报来的不改用户的
        modes.reported("bypassPermissions")
        assertEquals("acceptEdits", modes.user)
        assertEquals("acceptEdits", modes.shown)
        // 用户自己发下一条：切回用户的
        assertTrue(modes.switchTo(modes.forUser))
        assertEquals("acceptEdits", modes.process)
        // 用户的模式上 Claude Code 自己换了（计划获批后退出计划模式）：用户的跟着变
        modes.reported("default")
        assertEquals("default", modes.user)
        assertEquals(listOf("default"), remembered)
        assertFalse(modes.switchTo(null))
    }

    @Test
    fun userWithoutChoiceGetsClaudeCodeDefaultAfterARun() {
        val modes = state()
        modes.processStarted("bypassPermissions")
        // 默认还不知道：用逐项确认，宁可多问
        assertEquals("default", modes.forUser)
        claudeDefault = "auto"
        assertEquals("auto", modes.forUser)
        assertEquals("auto", modes.shown)
        // init 报的是起进程时指定的（后台运行的），不当成用户的
        modes.reportedAtStart("bypassPermissions")
        assertNull(modes.user)
    }

    @Test
    fun choosingRemembersOnTheProcess() {
        val modes = state()
        modes.choose("plan")
        assertEquals("plan", modes.user)
        assertEquals("plan", modes.forUser)
        assertEquals("plan", modes.shown)
        assertEquals(listOf("plan"), remembered)
    }

    /** 接回进程：用户的模式照进程上记下的恢复（initial），进程的照托管目录里报的。 */
    @Test
    fun reattachKeepsBothSides() {
        val mine = state("plan")
        mine.processStarted("plan")
        assertFalse(mine.switchTo(mine.forUser))
        val run = state()
        run.processStarted("bypassPermissions")
        assertTrue(run.switchTo(run.forUser))
        assertEquals("default", run.process)
    }
}
