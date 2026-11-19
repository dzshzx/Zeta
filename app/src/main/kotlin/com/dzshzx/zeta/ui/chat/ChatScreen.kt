package com.dzshzx.zeta.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.ComposerDraft
import com.dzshzx.zeta.core.ComposerDrafts
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EarlierState
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.core.turnActions
import com.dzshzx.zeta.ui.components.coui2.AnchoredMenu
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.LocalPageBackdrop
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.components.coui2.TopBarMoreMenu
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.menuItem
import com.dzshzx.zeta.ui.components.coui2.rememberPageBackdrop
import com.dzshzx.zeta.ui.components.coui2.rememberPopupMaterialBlur
import com.dzshzx.zeta.ui.markdown.LocalTextFinder
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import com.dzshzx.zeta.ui.tasks.BackgroundTaskStrip
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.TopAppBarGlow
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.effect.LocalPopupMaterialBlur
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Merge
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.icon.extended.Sidebar
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 还没连上进程、拿不到真实列表时先给的几个常用别名（Claude Code 自己解析）。 */
private val FallbackModels = listOf(
    ModelOption("default", "默认", ""),
    ModelOption("fable", "Fable", ""),
    ModelOption("opus", "Opus", ""),
    ModelOption("sonnet", "Sonnet", ""),
    ModelOption("haiku", "Haiku", ""),
)

/**
 * 聊天页。根上是 COUI 的 Scaffold（页面上的对话框、菜单都画在它里面），顶栏是 COUI 的小顶栏，中间换成对话标题和模型切换。
 * 内容录进页面背景（[LocalPageBackdrop]），顶栏光晕、对话框和菜单拿它做毛玻璃；做法同 CouiPageScaffold，
 * 只是聊天页铺整页底色（background），输入栏在内容里跟着键盘走。
 */
@Composable
internal fun ChatScreen(
    hub: ConversationHub,
    onOpenPane: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInstall: () -> Unit,
    onOpenLogin: () -> Unit,
    /** 打开后台任务面板，参数是对话的 key。 */
    onOpenTasks: (String) -> Unit = {},
    /** 打开子代理查看页：对话的 key、发起子代理的那一步（tool_use id）。 */
    onOpenSubagent: (String, String) -> Unit = { _, _ -> },
) {
    val conversation = hub.current
    val colors = COUITheme.colorScheme
    val find = remember(conversation.key) { ChatFindState() }
    val listState = remember(conversation.key) { LazyListState() }
    val backdrop = rememberPageBackdrop()
    val popupBlur = rememberPopupMaterialBlur(backdrop)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            // 顶栏在页面录制范围之外：它的菜单可以模糊页面
            CompositionLocalProvider(LocalPageBackdrop provides backdrop, LocalPopupMaterialBlur provides popupBlur) {
                ChatTopBar(
                    conversation = conversation,
                    models = hub.models.ifEmpty { FallbackModels },
                    backdrop = backdrop,
                    listState = listState.takeIf { hub.setup == SetupState.Ready },
                    onOpenPane = onOpenPane,
                    onNewChat = hub::startNew,
                    onOpenSettings = onOpenSettings,
                    onFind = find::open.takeIf { hub.setup == SetupState.Ready && conversation.items.isNotEmpty() },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(colors.background),
        ) {
            CompositionLocalProvider(LocalPageBackdrop provides backdrop, LocalPopupMaterialBlur provides popupBlur) {
                when (hub.setup) {
                    SetupState.Ready -> key(conversation.key) {
                        CompositionLocalProvider(LocalTextFinder provides find) {
                            ConversationBody(hub, conversation, padding, listState, onOpenLogin, find, onOpenTasks, onOpenSubagent)
                        }
                    }
                    SetupState.Checking -> SetupMessage(padding, "正在检查环境…", null)
                    SetupState.NoRoot -> SetupMessage(
                        padding,
                        "需要 root 权限",
                        "Zeta 通过 root 进入 Debian 环境来运行 Claude Code。请在 root 管理器（KernelSU / SukiSU / Magisk）里给 Zeta 授权，然后重试。",
                    ) { TextButton(text = "重试", onClick = hub::checkSetup) }
                    SetupState.NoEnvironment -> SetupMessage(
                        padding,
                        "还没有 Linux 环境",
                        "Zeta 在一个 Debian 环境里运行 Claude Code。可以让 Zeta 现在装一个（约 1 GB），或者在设置里指向已有的环境。",
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(
                                text = if (hub.ownStatus?.base == true) "继续安装 Debian 环境" else "安装 Debian 环境",
                                onClick = onOpenInstall,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(text = "打开设置", onClick = onOpenSettings)
                                TextButton(text = "重试", onClick = hub::checkSetup)
                            }
                        }
                    }
                }
                PermissionSheet(
                    request = conversation.pending,
                    onAllow = { request, answers -> conversation.respond(request, allow = true, answers = answers) },
                    onDeny = { request -> conversation.respond(request, allow = false) },
                )
            }
        }
    }
}

/** 顶栏光晕在内容往上滚过这么多之后全部显出（COUI 17 是 30dp）。 */
private val GlowRevealDistance = 30.dp

/**
 * 聊天页顶栏：COUI 小顶栏（左边历史对话，右边“更多”菜单），标题位置换成 [ChatHeader]。
 * 页面录进了 [backdrop] 时顶栏透明、带 COUI 17 光晕；光晕随聊天列表离开顶部的距离显出（[listState] 为 null 时不显）。
 */
@Composable
private fun ChatTopBar(
    conversation: Conversation,
    models: List<ModelOption>,
    backdrop: LayerBackdrop?,
    listState: LazyListState?,
    onOpenPane: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onFind: (() -> Unit)?,
) {
    val colors = COUITheme.colorScheme
    val revealPx = with(LocalDensity.current) { GlowRevealDistance.toPx() }
    val scrolled by remember(listState, revealPx) {
        derivedStateOf {
            when {
                listState == null -> 0f
                listState.firstVisibleItemIndex > 0 -> revealPx
                else -> listState.firstVisibleItemScrollOffset.toFloat().coerceAtMost(revealPx)
            }
        }
    }
    val glow: (@Composable BoxScope.() -> Unit)? = if (backdrop != null) {
        { TopAppBarGlow(backdrop = backdrop, scrollOffsetPx = scrolled, isDark = colors.isDark, baseColor = colors.background, enabled = true) }
    } else {
        null
    }
    Box {
        SmallTopAppBar(
            title = "",
            color = if (backdrop != null) Color.Transparent else colors.background,
            glow = glow,
            navigationIcon = { IconActionButton(COUIIcons.Sidebar, "历史对话", onClick = onOpenPane) },
            actions = { OverflowMenu(onNewChat = onNewChat, onOpenSettings = onOpenSettings, onFind = onFind) },
        )
        // 标题位置：两边让出图标按钮，竖直方向和顶栏的 52dp 工具栏对齐
        Box(
            modifier = Modifier
                .matchParentSize()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                .padding(horizontal = 72.dp),
            contentAlignment = Alignment.Center,
        ) {
            ChatHeader(conversation = conversation, models = models)
        }
    }
}

@Composable
private fun SetupMessage(padding: PaddingValues, title: String, message: String?, actions: (@Composable () -> Unit)? = null) {
    val colors = COUITheme.colorScheme
    Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 56.dp)) {
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
    val items = conversation.items
    // 每一组步骤拿到的是同一个函数，列表里的步骤组不因上层重组而跟着重组
    val latestOpenSubagent by rememberUpdatedState(onOpenSubagent)
    val openSubagent = remember(conversation.key) { { id: String -> latestOpenSubagent(conversation.key, id) } }
    // 输入栏的内容（文字、图片、附带的路径）是这个对话的草稿，由 hub 按对话保管：切走再回来、Zeta 重开后都还在
    val draft = remember { hub.drafts.of(conversation) }
    val input = draft.input
    val markdownCache = remember { StreamingMarkdownStateCache() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val attachments = draft.attachments
    val uploads = draft.uploads
    // 附件菜单里的“文件夹”“输入路径”：引用 Linux 里本来就有的路径
    val pathAttach = remember { PathAttach() }
    // 选的、分享来的、截屏：图片当作图片发给模型看，别的文件复制进 Linux 环境，让 Claude 用工具去读。
    // 读图片、复制文件在草稿那边跑，切到别的对话也会做完，落进这份草稿
    val sink = remember { IntakeSink(hub, context, hub.drafts.scope, input, attachments, uploads, MaxAttachments) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MaxAttachments)) { uris -> sink.addImages(uris) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> sink.addFiles(uris) }
    // 从外面送进来、定了放进这个对话的东西（分享、用 Zeta 打开、截屏）
    val incoming = hub.incoming
    LaunchedEffect(incoming) {
        if (incoming == null) return@LaunchedEffect
        hub.incoming = null
        sink.accept(incoming)
    }
    val home = hub.settings.env?.home
    val imagesOf = remember(conversation.cwd, home) { { text: String -> answerImages(text, conversation.cwd, home) } }
    // 贴底跟随：内容增长时自动滚到底；用户向上拖动后停止，回到底部或点按钮后恢复
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> follow = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> if (!listState.canScrollForward) follow = true
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canForward) ->
            if (!scrolling && !canForward) follow = true
        }
    }
    LaunchedEffect(listState, follow) {
        if (follow) {
            snapshotFlow { listState.canScrollForward }.collect { canForward ->
                if (canForward) listState.scrollToEnd()
            }
        }
    }
    // 撤回的排队消息（点了它，或按了停止）：放回输入框，接在已有草稿前面
    LaunchedEffect(conversation.recalled) {
        val back = conversation.takeRecalled() ?: return@LaunchedEffect
        val typed = input.text.toString()
        input.setTextAndPlaceCursorAtEnd(listOf(back.text, typed).filter { it.isNotBlank() }.joinToString("\n"))
        attachments.addAll(back.attachments.filter { a -> attachments.none { it.id == a.id } }.take(MaxAttachments - attachments.size))
        back.files.forEach(uploads::addPath)
    }
    LaunchedEffect(items) {
        markdownCache.discardUnused(items.mapNotNullTo(HashSet()) { (it as? AnswerItem)?.takeIf { answer -> answer.streaming }?.id })
    }
    // 编辑、重新生成、删除一轮、分支
    val actions = remember { MessageActions(hub, conversation, input, attachments, uploads, scope, context) }
    SaveDraft(hub.drafts, draft, actions)
    val prompts = rememberPromptsUi(input)
    val turns = remember(items) { TurnIndex(turnActions(items)) }
    val shown = actions.visible(items)
    // 长对话先只载入最近的一段，往上翻到顶附近时再接上更早的
    val earlierPaging = rememberEarlierPaging(conversation, listState, onLoad = { follow = false })
    // 有过更早的记录就一直给“更早的记录”那行留着地方：全载入后这行没了，留白不收，正看着的内容不跟着往上跳
    val earlierRoom = remember(conversation) { BooleanArray(1) }
    if (conversation.earlier != null) earlierRoom[0] = true
    ChatFindDriver(
        find = find,
        items = shown,
        answerText = { imagesOf(it).text },
        listState = listState,
        topInset = padding.calculateTopPadding(),
        onJump = { follow = false },
        earlier = conversation.skippedHistory,
        earlierIdle = conversation.earlierState != EarlierState.Loading,
        itemOf = conversation::itemForEvent,
        findEarlier = conversation::findEarlier,
        loadEarlier = conversation::loadEarlier,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().findViewport(find)) {
            when {
                conversation.loadingHistory -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { TypingIndicator() }
                items.isEmpty() -> EmptyState(
                    cwd = conversation.cwd,
                    needsLogin = hub.account?.loggedIn == false,
                    onLogin = onOpenLogin,
                    onSuggestion = {
                        conversation.send(it)
                        // 新对话就此开始：输入框里没发的字归这个对话，下一个新对话从空的开始
                        hub.drafts.save(draft)
                    },
                    onChangeCwd = { hub.startNew(it) },
                    modifier = Modifier.padding(padding),
                )
                else -> {
                    val last = items.last()
                    val idleSeconds by produceState(0, conversation.busy, conversation.lastActivityAt) {
                        value = 0
                        while (conversation.busy) {
                            delay(1000)
                            value = ((System.currentTimeMillis() - conversation.lastActivityAt) / 1000).toInt()
                        }
                    }
                    val statusText = conversation.status ?: if (idleSeconds >= 15) "已等待 $idleSeconds 秒" else null
                    // 等的是用户自己（确认或提问弹层开着）时不算"已等待"
                    val waiting = conversation.busy && conversation.pending == null &&
                        (statusText != null || !((last is AnswerItem && last.streaming) || (last is WorkItem && last.running)))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().nestedScroll(earlierPaging),
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() + 4.dp + if (earlierRoom[0]) EarlierRowHeight else 0.dp,
                            bottom = 16.dp,
                        ),
                    ) {
                        items(shown, key = { it.id }, contentType = { it::class }) { item ->
                            when (item) {
                                is UserItem -> {
                                    val turn = turns.byUser[item.id]?.takeIf { it.rewindable && conversation.canRewind && actions.editing == null }
                                    UserBubble(
                                        item,
                                        editing = actions.editing?.user?.id == item.id,
                                        onEdit = turn?.let { { actions.beginEdit(it) } },
                                        onDelete = turn?.let { { actions.askDelete(it) } },
                                        onSave = { prompts.saveMessage(item.text) },
                                    )
                                }
                                is AnswerItem -> AnswerBlock(item, markdownCache, imagesOf = imagesOf, actions = {
                                    ReadAloudButton(item)
                                    TurnActionButtons(item.id, turns, conversation, actions)
                                })
                                is WorkItem -> Column {
                                    WorkGroup(item, onOpenSubagent = openSubagent)
                                    TurnActionRow(item.id, turns, conversation, actions)
                                }
                                is NoticeItem -> Column {
                                    NoticeRow(item)
                                    TurnActionRow(item.id, turns, conversation, actions)
                                }
                            }
                        }
                        if (waiting) {
                            item(key = "waiting") {
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
                        }
                    }
                    // “更早的记录”那行画在列表外面、贴在第 0 项上方（为什么见 EarlierHistory.kt）
                    conversation.earlier?.let { earlier ->
                        EarlierHistoryRow(
                            skipped = earlier.skipped,
                            state = conversation.earlierState,
                            onLoad = {
                                follow = false
                                conversation.loadEarlier()
                            },
                            modifier = Modifier.aboveFirstItem(listState),
                        )
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !follow && listState.canScrollForward && items.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.82f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f),
            ) {
                // COUI 17 的浮动圆形按钮（顶栏按钮那种材质：阴影、边缘光、按压回弹）
                IconActionButton(ZetaIcons.ArrowDown, "回到底部", iconModifier = Modifier.size(18.dp)) {
                    follow = true
                    scope.launch { listState.scrollToEnd() }
                }
            }
        }
        if (find.active) {
            ChatFindBar(
                find = find,
                loadingEarlier = conversation.earlierState == EarlierState.Loading,
                earlierProblem = (conversation.earlierState as? EarlierState.Failed)?.message,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            )
        } else if (conversation.blockedByExternal) {
            ExternalSessionNotice(
                onTakeOver = conversation::takeOver,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            )
        } else Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        ) {
            var viewingScreen by remember { mutableStateOf(false) }
            var viewingUsage by remember { mutableStateOf(false) }
            // 顶部胶囊正显示这个对话时，后台屏幕在胶囊里看和接管，这里不再重复一条
            if (hub.overlayFocus !== conversation.overlay) {
                BackgroundScreenStrip(phone = conversation.phone, onOpen = { viewingScreen = true }, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (viewingScreen) BackgroundScreenViewer(conversation.phone) { viewingScreen = false }
            BackgroundTaskStrip(conversation, onOpen = { onOpenTasks(conversation.key) }, modifier = Modifier.padding(bottom = 6.dp))
            TodoStrip(conversation.items, conversation.todoSeed, conversation.busy, Modifier.padding(bottom = 6.dp))
            actions.editing?.let { EditStrip(it, onCancel = actions::cancelEdit, modifier = Modifier.padding(bottom = 6.dp)) }
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
                commands = hub.commands,
                onPickImages = {
                    if (attachments.size < MaxAttachments) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                },
                onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                onPickFolder = pathAttach::openFolder,
                onEnterPath = pathAttach::openPath,
                onOpenPrompts = prompts::open,
                onRemoveAttachment = { attachments.remove(it) },
                onRemoveFile = { uploads.remove(it) },
                onSlashTyped = conversation::prepare,
                onSend = { text ->
                    follow = true
                    if (actions.editing == null) {
                        conversation.send(text, attachments.toList(), uploads.mapNotNull { it.path })
                    } else if (!actions.submitEdit(text, attachments.toList(), uploads.mapNotNull { it.path })) {
                        // 还不能改写：内容留在输入框里
                        input.setTextAndPlaceCursorAtEnd(text)
                        return@Composer
                    }
                    attachments.clear()
                    uploads.clear()
                    // 发出去了：存着的那份马上清掉，不等停手存盘
                    hub.drafts.save(draft)
                },
                onStop = conversation::interrupt,
                tools = {
                    PermissionModeChip(mode = conversation.permissionMode, onChange = conversation::choosePermissionMode)
                    // 载入历史期间不能改：载完要照会话记录恢复思考强度
                    EffortChip(
                        effort = conversation.effort,
                        applied = conversation.usage.appliedEffort,
                        enabled = !conversation.loadingHistory,
                        onChange = conversation::chooseEffort,
                    )
                },
                status = { UsageButtons(hub.quota.snapshot, conversation.usage, onClick = { viewingUsage = true }) },
                voice = voice,
            )
            UsageDialog(
                visible = viewingUsage,
                usage = conversation.usage,
                skippedHistory = conversation.skippedHistory,
                canCompact = !conversation.busy && !conversation.loadingHistory && conversation.items.isNotEmpty(),
                onCompact = conversation::compact,
                onDismissRequest = { viewingUsage = false },
                quota = { QuotaDialogSection(hub, visible = viewingUsage) },
            )
        }
    }
    MessageActionDialogs(actions)
    IntakeProblemsDialog(sink)
    SavedPromptsDialogs(prompts)
    AttachPathDialogs(pathAttach, hub, conversation, onAttach = uploads::addPath)
}

private const val MaxAttachments = 4

/** 停手这么久后把草稿存盘。 */
private const val DraftSaveDelayMs = 600L

/**
 * 输入栏的内容随手存盘（[ComposerDrafts]）：停手 [DraftSaveDelayMs] 后存一次，离开这个对话（切走、界面重建）时马上存。
 * 编辑旧消息时输入框里是那条消息，不存，存着的仍是编辑前的草稿；离开时先取消编辑，把编辑前的草稿放回来再存。
 */
@Composable
private fun SaveDraft(drafts: ComposerDrafts, draft: ComposerDraft, actions: MessageActions) {
    LaunchedEffect(draft) {
        snapshotFlow {
            if (actions.editing != null) null
            else listOf(draft.input.text.toString(), draft.input.selection, draft.attachments.map { it.id }, draft.uploads.map { it.path to it.failed })
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
internal fun MutableList<FileUpload>.addPath(path: String) {
    if (none { it.path == path }) add(FileUpload(UUID.randomUUID().toString(), AttachedPaths.label(path)).also { it.path = path })
}

/** 会话正在别处（比如终端里）运行时，输入栏的位置换成这张说明卡。 */
@Composable
private fun ExternalSessionNotice(onTakeOver: () -> Unit, modifier: Modifier = Modifier) {
    val colors = COUITheme.colorScheme
    val shape = remember { SquircleShape(ChatPanelCorner) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .chatPanel(shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text = "这个会话正在别处运行", style = COUITheme.textStyles.headlineXS, color = colors.labelPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "另一个 Claude Code 进程（比如终端里的）还开着它。在这里续聊会让两边同时写同一份记录，内容可能错乱。",
            style = COUITheme.textStyles.bodyS,
            color = colors.labelSecondary,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(text = "仍要在这里继续", onClick = onTakeOver)
    }
}

/** 滚到内容的最底端。只定位到最后一项不够：它比一屏还高时会停在它的顶部。 */
private suspend fun LazyListState.scrollToEnd() {
    scrollToItem((layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    scrollBy(1_000_000f)
}

/** 顶栏上模型的名字：选择器里有的用它的显示名，完整 ID 写成「Opus 5.5」，默认模型是「默认」。 */
internal fun modelLabel(value: String?, models: List<ModelOption>): String = when {
    value == null || value == Conversation.DEFAULT_MODEL -> "默认"
    else -> models.firstOrNull { it.value == value }?.displayName?.ifBlank { null } ?: readableModelId(value)
}

private val ModelId = Regex("claude-([a-z]+)-(\\d+(?:-\\d{1,2})*)(?:-\\d{8})?(\\[1m])?")

/** Claude Code 报的完整模型 ID（claude-opus-5-5、claude-haiku-4-5-20251001、claude-opus-5-5[1m]）写成人看的名字；认不出的原样。 */
internal fun readableModelId(id: String): String {
    val match = ModelId.matchEntire(id) ?: return id
    val (family, version, wide) = match.destructured
    return family.replaceFirstChar { it.uppercase() } + " " + version.replace('-', '.') + if (wide.isNotEmpty()) " (1M)" else ""
}

/**
 * 顶栏中间：有标题时是“标题 + 模型”两行，新对话时是模型胶囊；点击弹出模型菜单。
 * 显示的是 [Conversation.shownModel]；模型由 Claude Code 照会话记录定时菜单里不勾哪一项。载入历史期间不能点。
 */
@Composable
private fun ChatHeader(conversation: Conversation, models: List<ModelOption>) {
    val colors = COUITheme.colorScheme
    val selected = conversation.model ?: Conversation.DEFAULT_MODEL.takeUnless { conversation.modelFromSession }
    val modelName = modelLabel(conversation.shownModel, models)
    val enabled = !conversation.loadingHistory
    var expanded by remember { mutableStateOf(false) }
    val entries = listOf(
        DropdownEntry(
            items = models.map { model ->
                DropdownItem(
                    text = if (model.value == Conversation.DEFAULT_MODEL) "默认" else model.displayName.ifBlank { model.value },
                    selected = model.value == selected,
                    onClick = { conversation.chooseModel(model.value) },
                )
            },
        ),
    )
    val source = remember { MutableInteractionSource() }
    Box {
        if (conversation.title.isNotBlank()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(COUIRoundCorner.M))
                    .clickable(enabled = enabled, interactionSource = source, indication = null) { expanded = true }
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
                    Text(text = modelName, style = COUITheme.textStyles.bodyXS, color = colors.labelSecondary, maxLines = 1)
                    Icon(ZetaIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(13.dp), tint = colors.labelTertiary)
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .clip(CircleShape)
                    .background(colors.container4, CircleShape)
                    .clickable(enabled = enabled, interactionSource = source, indication = null) { expanded = true }
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "Claude · $modelName",
                    style = COUITheme.textStyles.buttonM,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(ZetaIcons.ChevronDown, contentDescription = "切换模型", modifier = Modifier.padding(start = 2.dp).size(16.dp), tint = colors.labelSecondary)
            }
        }
        AnchoredMenu(show = expanded, entries = entries, onDismiss = { expanded = false })
    }
}

/** 顶栏右边的“更多”：新对话、在对话中查找、设置。 */
@Composable
private fun OverflowMenu(onNewChat: () -> Unit, onOpenSettings: () -> Unit, onFind: (() -> Unit)?) {
    TopBarMoreMenu(
        listOf(
            DropdownEntry(
                items = listOf(
                    menuItem("新对话", ZetaIcons.NewChat, onNewChat),
                    menuItem("在对话中查找", COUIIcons.Search, { onFind?.invoke() }, enabled = onFind != null),
                    menuItem("设置", COUIIcons.Settings, onOpenSettings),
                ),
            ),
        ),
    )
}

private class Suggestion(val title: String, val icon: ImageVector, val prompt: String)

private val Suggestions = listOf(
    Suggestion("看看这个项目", ZetaIcons.FolderOpen, "先浏览当前目录，概括这个项目是做什么的、结构如何。"),
    Suggestion("检查 Git 状态", COUIIcons.Merge, "查看当前目录的 git 状态和最近的提交，告诉我有哪些未提交的改动。"),
    Suggestion("接着上次做", ZetaIcons.History, "回顾这个目录里最近的改动，告诉我上次做到哪了、接下来该做什么。"),
    Suggestion("设备状况", ZetaIcons.Chip, "检查这台设备的内存、存储和负载情况，有异常就指出来。"),
)

@Composable
private fun EmptyState(
    cwd: String,
    needsLogin: Boolean,
    onLogin: () -> Unit,
    onSuggestion: (String) -> Unit,
    onChangeCwd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = COUITheme.colorScheme
    var editingCwd by remember { mutableStateOf(false) }
    var cwdDraft by remember { mutableStateOf(cwd) }
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.align(Alignment.Center).padding(bottom = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "有什么可以帮你？", style = COUITheme.textStyles.displayXS, color = colors.labelPrimary)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(CircleShape).clickable {
                    cwdDraft = cwd
                    editingCwd = true
                }.padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(ZetaIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(13.dp), tint = colors.labelSecondary)
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
            Spacer(Modifier.height(18.dp))
            // 还没登录时发什么都只会报错，先把人领去登录
            if (needsLogin) {
                Text(text = "这个环境里的 Claude Code 还没有登录账号", style = COUITheme.textStyles.bodyM, color = colors.labelSecondary)
                Spacer(Modifier.height(14.dp))
                TextButton(text = "登录 Claude 账号", onClick = onLogin, colors = ButtonDefaults.textButtonColorsPrimary())
            } else Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Suggestions.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { item ->
                            val shape = remember { SquircleShape(COUIRoundCorner.L) }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .background(colors.container4, shape)
                                    .clickable { onSuggestion(item.prompt) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.containerThemeHalftone),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(imageVector = item.icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = colors.primary)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = item.title,
                                    style = COUITheme.textStyles.buttonM,
                                    color = colors.labelPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    TextInputDialog(
        show = editingCwd,
        title = "工作目录",
        summary = "这个对话在 Debian 里的哪个目录下进行",
        value = cwdDraft,
        onValueChange = { cwdDraft = it },
        label = "/workspace",
        confirmText = "确定",
        confirmEnabled = cwdDraft.trim().startsWith("/"),
        onConfirm = {
            editingCwd = false
            if (cwdDraft.trim() != cwd) onChangeCwd(cwdDraft.trim())
        },
        onDismissRequest = { editingCwd = false },
    )
}
