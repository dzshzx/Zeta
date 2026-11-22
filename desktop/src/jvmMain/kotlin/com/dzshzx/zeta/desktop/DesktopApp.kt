package com.dzshzx.zeta.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.ui.AppPage
import com.dzshzx.zeta.ui.PageStack
import com.dzshzx.zeta.ui.PageStackHost
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.chat.IntakeChooser
import com.dzshzx.zeta.ui.chat.SubagentScreen
import com.dzshzx.zeta.ui.components.BackHandler
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.pane.SessionPane
import com.dzshzx.zeta.ui.rememberPageStack
import com.dzshzx.zeta.ui.setup.RemoteHostsScreen
import com.dzshzx.zeta.ui.tasks.AllConversations
import com.dzshzx.zeta.ui.tasks.BackgroundTasksScreen
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay

/** 侧栏（会话列表）的宽度：桌面窗口够宽，一直开着。 */
private val PaneWidth = 300.dp

/** 桌面主界面：左边常开的会话列表，右边聊天；设置、远程设备、后台任务、子代理这些页面盖在右边。 */
@Composable
fun DesktopApp(hub: ConversationHub, pages: PageStack = rememberPageStack()) {
    LaunchedEffect(Unit) { hub.sessionList.refresh() }
    Row(Modifier.fillMaxSize()) {
        SessionPane(
            width = PaneWidth,
            sessions = hub.sessionList.sessions,
            currentSessionId = hub.current.sessionId,
            revealed = true,
            statusOf = hub::sessionStatus,
            onNewChat = {
                hub.startNew()
                pages.closeAll(keepSetup = false)
            },
            onOpen = {
                hub.open(it)
                pages.closeAll(keepSetup = false)
            },
            onDelete = hub.sessionList::delete,
            onRename = hub.sessionList::rename,
            onArchive = hub.sessionList::archive,
            onOpenSettings = { pages.open(AppPage.Settings) },
            onOpenBackgroundTasks = { pages.open(AppPage.Tasks(AllConversations)) },
            modifier = Modifier.fillMaxHeight(),
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            ChatScreen(
                hub = hub,
                onOpenPane = null,
                onOpenSettings = { pages.open(AppPage.Settings) },
                onOpenInstall = {},
                onOpenLogin = {},
                onOpenRemoteHosts = { pages.open(AppPage.RemoteHosts) },
                onOpenTasks = { pages.open(AppPage.Tasks(it)) },
                onOpenSubagent = { conversation, use ->
                    pages.open(AppPage.Subagent(conversation, use))
                },
            )
            BackHandler(enabled = pages.any) { pages.pop() }
            PageStackHost(pages) { page ->
                when (page) {
                    AppPage.Settings ->
                        DesktopSettingsScreen(
                            hub,
                            onBack = { pages.close(page) },
                            onAddHost = { pages.open(AppPage.RemoteHosts) },
                        )
                    AppPage.RemoteHosts ->
                        RemoteHostsScreen(hub = hub, onBack = { pages.close(page) })
                    is AppPage.Tasks ->
                        BackgroundTasksScreen(
                            hub = hub,
                            scope = page.scope,
                            onBack = { pages.close(page) },
                            onOpenConversation = { key ->
                                hub.openConversation(key)
                                pages.closeAll(keepSetup = false)
                            },
                        )
                    is AppPage.Subagent ->
                        SubagentScreen(
                            hub = hub,
                            conversationKey = page.conversation,
                            toolUseId = page.toolUse,
                            onBack = { pages.close(page) },
                        )
                    // 手机的 Debian 环境、SSH 服务、安装和登录，桌面上没有
                    AppPage.Linux,
                    AppPage.Ssh,
                    AppPage.Install,
                    AppPage.Login -> Unit
                }
            }
        }
    }
    IntakeChooser(hub)
}

/** 窗口底部的提示（手机上的 Toast）：显示两秒半后消失。 */
@Composable
fun NoticeToast(platform: DesktopHubPlatform) {
    val notice = platform.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2500)
            platform.notice = null
        }
    }
    val shown = rememberRetained(notice)
    Box(Modifier.fillMaxSize().padding(bottom = 32.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(notice != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = shown.orEmpty(),
                color = COUITheme.colorScheme.background,
                modifier =
                    Modifier.background(
                            COUITheme.colorScheme.labelPrimary.copy(alpha = 0.86f),
                            RoundedCornerShape(18.dp),
                        )
                        .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}
