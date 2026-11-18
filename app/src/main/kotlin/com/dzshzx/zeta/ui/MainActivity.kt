package com.dzshzx.zeta.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.components.ConversationSidePaneScaffold
import com.dzshzx.zeta.ui.pane.SessionPane
import com.dzshzx.zeta.ui.setup.EnvInstallScreen
import com.dzshzx.zeta.ui.setup.LinuxScreen
import com.dzshzx.zeta.ui.setup.LoginScreen
import com.dzshzx.zeta.ui.setup.PhoneScreen
import com.dzshzx.zeta.ui.setup.SshScreen

class MainActivity : ComponentActivity() {
    private lateinit var hub: ConversationHub

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hub = ConversationHub.get(application)
        if (hub.setup != SetupState.Ready) hub.checkSetup()
        if (savedInstanceState == null && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        acceptShared(intent)
        setContent {
            val dark = hub.darkTheme ?: isSystemInDarkTheme()
            // 状态栏、导航栏的图标深浅跟着应用自己的主题走，而不是系统的
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ZetaTheme(darkTheme = hub.darkTheme) { ZetaApp(hub) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptShared(intent)
    }

    /** 别的应用把图片或文件分享过来：交给当前对话的输入栏。 */
    private fun acceptShared(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        if (uris.isNotEmpty()) hub.incomingShares = hub.incomingShares + uris
        intent.action = Intent.ACTION_MAIN
    }

}

@Composable
private fun ZetaApp(hub: ConversationHub) {
    var paneOpen by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLinux by rememberSaveable { mutableStateOf(false) }
    var showInstall by rememberSaveable { mutableStateOf(false) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var showPhone by rememberSaveable { mutableStateOf(false) }
    var showSsh by rememberSaveable { mutableStateOf(false) }
    val overlay = showSettings || showLinux || showInstall || showLogin || showPhone || showSsh
    ConversationSidePaneScaffold(
        visible = paneOpen,
        backHandlerEnabled = !overlay,
        onOpen = {
            paneOpen = true
            hub.refreshSessions()
        },
        onDismiss = { paneOpen = false },
        pane = { width, _, modifier ->
            SessionPane(
                width = width,
                sessions = hub.sessions,
                currentSessionId = hub.current.sessionId,
                isRunning = hub::isRunning,
                onNewChat = {
                    hub.startNew()
                    paneOpen = false
                },
                onOpen = {
                    hub.open(it)
                    paneOpen = false
                },
                onDelete = hub::delete,
                onRename = hub::rename,
                onArchive = hub::archive,
                onOpenSettings = { showSettings = true },
                modifier = modifier,
            )
        },
    ) {
        ChatScreen(
            hub = hub,
            onOpenPane = {
                paneOpen = true
                hub.refreshSessions()
            },
            onOpenSettings = { showSettings = true },
            onOpenInstall = { showInstall = true },
            onOpenLogin = { showLogin = true },
        )
    }
    Page(showSettings) { SettingsScreen(hub = hub, onBack = { showSettings = false }, onOpenLinux = { showLinux = true }, onOpenPhone = { showPhone = true }) }
    Page(showPhone) { PhoneScreen(hub = hub, onBack = { showPhone = false }) }
    Page(showLinux) {
        LinuxScreen(
            hub = hub,
            onBack = { showLinux = false },
            onOpenInstall = { showInstall = true },
            onOpenLogin = { showLogin = true },
            onOpenSsh = { showSsh = true },
        )
    }
    Page(showSsh) { SshScreen(hub = hub, onBack = { showSsh = false }) }
    Page(showInstall) { EnvInstallScreen(hub = hub, onBack = { showInstall = false }, onOpenLogin = { showLogin = true }) }
    Page(showLogin) { LoginScreen(hub = hub, onBack = { showLogin = false }) }
    // 返回键一层一层退：最上面的页面先关
    BackHandler(enabled = overlay) {
        when {
            showLogin -> {
                hub.login.cancel()
                showLogin = false
            }
            showInstall -> showInstall = false
            showSsh -> showSsh = false
            showPhone -> showPhone = false
            showLinux -> showLinux = false
            else -> showSettings = false
        }
    }
}

/** 盖在聊天页上面的一层页面，从右侧滑入。 */
@Composable
private fun Page(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(160)),
    ) { content() }
}
