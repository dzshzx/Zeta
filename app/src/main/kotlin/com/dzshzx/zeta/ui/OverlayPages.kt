package com.dzshzx.zeta.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** 子代理查看页看的是哪个对话（key）里哪一步（tool_use id）发起的子代理。 */
@Immutable
internal data class SubagentPageRef(val conversation: String, val toolUse: String)

/**
 * 盖在聊天页上的几页各自开没开。几页可以同时开着（比如设置上面再开 Linux 环境），
 * 返回键按 [closeTop] 的顺序一层一层退。
 */
@Stable
internal class OverlayPages(
    settings: Boolean = false,
    linux: Boolean = false,
    install: Boolean = false,
    login: Boolean = false,
    phone: Boolean = false,
    ssh: Boolean = false,
    tasksScope: String? = null,
    subagent: SubagentPageRef? = null,
) {
    var settings by mutableStateOf(settings)
    var linux by mutableStateOf(linux)
    var install by mutableStateOf(install)
    var login by mutableStateOf(login)
    var phone by mutableStateOf(phone)
    var ssh by mutableStateOf(ssh)

    /** 后台任务面板：对话的 key（只看它的），或 AllConversations；null 是没开。 */
    var tasksScope by mutableStateOf(tasksScope)

    /** 子代理查看页（聊天里点开发起子代理的那一步）；null 是没开。 */
    var subagent by mutableStateOf(subagent)

    val any: Boolean get() = settings || linux || install || login || phone || ssh || tasksScope != null || subagent != null

    /** 全部收起。[keepSetup] 时安装和登录两页留着：它们在进行时不打断。 */
    fun closeAll(keepSetup: Boolean) {
        settings = false
        linux = false
        phone = false
        ssh = false
        tasksScope = null
        subagent = null
        if (!keepSetup) {
            install = false
            login = false
        }
    }

    /** 返回键：关掉最上面的一页。 */
    fun closeTop() {
        when {
            subagent != null -> subagent = null
            login -> login = false
            install -> install = false
            tasksScope != null -> tasksScope = null
            ssh -> ssh = false
            phone -> phone = false
            linux -> linux = false
            else -> settings = false
        }
    }

    companion object {
        val Saver = listSaver<OverlayPages, Any?>(
            save = { listOf(it.settings, it.linux, it.install, it.login, it.phone, it.ssh, it.tasksScope, it.subagent?.conversation, it.subagent?.toolUse) },
            restore = {
                OverlayPages(
                    settings = it[0] as Boolean,
                    linux = it[1] as Boolean,
                    install = it[2] as Boolean,
                    login = it[3] as Boolean,
                    phone = it[4] as Boolean,
                    ssh = it[5] as Boolean,
                    tasksScope = it[6] as String?,
                    subagent = (it[7] as String?)?.let { conversation -> (it[8] as String?)?.let { use -> SubagentPageRef(conversation, use) } },
                )
            },
        )
    }
}

@Composable
internal fun rememberOverlayPages(): OverlayPages = rememberSaveable(saver = OverlayPages.Saver) { OverlayPages() }
