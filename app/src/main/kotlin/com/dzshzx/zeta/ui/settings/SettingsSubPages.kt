package com.dzshzx.zeta.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable

/** 设置页往下的一层页面。Claude 开头的是“上下文与扩展”里管 Claude Code 自身配置的几页。 */
internal enum class SettingsSubPage { Appearance, Permissions, ClaudeMemory, ClaudeSkills, ClaudeMcp, ClaudeAgents, Schedules, ClaudePermissions, Accounts, NotifyTriggers }

/**
 * 盖在设置页上的子页面，动画和 MainActivity 里的页面一致（从右侧滑入）。
 * 子页面挂在设置页自己身上，不用往 MainActivity 的页面栈里加东西。
 */
@Composable
internal fun SettingsSubPageHost(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(160)),
    ) { content() }
}
