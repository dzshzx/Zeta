package com.dzshzx.zeta.ui

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.dzshzx.zeta.ui.components.rememberRetained
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.Intake
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.offerIntake
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.chat.IntakeChooser
import com.dzshzx.zeta.ui.chat.SubagentScreen
import com.dzshzx.zeta.ui.components.ConversationSidePaneScaffold
import com.dzshzx.zeta.ui.pane.SessionPane
import com.dzshzx.zeta.ui.settings.SettingsLink
import com.dzshzx.zeta.ui.pane.SessionReveal
import com.dzshzx.zeta.ui.setup.EnvInstallScreen
import com.dzshzx.zeta.ui.setup.LinuxScreen
import com.dzshzx.zeta.ui.setup.LoginScreen
import com.dzshzx.zeta.ui.setup.PhoneScreen
import com.dzshzx.zeta.ui.setup.SshScreen
import com.dzshzx.zeta.ui.tasks.AllConversations
import com.dzshzx.zeta.ui.tasks.BackgroundTasksScreen
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private lateinit var hub: ConversationHub

    /** 从外面（胶囊、通知）要求切到某个对话的次数：每来一次，界面关掉盖在聊天页上的页面，露出那个对话。 */
    private var conversationRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hub = ConversationHub.get(application)
        live = WeakReference(this)
        if (hub.setup != SetupState.Ready) hub.checkSetup()
        if (savedInstanceState == null && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        acceptShared(intent)
        // 界面重建时 intent 还是原来那个，不再切一次
        if (savedInstanceState == null) acceptConversation(intent)
        SettingsLink.accept(intent)
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
        SettingsLink.accept(intent)
    }

    /** 从顶部状态胶囊打开：切到那个对话。 */
    private fun acceptConversation(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_CONVERSATION) ?: return
        intent.removeExtra(EXTRA_CONVERSATION)
        hub.openConversation(key)
        conversationRequests++
    }

    /** 别的应用分享过来（文字、图片、文件），或者用 Zeta 打开一个文件：先问放进哪个对话，再交给它的输入栏。 */
    private fun acceptShared(intent: Intent?) {
        val intake = Intake.fromIntent(intent) ?: return
        hub.offerIntake(intake)
        // 界面重建时 intent 还是这个，不再收一次
        intent?.action = Intent.ACTION_MAIN
    }

    companion object {
        private const val EXTRA_CONVERSATION = "com.dzshzx.zeta.extra.CONVERSATION"

        private var live = WeakReference<MainActivity>(null)

        /** 界面还开着（没被销毁）时它所在的屏幕；截屏后把 Zeta 打开到这块屏上。 */
        val liveDisplay: Int? get() = live.get()?.takeUnless { it.isDestroyed }?.display?.displayId

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
    val pages = rememberOverlayPages()
    /** 回到聊天页：收起侧栏和盖在上面的页面。[keepSetup] 时进行中的安装、登录不打断。 */
    fun showChat(keepSetup: Boolean = false) {
        if (!keepSetup && pages.login) hub.login.cancel()
        pages.closeAll(keepSetup)
        paneOpen = false
    }
    LaunchedEffect(conversationRequests) { if (conversationRequests > 0) showChat() }
    // 从外面送进来的东西要放进输入栏：侧栏和盖在聊天页上的设置页都收起来（安装、登录在进行时不打断）
    val incoming = hub.incoming
    LaunchedEffect(incoming) { if (incoming != null) showChat(keepSetup = true) }
    // 通知要打开设置里的某一页：先把设置页打开，那一页由设置页自己切过去
    LaunchedEffect(SettingsLink.pending) { if (SettingsLink.pending != null) pages.settings = true }
    ConversationSidePaneScaffold(
        visible = paneOpen,
        backHandlerEnabled = !pages.any,
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
                onOpenSettings = { pages.settings = true },
                onOpenBackground = {
                    pages.tasksScope = AllConversations
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
            onOpenSettings = { pages.settings = true },
            onOpenInstall = { pages.install = true },
            onOpenLogin = { pages.login = true },
            onOpenTasks = { pages.tasksScope = it },
            onOpenSubagent = { conversation, use -> pages.subagent = SubagentPageRef(conversation, use) },
        )
    }
    Page(pages.settings) {
        SettingsScreen(
            hub = hub,
            onBack = { pages.settings = false },
            onOpenLinux = { pages.linux = true },
            onOpenPhone = { pages.phone = true },
            // 存储页里点一个会话：关掉设置，打开侧栏并定位到它
            onRevealSession = { id ->
                SessionReveal.request = id
                pages.settings = false
                paneOpen = true
                hub.refreshSessions()
            },
            onShowChat = { showChat() },
        )
    }
    Page(pages.phone) { PhoneScreen(hub = hub, onBack = { pages.phone = false }) }
    Page(pages.linux) {
        LinuxScreen(
            hub = hub,
            onBack = { pages.linux = false },
            onOpenInstall = { pages.install = true },
            onOpenLogin = { pages.login = true },
            onOpenSsh = { pages.ssh = true },
        )
    }
    Page(pages.ssh) { SshScreen(hub = hub, onBack = { pages.ssh = false }) }
    Page(pages.install) { EnvInstallScreen(hub = hub, onBack = { pages.install = false }, onOpenLogin = { pages.login = true }) }
    Page(pages.login) { LoginScreen(hub = hub, onBack = { pages.login = false }) }
    // 退场动画期间 tasksScope 已经是 null，用最后一个值画完
    val lastScope = rememberRetained(pages.tasksScope)
    Page(pages.tasksScope != null) {
        BackgroundTasksScreen(
            hub = hub,
            scope = lastScope ?: AllConversations,
            onBack = { pages.tasksScope = null },
            onOpenConversation = { key ->
                hub.openConversation(key)
                showChat()
            },
        )
    }
    val lastSubagent = rememberRetained(pages.subagent)
    Page(pages.subagent != null) {
        lastSubagent?.let { ref -> SubagentScreen(hub = hub, conversationKey = ref.conversation, toolUseId = ref.toolUse, onBack = { pages.subagent = null }) }
    }
    IntakeChooser(hub)
    // 返回键一层一层退：最上面的页面先关
    BackHandler(enabled = pages.any) {
        if (pages.login) hub.login.cancel()
        pages.closeTop()
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
