package com.dzshzx.zeta.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.ComposerDraft
import com.dzshzx.zeta.core.ComposerDrafts
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.CwdProblem
import com.dzshzx.zeta.core.EarlierState
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.Occupant
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.ThoughtItem
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.core.userTurns
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.LocalHub
import com.dzshzx.zeta.ui.components.coui2.AnchoredMenu
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.PageBlurHost
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.components.coui2.TopBarMoreMenu
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.menuItem
import com.dzshzx.zeta.ui.components.coui2.pageBlurSource
import com.dzshzx.zeta.ui.components.coui2.rememberPageBlur
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.components.whileStarted
import com.dzshzx.zeta.ui.markdown.LocalTextFinder
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import com.dzshzx.zeta.ui.pane.HostSwitcher
import com.dzshzx.zeta.ui.settings.choiceEntries
import com.dzshzx.zeta.ui.settings.modelChoices
import com.dzshzx.zeta.ui.tasks.BackgroundTaskStrip
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.TopAppBarGlow
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.icon.extended.Sidebar
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 结束一个对话的确认框说明（结束一律先问，顶栏和侧栏同一条规则）：正在别处运行的（[external]）连那边的进程一起停掉，说得出在哪 （[occupant]，核心查到的那边的样子，没查到是
 * null）时说在哪；还在进行的（[busy]）一并停掉；闲着的只停掉背后的进程。 同桌面版 `windows/internal/ui/sessionops.go` 的
 * endConfirmSummary。
 */
fun endConfirmSummary(external: Boolean, occupant: Occupant?, busy: Boolean): String =
    if (external)
        spaceMixed(
            Strings.chatEndSummaryAt(
                occupant?.let { occupantPlace(it.app, it.device, it.local) }
                    ?: Strings.chatPlaceElsewhere(),
            ),
        )
    else if (busy) Strings.chatEndSummaryActive() else Strings.chatEndSummaryIdle()

/**
 * 聊天页。根上是 COUI 的 Scaffold（页面上的对话框、菜单都画在它里面），顶栏是 COUI 的小顶栏，中间换成对话标题和模型切换。
 * 内容录进页面背景（[PageBlur]），顶栏光晕、对话框和菜单拿它做毛玻璃；做法同 CouiPageScaffold， 只是聊天页铺整页底色（background），输入栏在内容里跟着键盘走。
 */
@Composable
fun ChatScreen(
    hub: ConversationHub,
    /** 打开侧栏（历史对话）；侧栏一直开着时是 null，顶栏不放这个钮。 */
    onOpenPane: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onOpenInstall: () -> Unit,
    onOpenLogin: () -> Unit,
    /** 打开远程主机设置页（手机上没有 Debian 环境时连远程主机）。 */
    onOpenRemoteHosts: () -> Unit = {},
    /** 打开后台任务面板，参数是对话的 key。 */
    onOpenTasks: (String) -> Unit = {},
    /** 打开子代理查看页：对话的 key、发起子代理的那一步（tool_use id）。 */
    onOpenSubagent: (String, String) -> Unit = { _, _ -> },
    /** 侧栏开着：聊天页被推到一边，等用户回应的请求先不弹，收起侧栏后再弹。 */
    paneOpen: Boolean = false,
) {
    val conversation = hub.current
    val colors = COUITheme.colorScheme
    val find = remember(conversation) { ChatFindState() }
    val listState = remember(conversation) { LazyListState() }
    // 对话能不能显示：要靠 Debian 的主机等它就绪，直连的远程主机不用
    val usable = hub.usable(conversation.backend)
    val blur = rememberPageBlur()
    // 结束确认框的说明，打开时定下（框开着时对话闲下来，说明也不跟着变）；null 是没开着
    var endSummary by remember(conversation) { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        contentWindowInsets =
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            // 顶栏在页面录制范围之外：它的菜单可以模糊页面
            PageBlurHost(blur) {
                ChatTopBar(
                    conversation = conversation,
                    host =
                        hub.backendName(conversation.backend).takeIf {
                            conversation.backend != Backends.LOCAL
                        },
                    models = hub.modelOptions(conversation.backend),
                    backdrop = blur.backdrop,
                    listState = listState.takeIf { usable },
                    onOpenPane = onOpenPane,
                    onNewChat = hub::startNew,
                    onEnd = {
                            endSummary =
                                endConfirmSummary(
                                    conversation.blockedByExternal,
                                    conversation.occupant,
                                    conversation.active,
                                )
                        }
                            .takeIf {
                                canEnd(hub, conversation)
                            },
                    onOpenSettings = onOpenSettings,
                    onFind = find::open.takeIf { usable && conversation.hasItems },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().pageBlurSource(blur).background(colors.background),
        ) {
            PageBlurHost(blur) {
                when {
                    // 换对话时这一块整个重建：ConversationBody 里记住的东西（草稿、消息操作、列表项和输入栏各控件的状态）都从头来
                    usable ->
                        key(conversation) {
                            CompositionLocalProvider(
                                LocalTextFinder provides find,
                                LocalBackend provides conversation.backend,
                                LocalHub provides hub,
                            ) {
                                ConversationBody(
                                    hub,
                                    conversation,
                                    padding,
                                    listState,
                                    onOpenLogin,
                                    find,
                                    onOpenTasks,
                                    onOpenSubagent,
                                )
                            }
                        }
                    hub.remoteHost(conversation.backend)?.connected == false ->
                        DisconnectedMessage(hub, conversation.backend, padding)
                    else ->
                        ChatPlatform.current.Unavailable(
                            hub,
                            padding,
                            onOpenInstall,
                            onOpenRemoteHosts,
                            onOpenSettings,
                        )
                }
                // 对话框的毛玻璃底取的是聊天页的画面：聊天页被侧栏推开时，侧栏那一块没有底，会透出侧栏的字
                PromptSheet(
                    prompt = conversation.pending.takeUnless { paneOpen },
                    onReply = conversation::reply,
                    onElicit = conversation::answerElicitation,
                )
                ConfirmDialog(
                    show = endSummary != null,
                    title = Strings.chatEndTitle(),
                    summary = rememberRetained(endSummary),
                    confirmText = Strings.chatEnd(),
                    destructive = true,
                    onConfirm = {
                        endSummary = null
                        if (hub.current === conversation) hub.endCurrent()
                    },
                    onDismissRequest = { endSummary = null },
                )
            }
        }
    }
}

/** 有可结束的对话：环境就绪，且不是空的新对话。 */
private fun canEnd(hub: ConversationHub, conversation: Conversation): Boolean =
    (hub.platform.local?.ready ?: true) && (conversation.sessionId != null || conversation.hasItems)

/** 顶栏光晕在内容往上滚过这么多之后全部显出（COUI 17 是 30dp）。 */
private val GlowRevealDistance = 30.dp

/**
 * 聊天页顶栏：COUI 小顶栏（左边历史对话，右边“更多”菜单），标题位置换成 [ChatHeader]。 页面录进了 [backdrop] 时顶栏透明、带 COUI 17
 * 光晕；光晕随聊天列表离开顶部的距离显出（[listState] 为 null 时不显）。
 */
@Composable
private fun ChatTopBar(
    conversation: Conversation,
    /** 对话在远程主机上时是那台的名字，标题下面和模型写在一起；手机本机的是 null。 */
    host: String?,
    models: List<ModelOption>,
    backdrop: LayerBackdrop?,
    listState: LazyListState?,
    onOpenPane: (() -> Unit)?,
    onNewChat: () -> Unit,
    /** 结束当前对话；没有可结束的（空的新对话）时为 null。 */
    onEnd: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onFind: (() -> Unit)?,
) {
    val colors = COUITheme.colorScheme
    val revealPx = with(LocalDensity.current) { GlowRevealDistance.toPx() }
    val scrolled by
        remember(listState, revealPx) {
            derivedStateOf {
                when {
                    listState == null -> 0f
                    listState.firstVisibleItemIndex > 0 -> revealPx
                    else -> listState.firstVisibleItemScrollOffset.toFloat().coerceAtMost(revealPx)
                }
            }
        }
    val glow: (@Composable BoxScope.() -> Unit)? =
        if (backdrop != null) {
            {
                TopAppBarGlow(
                    backdrop = backdrop,
                    scrollOffsetPx = scrolled,
                    isDark = colors.isDark,
                    baseColor = colors.background,
                    enabled = true,
                )
            }
        } else {
            null
        }
    Box {
        SmallTopAppBar(
            title = "",
            color = if (backdrop != null) Color.Transparent else colors.background,
            glow = glow,
            navigationIcon = {
                onOpenPane?.let {
                    IconActionButton(COUIIcons.Sidebar, Strings.chatHistory(), onClick = it)
                }
            },
            actions = {
                OverflowMenu(
                    onNewChat = onNewChat,
                    onEnd = onEnd,
                    onOpenSettings = onOpenSettings,
                    onFind = onFind,
                )
            },
        )
        // 标题位置：两边让出图标按钮，竖直方向和顶栏的 52dp 工具栏对齐
        Box(
            modifier =
                Modifier.matchParentSize()
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                    .padding(horizontal = 72.dp),
            contentAlignment = Alignment.Center,
        ) {
            ChatHeader(conversation = conversation, host = host, models = models)
        }
    }
}

/** 手机本机用不了时连远程主机：已经加过的话给主机胶囊换过去，没加过给去加的按钮。 */
@Composable
fun RemoteChoice(hub: ConversationHub, onOpenRemoteHosts: () -> Unit) {
    if (hub.settings.remoteHosts.isEmpty()) {
        TextButton(text = Strings.chatConnectRemote(), onClick = onOpenRemoteHosts)
    } else {
        HostSwitcher(hub)
    }
}

/** 当前对话所在的远程主机「连接」关着（[ConversationHub.setConnected]）：说一声，点「连接」马上连回去、接着看这个对话。 */
@Composable
private fun DisconnectedMessage(hub: ConversationHub, backend: String, padding: PaddingValues) {
    SetupMessage(
        padding,
        Strings.chatDisconnectedTitle(hub.backendName(backend)),
        Strings.chatDisconnectedBody(RemoteHosts.OFF_NOTE),
    ) {
        TextButton(
            text = Strings.chatConnect(),
            onClick = { hub.setConnected(backend, true) },
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/** 对话用不了时占满聊天页的说明：标题、一段话（null 时是在等的动画），下面放 [actions]。 */
@Composable
fun SetupMessage(
    padding: PaddingValues,
    title: String,
    message: String?,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = COUITheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 56.dp),
        ) {
            Text(text = title, style = COUITheme.textStyles.headlineM, color = colors.labelPrimary)
            if (message != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = COUITheme.textStyles.bodyM,
                    color = colors.labelSecondary,
                    textAlign = TextAlign.Center,
                )
            } else {
                Spacer(Modifier.height(16.dp))
                TypingIndicator()
            }
            if (actions != null) {
                Spacer(Modifier.height(22.dp))
                actions()
            }
        }
    }
}

@Composable
private fun ConversationBody(
    hub: ConversationHub,
    conversation: Conversation,
    padding: PaddingValues,
    listState: LazyListState,
    onOpenLogin: () -> Unit,
    find: ChatFindState,
    onOpenTasks: (String) -> Unit,
    onOpenSubagent: (String, String) -> Unit,
) {
    // 回答流式输出时条目每秒变好几次；界面看不见时这一整页不跟着重组、测量（贴底跟随还会强制测量列表）
    val items = whileStarted { conversation.items }
    // 下面记住的这个对话的状态（草稿、消息操作、Markdown 缓存、引用路径等）不另以 conversation 做键：
    // ConversationBody 只在 ChatScreen 的 key(conversation) 里调用，换了对话整块重建，这些状态跟着换一套
    // 每一组步骤拿到的是同一个函数，列表里的步骤组不因上层重组而跟着重组
    val latestOpenSubagent by rememberUpdatedState(onOpenSubagent)
    val openSubagent = remember { { id: String -> latestOpenSubagent(conversation.key, id) } }
    // 输入栏的内容（文字、图片、附带的路径）是这个对话的草稿，由 hub 按对话保管：切走再回来、Zeta 重开后都还在
    val draft = remember { hub.drafts.of(conversation) }
    val input = draft.input
    val uploads = draft.uploads
    val markdownCache = remember { StreamingMarkdownStateCache() }
    val scope = rememberCoroutineScope()
    // 附件菜单里的“文件夹”“输入路径”：引用 Linux 里本来就有的路径
    val pathAttach = remember { PathAttach() }
    // 选的、分享来的文件复制进对话所在的主机，让 Claude 用工具去读；图片另带一张缩小的随消息给模型先看。截屏只当图片附件。
    // 读图片、复制文件在草稿那边跑，切到别的对话也会做完，落进这份草稿
    val sink = remember {
        IntakeSink(hub, hub.drafts.scope, draft, MaxAttachments, conversation.backend)
    }
    // 编辑、重新生成、删除一轮、分支
    val actions = remember { MessageActions(hub, conversation, draft, scope) }
    val pickers = ChatPlatform.current.rememberFilePickers(sink::addFiles)
    // 从外面送进来、定了放进这个对话的东西（分享、用 Zeta 打开、截屏）
    val incoming = hub.incoming
    LaunchedEffect(incoming) {
        if (incoming == null) return@LaunchedEffect
        hub.incoming = null
        sink.accept(incoming)
    }
    val home = hub.home(conversation.backend)
    val imagesOf =
        remember(conversation.cwd, home) {
            { text: String -> answerImages(text, conversation.cwd, home) }
        }
    // 撤回的排队消息（点了它，或按了停止）：放回输入框，接在已有草稿前面
    LaunchedEffect(conversation.recalled) {
        conversation.takeRecalled()?.let(sink::putBack)
    }
    val streamingAnswers =
        remember(items) {
            items.mapNotNullTo(HashSet()) {
                (it as? AnswerItem)?.takeIf { answer -> answer.streaming }?.id
            }
        }
    LaunchedEffect(markdownCache, streamingAnswers) {
        markdownCache.discardWhenUnused(streamingAnswers)
    }
    SaveDraft(hub.drafts, draft, actions)
    val prompts = rememberPromptsUi(input)
    // 按值比较：回答逐字增长时各轮能做的操作不变，TurnIndex 还是原来那个，屏幕上别的消息不跟着重组
    val turnList = remember(items) { userTurns(items) }
    val turns = remember(turnList) { TurnIndex(turnList) }
    val shown = actions.visible(items)
    val leading = if (conversation.earlier != null) 1 else 0
    // 贴底跟随：发消息后跟到这条消息顶到上沿为止，点「回到底部」后一直跟，往上拖就停（见 FollowBottom）
    val latestShown by rememberUpdatedState(shown)
    val latestLeading by rememberUpdatedState(leading)
    val follow =
        rememberFollowBottom(listState) {
            val at = latestShown.indexOfLast { it is UserItem }
            if (at < 0) null else FollowAnchor(latestShown[at].id, at + latestLeading)
        }
    ScrollToEndOnKeyboard(listState)
    // 长对话先只载入最近的一段，往上翻到顶附近时再接上更早的；“更早的记录”那行是列表的第一项
    val earlierPaging = rememberEarlierPaging(conversation, listState, onLoad = follow::pause)
    // 列表到底后接着上拖调起输入法，输入法开着时下拖收起
    val composerFocus = remember { FocusRequester() }
    val swipeKeyboard = rememberSwipeKeyboard(composerFocus)
    KeepReadingPosition(listState, shown.firstOrNull()?.id) { key ->
        listIndexOf(shown, key, leading)
    }
    ChatFindDriver(
        find = find,
        items = shown,
        answerText = { imagesOf(it).text },
        listState = listState,
        leading = leading,
        topInset = padding.calculateTopPadding(),
        onJump = follow::pause,
    )
    LoadEarlierWhileFinding(find, conversation)

    // 记住同一份：列表里各条消息的内容不因上层重组而换成新的、跟着重组
    val rows =
        remember(conversation, turns, actions, prompts, markdownCache, imagesOf, openSubagent) {
            ChatRows(conversation, turns, actions, prompts, markdownCache, imagesOf, openSubagent)
        }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().findViewport(find)) {
            ChatContent(
                hub = hub,
                conversation = conversation,
                draft = draft,
                items = items,
                shown = shown,
                listState = listState,
                padding = padding,
                earlierPaging = earlierPaging,
                swipeKeyboard = swipeKeyboard,
                follow = follow,
                rows = rows,
                onOpenLogin = onOpenLogin,
            )
            BackToBottomButton(
                visible = !follow.following && listState.canScrollForward && items.isNotEmpty(),
                onClick = follow::resume,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
        val bottomModifier =
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
        if (find.active) {
            ChatFindBar(
                find = find,
                earlierLeft = conversation.skippedHistory,
                earlierProblem = (conversation.earlierState as? EarlierState.Failed)?.message,
                modifier = bottomModifier,
            )
        } else if (conversation.blockedByExternal) {
            ExternalSessionNotice(conversation, modifier = bottomModifier)
        } else
            ConversationInput(
                hub = hub,
                conversation = conversation,
                items = items,
                draft = draft,
                actions = actions,
                follow = follow,
                prompts = prompts,
                pathAttach = pathAttach,
                onPickImages = pickers::pickImages,
                onPickFiles = pickers::pickFiles,
                onOpenTasks = onOpenTasks,
                composerFocus = composerFocus,
                modifier = bottomModifier,
            )
    }
    MessageActionDialogs(actions)
    IntakeProblemsDialog(sink)
    SavedPromptsDialogs(prompts)
    AttachPathDialogs(pathAttach, hub, conversation, onAttach = uploads::addPath)
}

/** 消息 [key] 在聊天列表里是第几项（前面有 [leading] 项不是消息）；不在了是 -1。 */
private fun listIndexOf(shown: List<ChatItem>, key: Any, leading: Int): Int =
    shown.indexOfFirst { it.id == key }.let { if (it < 0) -1 else it + leading }

/** 查找查整个对话：打开查找栏时把还没载入的更早记录一次载完（整份记录只读一遍），之后只在载入的内容里查。 没读成就停下，查找栏里说明；点“更早的记录”那行重试成功后接着载。 */
@Composable
private fun LoadEarlierWhileFinding(find: ChatFindState, conversation: Conversation) {
    LaunchedEffect(find.active) {
        if (!find.active) return@LaunchedEffect
        snapshotFlow {
            conversation.earlier.takeIf {
                conversation.earlierState == EarlierState.Idle && !conversation.loadingHistory
            }
        }
            .collect { if (it != null) conversation.loadEarlier(rest = true) }
    }
}

/** 输入栏上面那一块：载入历史时转圈，空对话是起始页，有内容就是聊天列表。 */
@Composable
private fun ChatContent(
    hub: ConversationHub,
    conversation: Conversation,
    draft: ComposerDraft,
    items: List<ChatItem>,
    shown: List<ChatItem>,
    listState: LazyListState,
    padding: PaddingValues,
    earlierPaging: NestedScrollConnection,
    swipeKeyboard: NestedScrollConnection,
    follow: FollowBottom,
    rows: ChatRows,
    onOpenLogin: () -> Unit,
) {
    when {
        conversation.loadingHistory ->
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                TypingIndicator()
            }
        items.isEmpty() ->
            EmptyState(
                hub = hub,
                cwd = conversation.cwd,
                // 登录状态查的是手机上的 Claude Code；远程主机上的登没登录，发了才知道
                needsLogin =
                    conversation.backend == Backends.LOCAL && hub.platform.local?.loggedOut == true,
                onLogin = onOpenLogin,
                onChangeCwd = { hub.startNew(it) },
                modifier = Modifier.padding(padding),
            )
        else ->
            ConversationList(
                conversation = conversation,
                items = items,
                shown = shown,
                listState = listState,
                padding = padding,
                earlierPaging = earlierPaging,
                swipeKeyboard = swipeKeyboard,
                follow = follow,
                rows = rows,
            )
    }
}

/** 不贴底时浮在列表底部的“回到底部”按钮（COUI 17 的浮动圆形按钮：顶栏按钮那种材质，阴影、边缘光、按压回弹）。 */
@Composable
private fun BackToBottomButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.82f),
        exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f),
    ) {
        IconActionButton(
            ZetaIcons.ArrowDown,
            Strings.chatScrollToBottom(),
            iconModifier = Modifier.size(18.dp),
            onClick = onClick,
        )
    }
}

/** 聊天列表里画一条消息要用的东西：这个对话、各轮能做的操作、Markdown 缓存、回答里的图片和打开子代理查看页。 */
private class ChatRows(
    val conversation: Conversation,
    val turns: TurnIndex,
    val actions: MessageActions,
    val prompts: PromptsUi,
    val markdownCache: StreamingMarkdownStateCache,
    val imagesOf: (String) -> AnswerImages,
    val openSubagent: (String) -> Unit,
)

/** 有内容的对话：“更早的记录”那行、各条消息，在等输出时最后是等待行。 */
@Composable
private fun ConversationList(
    conversation: Conversation,
    items: List<ChatItem>,
    shown: List<ChatItem>,
    listState: LazyListState,
    padding: PaddingValues,
    earlierPaging: NestedScrollConnection,
    swipeKeyboard: NestedScrollConnection,
    follow: FollowBottom,
    rows: ChatRows,
) {
    // 每秒看一次离上次输出多久，够长要显示时才有值：回答流式输出时这一页不因它重组
    val idleSeconds by
        produceState<Int?>(null, conversation.busy) {
            value = null
            while (conversation.busy) {
                delay(1000)
                value =
                    conversation.lastActivityAt
                        ?.let { ((System.currentTimeMillis() - it) / 1000).toInt() }
                        ?.takeIf { it >= IdleHintSeconds }
            }
        }
    val statusText = conversation.status ?: idleSeconds?.let { Strings.chatWaitedSeconds(it) }
    val waiting = showsWaiting(conversation, items.last(), statusText)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(earlierPaging).nestedScroll(swipeKeyboard),
        contentPadding =
            PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = 16.dp,
            ),
    ) {
        conversation.earlier?.let { earlier ->
            item(key = EarlierRowKey, contentType = EarlierRowKey) {
                EarlierHistoryRow(
                    skipped = earlier,
                    state = conversation.earlierState,
                    onLoad = {
                        follow.pause()
                        conversation.loadEarlier()
                    },
                )
            }
        }
        items(shown, key = { it.id }, contentType = { it::class }) { item -> ChatRow(item, rows) }
        if (waiting) item(key = "waiting") { WaitingRow(statusText) }
    }
}

/** 要不要在列表末尾显示等待行：这一轮在跑，又看不出在出东西（有状态说明，或最后一条不是正在写的回答、正在跑的步骤）。 等的是用户自己（确认或提问弹层开着）时不算"已等待"。 */
private fun showsWaiting(conversation: Conversation, last: ChatItem, statusText: String?): Boolean {
    if (!conversation.busy || conversation.pending != null) return false
    if (statusText != null) return true
    val producing =
        (last is AnswerItem && last.streaming) ||
            (last is ThoughtItem && last.streaming) ||
            (last is WorkItem && last.running)
    return !producing
}

/** 聊天列表里的一条：提问气泡、回答、一组步骤或提示，后面跟着这一轮能做的操作。 */
@Composable
private fun ChatRow(item: ChatItem, rows: ChatRows) {
    val conversation = rows.conversation
    val turns = rows.turns
    val actions = rows.actions
    when (item) {
        is UserItem -> {
            val turn =
                turns.byUser[item.id]?.takeIf {
                    it.user.rewindable && conversation.canRewind && actions.editing == null
                }
            UserBubble(
                item,
                editing = actions.editing?.user?.id == item.id,
                onEdit = turn?.let { { actions.beginEdit(it) } },
                onDelete = turn?.let { { actions.askDelete(it) } },
                onSave = { rows.prompts.saveMessage(item.text) },
            )
        }
        is AnswerItem ->
            AnswerBlock(
                item,
                rows.markdownCache,
                imagesOf = rows.imagesOf,
                actions = {
                    ReadAloudButton(item)
                    TurnActionButtons(item.id, turns, conversation, actions)
                },
            )
        is ThoughtItem ->
            Column {
                ThoughtBlock(item, rows.markdownCache, imagesOf = rows.imagesOf)
                TurnActionRow(item.id, turns, conversation, actions)
            }
        is WorkItem ->
            Column {
                WorkGroup(item, onOpenSubagent = rows.openSubagent)
                TurnActionRow(item.id, turns, conversation, actions)
            }
        is NoticeItem ->
            Column {
                NoticeRow(item)
                TurnActionRow(item.id, turns, conversation, actions)
            }
    }
}

/** 列表末尾的等待行：跳动的点，有说明（状态或已等待多久）时跟在后面。 */
@Composable
private fun WaitingRow(statusText: String?) {
    Row(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypingIndicator()
        if (statusText != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = statusText,
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

/** 输入区：后台屏幕、后台任务、待办、编辑和排队几条横条，下面是输入栏，加上用量对话框。 */
@Composable
private fun ConversationInput(
    hub: ConversationHub,
    conversation: Conversation,
    items: List<ChatItem>,
    draft: ComposerDraft,
    actions: MessageActions,
    follow: FollowBottom,
    prompts: PromptsUi,
    pathAttach: PathAttach,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onOpenTasks: (String) -> Unit,
    composerFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val input = draft.input
    val attachments = draft.attachments
    val uploads = draft.uploads
    Column(modifier = modifier) {
        var viewingUsage by remember { mutableStateOf(false) }
        var viewingCpu by remember { mutableStateOf(false) }
        // 负载标识只在对话里有了消息以后显示、去问：还没发过消息的新对话不为它连主机
        val hasMessages = items.isNotEmpty()
        val hostCpu = rememberHostCpu(hub, conversation.backend, active = hasMessages)
        ChatPlatform.current.ConversationStrip(hub, conversation, Modifier.padding(bottom = 6.dp))
        BackgroundTaskStrip(
            conversation,
            onOpen = { onOpenTasks(conversation.key) },
            modifier = Modifier.padding(bottom = 6.dp),
        )
        TodoStrip(
            conversation.todos,
            conversation.busy,
            Modifier.padding(bottom = 6.dp),
        )
        actions.editing?.let {
            EditStrip(
                it,
                onCancel = actions::cancelEdit,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        QueueStrip(
            queued = conversation.queued,
            steering = conversation.steering,
            onSteer = conversation::steerNow,
            onRecall = { conversation.cancelQueued(it.uuid, recall = true) },
            onRemove = { conversation.cancelQueued(it.uuid, recall = false) },
            modifier = Modifier.padding(bottom = 6.dp),
        )
        val voice = rememberHoldToTalk()
        Composer(
            state = input,
            busy = conversation.busy,
            enabled = !conversation.loadingHistory && !conversation.rewinding,
            attachments = attachments,
            files = uploads,
            commands = conversation.commands,
            onPickImages = onPickImages,
            onPickFiles = onPickFiles,
            // 手机上的文件夹只有手机本机的 Debian 绑得进去
            onPickFolder = pathAttach::openFolder.takeIf { conversation.backend == Backends.LOCAL },
            onEnterPath = pathAttach::openPath,
            onOpenPrompts = prompts::open,
            onRemoveAttachment = { attachments.remove(it) },
            onRemoveFile = { uploads.remove(it) },
            onSlashTyped = conversation::prepare,
            onSend = { text ->
                follow.followTurn()
                sendDraft(text, hub, conversation, draft, actions)
            },
            onStop = conversation::interrupt,
            tools = {
                PermissionModeChip(
                    mode = conversation.shownPermissionMode,
                    onChange = conversation::choosePermissionMode,
                )
                // 载入历史期间不能改：载完要照会话记录恢复思考强度
                EffortChip(
                    effort = conversation.effort,
                    applied = conversation.usage.appliedEffort,
                    enabled = !conversation.loadingHistory,
                    onChange = conversation::chooseEffort,
                )
            },
            status = {
                if (hasMessages) HostCpuButton({ hostCpu.value }, onClick = { viewingCpu = true })
                UsageButton(conversation.usage, onClick = { viewingUsage = true })
            },
            voice = voice,
            focus = composerFocus,
        )
        HostCpuDialog(
            visible = viewingCpu,
            host = hub.backendName(conversation.backend),
            cpu = { hostCpu.value },
            onDismissRequest = { viewingCpu = false },
        )
        UsageDialog(
            visible = viewingUsage,
            usage = conversation.usage,
            skippedHistory = conversation.skippedHistory,
            canCompact = !conversation.busy && !conversation.loadingHistory && items.isNotEmpty(),
            onCompact = conversation::compact,
            onDismissRequest = { viewingUsage = false },
            quota = { QuotaDialogSection(hub, conversation.backend, visible = viewingUsage) },
        )
    }
}

/** 发出输入栏里的内容（正在编辑旧消息时是改写那一轮）；还不能改写时内容留在输入框里。发出去了就清空附件，马上存草稿。 */
private fun sendDraft(
    text: String,
    hub: ConversationHub,
    conversation: Conversation,
    draft: ComposerDraft,
    actions: MessageActions,
) {
    val images = draft.outgoingImages()
    val files = draft.outgoingFiles()
    if (actions.editing == null) {
        conversation.send(text, images, files)
    } else if (!actions.submitEdit(text, images, files)) {
        // 还不能改写：内容留在输入框里
        draft.input.setTextAndPlaceCursorAtEnd(text)
        return
    }
    draft.attachments.clear()
    draft.uploads.clear()
    // 发出去了：存着的那份马上清掉，不等停手存盘
    hub.drafts.save(draft)
}

private const val MaxAttachments = 4

/** 一轮里多久没有输出就在等待行上显示“已等待 N 秒”。 */
private const val IdleHintSeconds = 15

/** 停手这么久后把草稿存盘。 */
private const val DraftSaveDelayMs = 600L

/**
 * 输入栏的内容随手存盘（[ComposerDrafts]）：内容变了、停手 [DraftSaveDelayMs] 后存一次（只挪光标不算），离开这个对话（切走、界面重建）时马上存。
 * 编辑旧消息时输入框里是那条消息，不存，存着的仍是编辑前的草稿；离开时先取消编辑，把编辑前的草稿放回来再存。
 */
@Composable
private fun SaveDraft(drafts: ComposerDrafts, draft: ComposerDraft, actions: MessageActions) {
    LaunchedEffect(draft) {
        snapshotFlow {
                if (actions.editing != null) null
                else
                    listOf(
                        draft.input.text.toString(),
                        draft.attachments.map { it.id },
                        draft.uploads.map { it.path to it.failed },
                    )
            }
            .drop(1)
            .collectLatest { shape ->
                if (shape == null) return@collectLatest
                delay(DraftSaveDelayMs)
                drafts.save(draft)
            }
    }
    DisposableEffect(draft) {
        onDispose {
            actions.cancelEdit()
            drafts.save(draft)
        }
    }
}

/** 引用 Linux 里已有的一个路径（不用上传，一开始就是传好的样子）；已经引用过的不再加。 */
fun MutableList<FileUpload>.addPath(path: String) {
    if (none { it.path == path })
        add(
            FileUpload(UUID.randomUUID().toString(), AttachedPaths.label(path)).also {
                it.path = path
            },
        )
}

/**
 * 顶栏中间：有标题时是“标题 + 模型”两行，新对话时是模型胶囊；点击弹出模型菜单。 显示的是 [Conversation.shownModel]。接旧会话又没在这个对话里选过模型时，模型由
 * Claude Code 照会话记录定，菜单不勾任何一项。 菜单只直接列各系列最新的，旧版本收进「更多模型」二级菜单（[choiceEntries]）；选的是旧版本时那一项下面写着它。
 */
@Composable
private fun ChatHeader(conversation: Conversation, host: String?, models: List<ModelOption>) {
    val colors = COUITheme.colorScheme
    val selected =
        conversation.model
            ?: Conversation.DEFAULT_MODEL.takeUnless { conversation.modelFromSession }
    val modelName = conversation.shownModelName(models)
    var expanded by remember { mutableStateOf(false) }
    val entries =
        remember(models, selected, conversation) {
            val choices = modelChoices(models)
            choiceEntries(choices.latest, choices.older, selected) { conversation.chooseModel(it) }
        }
    val source = remember { MutableInteractionSource() }
    Box {
        if (conversation.title.isNotBlank()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier.clip(RoundedCornerShape(COUIRoundCorner.M))
                        .clickable(interactionSource = source, indication = null) {
                            expanded = true
                        }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = conversation.title,
                    style = COUITheme.textStyles.headlineXS,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = host?.let { "$it · $modelName" } ?: modelName,
                        style = COUITheme.textStyles.bodyXS,
                        color = colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        ZetaIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = colors.labelTertiary,
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.heightIn(min = 34.dp)
                        .clip(CircleShape)
                        .background(colors.container4, CircleShape)
                        .clickable(interactionSource = source, indication = null) {
                            expanded = true
                        }
                        .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "Claude · $modelName",
                    style = COUITheme.textStyles.buttonM,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    ZetaIcons.ChevronDown,
                    contentDescription = Strings.chatSwitchModel(),
                    modifier = Modifier.padding(start = 2.dp).size(16.dp),
                    tint = colors.labelSecondary,
                )
            }
        }
        AnchoredMenu(show = expanded, entries = entries, onDismiss = { expanded = false })
    }
}

/** 顶栏右边的“更多”：新对话、结束对话（终端里的 /exit）、在对话中查找、设置。 */
@Composable
private fun OverflowMenu(
    onNewChat: () -> Unit,
    onEnd: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onFind: (() -> Unit)?,
) {
    TopBarMoreMenu(
        listOf(
            DropdownEntry(
                items =
                    listOf(
                        menuItem(Strings.chatNewChat(), ZetaIcons.NewChat, onNewChat),
                        menuItem(
                            Strings.chatEndChat(),
                            ZetaIcons.Exit,
                            { onEnd?.invoke() },
                            enabled = onEnd != null,
                        ),
                        menuItem(
                            Strings.chatFindInChat(),
                            COUIIcons.Search,
                            { onFind?.invoke() },
                            enabled = onFind != null,
                        ),
                        menuItem(Strings.chatSettings(), COUIIcons.Settings, onOpenSettings),
                    ),
            ),
        ),
    )
}

@Composable
private fun EmptyState(
    hub: ConversationHub,
    cwd: String,
    needsLogin: Boolean,
    onLogin: () -> Unit,
    onChangeCwd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = COUITheme.colorScheme
    var editingCwd by remember { mutableStateOf(false) }
    var cwdDraft by remember { mutableStateOf(cwd) }
    val scope = rememberCoroutineScope()
    // 键盘弹出后地方不够时内容照原样排、能上下滚，不让 Column 把下面的登录提示压扁；地方够时照旧居中（底下留 56）
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = Strings.chatEmptyGreeting(),
                style = COUITheme.textStyles.displayXS,
                color = colors.labelPrimary,
            )
            Spacer(Modifier.height(8.dp))
            // 加过远程主机时：这个新对话开在哪台主机上，点开换一台
            HostSwitcher(hub, modifier = Modifier.padding(bottom = 4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.clip(CircleShape)
                        .clickable {
                            cwdDraft = cwd
                            editingCwd = true
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(
                    ZetaIcons.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = colors.labelSecondary,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = cwd,
                    style = COUITheme.textStyles.bodyS,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            }
            // 还没登录时发什么都只会报错，先把人领去登录
            if (needsLogin) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = Strings.chatNotSignedIn(),
                    style = COUITheme.textStyles.bodyM,
                    color = colors.labelSecondary,
                )
                Spacer(Modifier.height(14.dp))
                TextButton(
                    text = Strings.chatSignIn(),
                    onClick = onLogin,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
    TextInputDialog(
        show = editingCwd,
        title = Strings.chatCwdTitle(),
        summary =
            if (hub.backend == Backends.LOCAL) Strings.chatCwdLocal()
            else Strings.chatCwdRemote(hub.backendName(hub.backend)),
        value = cwdDraft,
        onValueChange = { cwdDraft = it },
        label = "/workspace",
        confirmText = Strings.chatOk(),
        confirmEnabled = cwdDraft.trim().startsWith("/"),
        onConfirm = {
            val path = cwdDraft.trim()
            val backend = hub.current.backend
            // 先看那台上进不进得去：进不去就留着对话框让用户改，不然发出消息才知道起不来
            scope.launch {
                val problem = withContext(Dispatchers.IO) { CwdProblem.check(hub, backend, path) }
                if (problem == null) {
                    editingCwd = false
                    if (path != cwd) onChangeCwd(path)
                } else hub.platform.toast(problem)
            }
        },
        onDismissRequest = { editingCwd = false },
    )
}
