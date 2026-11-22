package com.dzshzx.zeta.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.ui.PageStack
import com.dzshzx.zeta.ui.components.LocalHub
import com.dzshzx.zeta.ui.components.ProcessHub
import com.dzshzx.zeta.ui.rememberPageStack
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController

/** Zeta 桌面版的入口：一个窗口，左边是会话列表，右边是聊天，设置等页面盖在聊天这一侧。Esc 当返回键。 */
fun main() = application {
    val platform = remember { DesktopHubPlatform() }
    val hub = remember { ConversationHub(platform).also { ProcessHub.current = it } }
    val back = remember { DirectNavigationEventInput() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Zeta",
        state = rememberWindowState(size = DpSize(1280.dp, 860.dp)),
        onPreviewKeyEvent = { event ->
            val escape = event.key == Key.Escape && event.type == KeyEventType.KeyDown
            if (escape) back.backCompleted()
            escape
        },
    ) {
        DesktopRoot(hub, platform, back)
    }
}

/** 窗口里的全部内容：返回键分发、中枢、主题、主界面和底部的提示。截图测试直接画它（[back] 为 null，[pages] 由测试给，好直接打开某一页）。 */
@Composable
fun DesktopRoot(
    hub: ConversationHub,
    platform: DesktopHubPlatform,
    back: DirectNavigationEventInput?,
    pages: PageStack = rememberPageStack(),
) {
    val owner = rememberNavigationEventDispatcherOwner(parent = null)
    if (back != null) {
        DisposableEffect(owner, back) {
            owner.navigationEventDispatcher.addInput(back)
            onDispose { owner.navigationEventDispatcher.removeInput(back) }
        }
    }
    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides owner,
        LocalHub provides hub,
    ) {
        DesktopTheme(hub) {
            DesktopApp(hub, pages)
            NoticeToast(platform)
        }
    }
}

/** COUI 2 的配色：跟着设置里的深浅色，没设时跟系统。 */
@Composable
fun DesktopTheme(hub: ConversationHub, content: @Composable () -> Unit) {
    val dark = hub.settings.darkTheme ?: isSystemInDarkTheme()
    val controller =
        remember(dark) {
            ThemeController(
                colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            )
        }
    COUITheme(controller = controller) { Box(Modifier.fillMaxSize()) { content() } }
}
