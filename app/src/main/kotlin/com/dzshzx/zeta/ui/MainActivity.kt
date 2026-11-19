package com.dzshzx.zeta.ui

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LinuxEnv
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
import com.dzshzx.zeta.ui.tasks.AllConversations
import com.dzshzx.zeta.ui.tasks.BackgroundTasksScreen

class MainActivity : ComponentActivity() {
    private lateinit var hub: ConversationHub

    /** 从外面（胶囊、通知）要求切到某个对话的次数：每来一次，界面关掉盖在聊天页上的页面，露出那个对话。 */
    private var conversationRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hub = ConversationHub.get(application)
        if (hub.setup != SetupState.Ready) hub.checkSetup()
        if (savedInstanceState == null && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        acceptShared(intent)
        // 界面重建时 intent 还是原来那个，不再切一次
        if (savedInstanceState == null) acceptConversation(intent)
        setContent {
            val dark = hub.darkTheme ?: isSystemInDarkTheme()
            // 状态栏、导航栏的图标深浅跟着应用自己的主题走，而不是系统的
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ZetaTheme(darkTheme = hub.darkTheme) { ZetaApp(hub, conversationRequests) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptShared(intent)
        acceptConversation(intent)
    }

    /** 从顶部状态胶囊打开：切到那个对话。 */
    private fun acceptConversation(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_CONVERSATION) ?: return
        intent.removeExtra(EXTRA_CONVERSATION)
        hub.openConversation(key)
        conversationRequests++
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

    companion object {
        private const val EXTRA_CONVERSATION = "com.dzshzx.zeta.extra.CONVERSATION"

        /** 点了打开 Zeta 并切到对话 [key] 的 Intent（通知用，点通知时系统允许从后台打开界面）。 */
        fun conversationIntent(context: Context, key: String): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CONVERSATION, key)

        /** 打开 Zeta 并切到对话 [key]（sessionId，新对话用它的 key）。可以在应用不在前台时调用。 */
        /**
         * 打开 Zeta 并切到对话 [key]。从胶囊上调用时 Zeta 在后台，直接 startActivity 会被后台启动限制拦下（不报错，任务就是不上来）：
         * 照 Eta 用 PendingIntent 发，声明“胶囊这个可见窗口在，就允许”。[displayId] 不是 0 时（调试用的“主屏”）开到那块屏上。
         */
        fun openConversation(context: Context, key: String, displayId: Int = 0) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_CONVERSATION, key)
            val mode = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else {
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            val creator = ActivityOptions.makeBasic().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) pendingIntentCreatorBackgroundActivityStartMode = mode
            }
            val sender = ActivityOptions.makeBasic().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) pendingIntentBackgroundActivityStartMode = mode
                if (displayId != 0) launchDisplayId = displayId
            }
            val pending = PendingIntent.getActivity(
                context, OPEN_CONVERSATION_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, creator.toBundle(),
            )
            runCatching { pending.send(context, 0, null, null, null, null, sender.toBundle()) }
                .onFailure { runCatching { context.startActivity(intent, sender.toBundle()) } }
        }

        /**
         * 经 root 打开（[openConversation] 被拦下时的兜底）。MIUI 另有一道“后台弹出界面”权限，Zeta 没有时上面那次
         * 系统照样回“已送达”，界面却不会到前台。root 的 am start 不受这道限制。在后台线程上调用。
         */
        fun openConversationAsRoot(context: Context, key: String, displayId: Int = 0) {
            val display = if (displayId != 0) " --display $displayId" else ""
            val script = "am start --activity-single-top -n \"\$2\" --es \"\$3\" \"\$4\"$display >/dev/null 2>&1"
            val component = context.packageName + "/" + MainActivity::class.java.name
            runCatching { LinuxEnv.runRoot(LinuxEnv.androidScript(script, component, EXTRA_CONVERSATION, key), 10_000) }
        }

        private const val OPEN_CONVERSATION_REQUEST = 7
    }
}

/** [conversationRequests] 变了：外面要求切到某个对话（对话已经切好），关掉盖在聊天页上的页面和侧栏。 */
@Composable
private fun ZetaApp(hub: ConversationHub, conversationRequests: Int) {
    var paneOpen by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLinux by rememberSaveable { mutableStateOf(false) }
    var showInstall by rememberSaveable { mutableStateOf(false) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var showPhone by rememberSaveable { mutableStateOf(false) }
    var showSsh by rememberSaveable { mutableStateOf(false) }
    // 后台任务面板：对话的 key（只看它的），或 AllConversations
    var tasksScope by rememberSaveable { mutableStateOf<String?>(null) }
    val overlay = showSettings || showLinux || showInstall || showLogin || showPhone || showSsh || tasksScope != null
    fun showChat() {
        if (showLogin) hub.login.cancel()
        showSettings = false
        showLinux = false
        showInstall = false
        showLogin = false
        showPhone = false
        showSsh = false
        tasksScope = null
        paneOpen = false
    }
    LaunchedEffect(conversationRequests) { if (conversationRequests > 0) showChat() }
    ConversationSidePaneScaffold(
        visible = paneOpen,
        backHandlerEnabled = !overlay,
        onOpen = {
            paneOpen = true
            hub.refreshSessions()
        },
        onDismiss = { paneOpen = false },
        pane = { width, revealed, modifier ->
            SessionPane(
                width = width,
                sessions = hub.sessions,
                currentSessionId = hub.current.sessionId,
                revealed = revealed,
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
                onOpenBackground = {
                    tasksScope = AllConversations
                    paneOpen = false
                },
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
            onOpenTasks = { tasksScope = it },
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
    // 退场动画期间 tasksScope 已经是 null，用最后一个值画完
    val lastScope = remember { arrayOfNulls<String>(1) }
    tasksScope?.let { lastScope[0] = it }
    Page(tasksScope != null) {
        BackgroundTasksScreen(
            hub = hub,
            scope = lastScope[0] ?: AllConversations,
            onBack = { tasksScope = null },
            onOpenConversation = { key ->
                hub.openConversation(key)
                showChat()
            },
        )
    }
    // 返回键一层一层退：最上面的页面先关
    BackHandler(enabled = overlay) {
        when {
            showLogin -> {
                hub.login.cancel()
                showLogin = false
            }
            showInstall -> showInstall = false
            tasksScope != null -> tasksScope = null
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
