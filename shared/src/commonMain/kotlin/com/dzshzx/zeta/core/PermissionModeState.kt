package com.dzshzx.zeta.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一个对话的权限模式。Claude Code 的模式跟着进程走（和它的「只用于这个会话」一样），这里分两样记：
 * - [user]：用户在这个对话里的模式，用户自己的消息按它跑。用户在输入栏选的（[choose]）、Claude Code 在它上面自己换的 （计划获批后退出计划模式，见
 *   [reported]）都记在这里，同时经 [remember] 记在进程上，应用被回收后接回进程时照它恢复。 分支接着用原对话的。null 是还没选过、也还没报过：起进程时不指定，用
 *   Claude Code 设置里的默认。
 * - [process]：进程现在用的模式：起进程时带的、之后切到的，或 Claude Code 报来的；起进程时没指定、还没报过时为 null。
 *
 * 每条消息发出前把进程切到发的人的模式（[switchTo]）：后台运行（定时任务、通知触发）的按它自己的，用户的按 [forUser]，
 * 所以后台运行之后用户自己发下一条时自动切回，不用记上一条是谁发的。Claude Code 的默认（settings.json 的
 * permissions.defaultMode，没设时是逐项确认）由 [claudeDefault] 给，还没读到时为 null。 纯逻辑，不碰进程；状态只在主线程上改。
 */
class PermissionModeState(
    initial: String?,
    private val claudeDefault: () -> String?,
    private val remember: (String) -> Unit,
) {
    var user by mutableStateOf(initial)
        private set

    var process: String? = null
        private set

    /** 输入栏显示的模式：这个对话的，没有时是 Claude Code 的默认。 */
    val shown: String?
        get() = user ?: claudeDefault()

    /**
     * 用户的消息用的模式。这个对话没有时：进程没指定模式就不指定（用 Claude Code 的默认）；进程在后台运行的模式上时切到 Claude Code
     * 的默认，还不知道是什么就用逐项确认，宁可多问。
     */
    val forUser: String?
        get() = user ?: (claudeDefault() ?: "default").takeIf { process != null }

    /** 定下用户的模式（用户在输入栏选的，或 Claude Code 在用户的模式上换的），也记在进程上。 */
    fun choose(mode: String) {
        user = mode
        remember(mode)
    }

    /** 起了新进程或接回了在跑的进程，它带的（报的）模式是 [mode]；null 是起的时候没指定。 */
    fun processStarted(mode: String?) {
        process = mode
    }

    /** 要把进程切到 [mode]：要发 set_permission_mode 时返回 true；[mode] 为 null 或进程已经是了就返回 false。 */
    fun switchTo(mode: String?): Boolean {
        if (mode == null || mode == process) return false
        process = mode
        return true
    }

    /**
     * Claude Code 报来进程现在的模式（status 行）。进程在用户的模式上（没有后台运行的模式在用）时用户的跟着变， 用户没选过的从此定下来；起进程时没指定模式的，报来的就是
     * Claude Code 照设置定的。
     */
    fun reported(mode: String) {
        if (process == forUser) choose(mode)
        process = mode
    }

    /** init 和 initialize 应答报的：起进程时没指定模式才要（指定了的报来的就是指定的那个），之后的变化看 status 行。 */
    fun reportedAtStart(mode: String) {
        if (process == null) reported(mode)
    }
}
