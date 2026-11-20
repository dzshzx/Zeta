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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.dzshzx.zeta.ui.settings.SettingsScreen
import com.dzshzx.zeta.ui.pane.SessionReveal
import com.dzshzx.zeta.ui.setup.EnvInstallScreen
import com.dzshzx.zeta.ui.setup.LinuxScreen
import com.dzshzx.zeta.ui.setup.LoginScreen
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
        if (hub.environment.setup != SetupState.Ready) hub.environment.checkSetup()
        if (savedInstanceState == null && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        acceptShared(intent)
        // 界面重建时 intent 还是原来那个，不再切一次
        if (savedInstanceState == null) acceptConversation(intent)
        SettingsLink.accept(intent)
        setContent {
            val dark = hub.settings.darkTheme ?: isSystemInDarkTheme()
            // 状态栏、导航栏的图标深浅跟着应用自己的主题走，而不是系统的
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ZetaTheme(darkTheme = hub.settings.darkTheme) { ZetaApp(hub, conversationRequests) }
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
            val creator = ActivityOptions.makeBasic().apply { pendingIntentCreatorBackgroundActivityStartMode = mode }
            val sender = ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode = mode
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
    val pages = rememberPageStack()
    /** 回到聊天页：收起侧栏和盖在上面的页面。[keepSetup] 时进行中的安装、登录不打断。 */
    fun showChat(keepSetup: Boolean = false) {
        if (!keepSetup && AppPage.Login in pages) hub.environment.login.cancel()
        pages.closeAll(keepSetup)
        paneOpen = false
    }
    LaunchedEffect(conversationRequests) { if (conversationRequests > 0) showChat() }
    // 从外面送进来的东西要放进输入栏：侧栏和盖在聊天页上的设置页都收起来（安装、登录在进行时不打断）
    val incoming = hub.incoming
    LaunchedEffect(incoming) { if (incoming != null) showChat(keepSetup = true) }
    // 通知要打开设置里的某一页：先把设置页打开（放到最上面），那一页由设置页自己切过去
    LaunchedEffect(SettingsLink.pending) { if (SettingsLink.pending != null) pages.open(AppPage.Settings) }
    ConversationSidePaneScaffold(
        visible = paneOpen,
        backHandlerEnabled = !pages.any,
        onOpen = {
            paneOpen = true
            hub.sessionList.refresh()
        },
        onDismiss = { paneOpen = false },
        pane = { width, revealed, modifier ->
            SessionPane(
                width = width,
                sessions = hub.sessionList.sessions,
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
                onDelete = hub.sessionList::delete,
                onRename = hub.sessionList::rename,
                onArchive = hub.sessionList::archive,
                onOpenSettings = { pages.open(AppPage.Settings) },
                onOpenBackgroundTasks = {
                    pages.open(AppPage.Tasks(AllConversations))
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
                hub.sessionList.refresh()
            },
            onOpenSettings = { pages.open(AppPage.Settings) },
            onOpenInstall = { pages.open(AppPage.Install) },
            onOpenLogin = { pages.open(AppPage.Login) },
            onOpenTasks = { pages.open(AppPage.Tasks(it)) },
            onOpenSubagent = { conversation, use -> pages.open(AppPage.Subagent(conversation, use)) },
        )
    }
    // 返回键关最上面那页。放在页面前面注册：页面里自己的返回键处理器（关子页）后注册，先收到返回键
    BackHandler(enabled = pages.any) {
        if (pages.top == AppPage.Login) hub.environment.login.cancel()
        pages.pop()
    }
    PageStackHost(pages) { page ->
        when (page) {
            AppPage.Settings -> SettingsScreen(
                hub = hub,
                onBack = { pages.close(page) },
                onOpenLinux = { pages.open(AppPage.Linux) },
                // 存储页里点一个会话：关掉设置，打开侧栏并定位到它
                onRevealSession = { id ->
                    SessionReveal.request = id
                    pages.close(page)
                    paneOpen = true
                    hub.sessionList.refresh()
                },
                onShowChat = { showChat() },
            )
            AppPage.Linux -> LinuxScreen(
                hub = hub,
                onBack = { pages.close(page) },
                onOpenInstall = { pages.open(AppPage.Install) },
                onOpenLogin = { pages.open(AppPage.Login) },
                onOpenSsh = { pages.open(AppPage.Ssh) },
            )
            AppPage.Ssh -> SshScreen(hub = hub, onBack = { pages.close(page) })
            AppPage.Install -> EnvInstallScreen(hub = hub, onBack = { pages.close(page) }, onOpenLogin = { pages.open(AppPage.Login) })
            AppPage.Login -> LoginScreen(hub = hub, onBack = { pages.close(page) })
            is AppPage.Tasks -> BackgroundTasksScreen(
                hub = hub,
                scope = page.scope,
                onBack = { pages.close(page) },
                onOpenConversation = { key ->
                    hub.openConversation(key)
                    showChat()
                },
            )
            is AppPage.Subagent -> SubagentScreen(hub = hub, conversationKey = page.conversation, toolUseId = page.toolUse, onBack = { pages.close(page) })
        }
    }
    IntakeChooser(hub)
}
