package com.dzshzx.zeta.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MapsUgc
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Scaffold
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.TopAppBar
import com.itos.coui.topappbar.TopAppBarButtonGroup
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.AttachedPaths
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.Attachments
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.Uploads
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.core.turnActions
import com.dzshzx.zeta.ui.components.coui.CouiButton
import com.dzshzx.zeta.ui.components.coui.CouiButtonRole
import com.dzshzx.zeta.ui.components.coui.CouiInputDialog
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarIconButton
import com.dzshzx.zeta.ui.markdown.LocalTextFinder
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 还没连上进程、拿不到真实列表时先给的几个常用别名（Claude Code 自己解析）。 */
private val FallbackModels = listOf(
    ModelOption("default", "默认", ""),
    ModelOption("fable", "Fable", ""),
    ModelOption("opus", "Opus", ""),
    ModelOption("sonnet", "Sonnet", ""),
    ModelOption("haiku", "Haiku", ""),
)

@Composable
internal fun ChatScreen(
    hub: ConversationHub,
    onOpenPane: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInstall: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val conversation = hub.current
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val find = remember(conversation.key) { ChatFindState() }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ChatHeader(conversation = conversation, models = hub.models.ifEmpty { FallbackModels })
                    }
                },
                navigationIcon = {
                    CouiTopAppBarIconButton(
                        icon = Icons.AutoMirrored.Outlined.ShortText,
                        contentDescription = "历史对话",
                        onClick = onOpenPane,
                        dark = dark,
                        colors = colors,
                    )
                },
                actions = {
                    TopAppBarButtonGroup {
                        OverflowMenu(
                            onNewChat = hub::startNew,
                            onOpenSettings = onOpenSettings,
                            onFind = find::open.takeIf { hub.setup == SetupState.Ready && conversation.items.isNotEmpty() },
                        )
                    }
                },
                dark = dark,
                colors = CouiTopAppBarDefaults.colors(backgroundColor = MiuixTheme.colorScheme.surface, titleColor = colors.title, dark = dark),
            )
        },
    ) { padding ->
        when (hub.setup) {
            SetupState.Ready -> key(conversation.key) {
                CompositionLocalProvider(LocalTextFinder provides find) { ConversationBody(hub, conversation, padding, onOpenLogin, find) }
            }
            SetupState.Checking -> SetupMessage(padding, "正在检查环境…", null)
            SetupState.NoRoot -> SetupMessage(
                padding,
                "需要 root 权限",
                "Zeta 通过 root 进入 Debian 环境来运行 Claude Code。请在 root 管理器（KernelSU / SukiSU / Magisk）里给 Zeta 授权，然后重试。",
            ) { CouiButton(text = "重试", onClick = hub::checkSetup) }
            SetupState.NoEnvironment -> SetupMessage(
                padding,
                "还没有 Linux 环境",
                "Zeta 在一个 Debian 环境里运行 Claude Code。可以让 Zeta 现在装一个（约 1 GB），或者在设置里指向已有的环境。",
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CouiButton(text = if (hub.ownStatus?.base == true) "继续安装 Debian 环境" else "安装 Debian 环境", onClick = onOpenInstall, role = CouiButtonRole.Primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CouiButton(text = "打开设置", onClick = onOpenSettings)
                        CouiButton(text = "重试", onClick = hub::checkSetup)
                    }
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

@Composable
private fun SetupMessage(padding: PaddingValues, title: String, message: String?, actions: (@Composable () -> Unit)? = null) {
    Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 56.dp)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title2.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (message != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.body2.copy(lineHeight = 21.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
private fun ConversationBody(hub: ConversationHub, conversation: Conversation, padding: PaddingValues, onOpenLogin: () -> Unit, find: ChatFindState) {
    val items = conversation.items
    val listState = remember { LazyListState() }
    val input = remember { TextFieldState() }
    val markdownCache = remember { StreamingMarkdownStateCache() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val attachments = remember { mutableStateListOf<Attachment>() }
    var imageFailure by remember { mutableStateOf(false) }
    fun addImages(uris: List<Uri>) {
        scope.launch {
            for (uri in uris.take(MaxAttachments - attachments.size)) {
                val loaded = withContext(Dispatchers.IO) { Attachments.load(context, uri) }
                if (loaded != null) attachments.add(loaded) else imageFailure = true
            }
        }
    }
    val uploads = remember { mutableStateListOf<FileUpload>() }
    // 附件菜单里的“文件夹”“输入路径”：引用 Linux 里本来就有的路径
    val pathAttach = remember { PathAttach() }
    // 图片当作图片发给模型看；别的文件复制进 Linux 环境，让 Claude 用工具去读
    fun addShared(uris: List<Uri>) {
        scope.launch {
            for (uri in uris) {
                val (name, type) = withContext(Dispatchers.IO) { Uploads.describe(context, uri) }
                if (type?.startsWith("image/") == true && type != "image/svg+xml") {
                    addImages(listOf(uri))
                    continue
                }
                val config = hub.settings.env ?: continue
                val upload = FileUpload(UUID.randomUUID().toString(), name)
                uploads.add(upload)
                launch {
                    val path = withContext(Dispatchers.IO) { Uploads.copy(context, config, uri, name) }
                    if (path != null) upload.path = path else upload.failed = true
                }
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MaxAttachments)) { uris -> addImages(uris) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> addShared(uris) }
    // 从别的应用分享进来的图片和文件
    LaunchedEffect(hub.incomingShares) {
        val incoming = hub.incomingShares
        if (incoming.isNotEmpty()) {
            hub.incomingShares = emptyList()
            addShared(incoming)
        }
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
        val draft = input.text.toString()
        input.setTextAndPlaceCursorAtEnd(listOf(back.text, draft).filter { it.isNotBlank() }.joinToString("\n"))
        attachments.addAll(back.attachments.filter { a -> attachments.none { it.id == a.id } }.take(MaxAttachments - attachments.size))
        for (path in back.files) {
            if (uploads.none { it.path == path }) uploads.add(FileUpload(UUID.randomUUID().toString(), AttachedPaths.label(path)).also { it.path = path })
        }
    }
    LaunchedEffect(items) {
        markdownCache.discardUnused(items.mapNotNullTo(HashSet()) { (it as? AnswerItem)?.takeIf { answer -> answer.streaming }?.id })
    }
    // 编辑、重新生成、删除一轮、分支
    val actions = remember { MessageActions(hub, conversation, input, attachments, uploads, scope, context) }
    val turns = remember(items) { TurnIndex(turnActions(items)) }
    val shown = actions.visible(items)
    ChatFindDriver(
        find = find,
        items = shown,
        answerText = { imagesOf(it).text },
        listState = listState,
        leading = if (conversation.skippedHistory > 0) 1 else 0,
        topInset = padding.calculateTopPadding(),
        onJump = { follow = false },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().findViewport(find)) {
            when {
                conversation.loadingHistory -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { TypingIndicator() }
                items.isEmpty() -> EmptyState(
                    cwd = conversation.cwd,
                    needsLogin = hub.account?.loggedIn == false,
                    onLogin = onOpenLogin,
                    onSuggestion = conversation::send,
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = 16.dp),
                    ) {
                        if (conversation.skippedHistory > 0) {
                            item(key = "skipped") { NoticeRow(NoticeItem("skipped", "更早的 ${conversation.skippedHistory} 条记录未载入", error = false)) }
                        }
                        items(shown, key = { it.id }, contentType = { it::class }) { item ->
                            when (item) {
                                is UserItem -> {
                                    val turn = turns.byUser[item.id]?.takeIf { it.rewindable && conversation.canRewind && actions.editing == null }
                                    UserBubble(
                                        item,
                                        editing = actions.editing?.user?.id == item.id,
                                        onEdit = turn?.let { { actions.beginEdit(it) } },
                                        onDelete = turn?.let { { actions.askDelete(it) } },
                                    )
                                }
                                is AnswerItem -> AnswerBlock(item, markdownCache, imagesOf = imagesOf, actions = {
                                    TurnActionButtons(item.id, turns, conversation, actions)
                                })
                                is WorkItem -> Column {
                                    WorkGroup(item)
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
                                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp),
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !follow && listState.canScrollForward && items.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.82f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f),
            ) {
                val dark = CouiTheme.isDark
                Box(
                    modifier = Modifier
                        .dropShadow(shape = CircleShape, shadow = Shadow(radius = 10.dp, color = Color.Black, alpha = if (dark) 0.24f else 0.08f))
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (dark) Color(0xFF24262B) else Color.White, CircleShape)
                        .border(0.5.dp, if (dark) Color(0x22FFFFFF) else Color(0x10000000), CircleShape)
                        .clickable {
                            follow = true
                            scope.launch { listState.scrollToEnd() }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = "回到底部",
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                }
            }
        }
        if (find.active) {
            ChatFindBar(
                find = find,
                skippedHistory = conversation.skippedHistory,
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
            Composer(
                state = input,
                busy = conversation.busy,
                enabled = !conversation.loadingHistory && !conversation.rewinding,
                permissionMode = conversation.permissionMode,
                onPermissionModeChange = conversation::choosePermissionMode,
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
                },
                onStop = conversation::interrupt,
                extraChips = {
                    EffortChip(
                        effort = conversation.effort,
                        applied = conversation.usage.appliedEffort,
                        onChange = conversation::chooseEffort,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                },
                trailing = {
                    QuotaChip(hub.quota.snapshot, onClick = { viewingUsage = true }, modifier = Modifier.padding(end = 4.dp))
                    if (conversation.usage.known) ContextMeter(conversation.usage, onClick = { viewingUsage = true }, modifier = Modifier.padding(end = 4.dp))
                },
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
    AttachPathDialogs(pathAttach, hub, conversation) { entry ->
        if (uploads.none { it.path == entry }) uploads.add(FileUpload(UUID.randomUUID().toString(), AttachedPaths.label(entry)).also { it.path = entry })
    }
}

private const val MaxAttachments = 4

/** 会话正在别处（比如终端里）运行时，输入栏的位置换成这张说明卡。 */
@Composable
private fun ExternalSessionNotice(onTakeOver: () -> Unit, modifier: Modifier = Modifier) {
    val dark = CouiTheme.isDark
    val shape = remember { CouiShape(24.dp, 2.3f) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) Color(0xFF1C1E22) else Color(0xFFF6F7F9), shape)
            .border(0.5.dp, if (dark) Color(0x20FFFFFF) else Color(0x0E000000), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "这个会话正在别处运行",
            style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "另一个 Claude Code 进程（比如终端里的）还开着它。在这里续聊会让两边同时写同一份记录，内容可能错乱。",
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(12.dp))
        CouiButton(text = "仍要在这里继续", onClick = onTakeOver)
    }
}

/** 滚到内容的最底端。只定位到最后一项不够：它比一屏还高时会停在它的顶部。 */
private suspend fun LazyListState.scrollToEnd() {
    scrollToItem((layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    scrollBy(1_000_000f)
}

/** 顶栏中间：有标题时是“标题 + 模型”两行，新对话时是模型胶囊；点击切换模型。 */
@Composable
private fun ChatHeader(conversation: Conversation, models: List<ModelOption>) {
    val dark = CouiTheme.isDark
    val ink = MiuixTheme.colorScheme.onSurface
    val current = conversation.model ?: "default"
    val modelName = if (current == "default") "默认" else models.firstOrNull { it.value == current }?.displayName?.ifBlank { null } ?: current
    val anchor = rememberCouiMenuAnchor()
    var expanded by remember { mutableStateOf(false) }
    val menuItems = remember(models, current) { models.map { CouiMenuItem(value = it.value, text = if (it.value == "default") "默认" else it.displayName.ifBlank { it.value }, selected = it.value == current) } }
    val source = remember { MutableInteractionSource() }
    val subtitleColor = ink.copy(alpha = if (dark) 0.56f else 0.52f)
    Box {
        if (conversation.title.isNotBlank()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .couiMenuAnchor(anchor)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(interactionSource = source, indication = null) { anchor.open { expanded = true } }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = conversation.title,
                    style = MiuixTheme.textStyles.body1.copy(fontSize = 15.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = modelName,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.5.sp, lineHeight = 14.sp),
                        color = subtitleColor,
                        maxLines = 1,
                    )
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(13.dp), tint = ink.copy(alpha = if (dark) 0.46f else 0.42f))
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .couiMenuAnchor(anchor)
                    .heightIn(min = 34.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = if (dark) 0.08f else 0.042f), CircleShape)
                    .border(0.5.dp, ink.copy(alpha = if (dark) 0.12f else 0.07f), CircleShape)
                    .clickable(interactionSource = source, indication = null) { anchor.open { expanded = true } }
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "Claude · $modelName",
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "切换模型", modifier = Modifier.padding(start = 2.dp).size(16.dp), tint = subtitleColor)
            }
        }
        Menu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = menuItems,
            onItemClick = {
                expanded = false
                conversation.chooseModel(it.value.takeIf { value -> value != "default" })
            },
            anchor = anchor,
        )
    }
}

@Composable
private fun OverflowMenu(onNewChat: () -> Unit, onOpenSettings: () -> Unit, onFind: (() -> Unit)?) {
    val dark = CouiTheme.isDark
    val anchor = rememberCouiMenuAnchor()
    var expanded by remember { mutableStateOf(false) }
    val findEnabled = onFind != null
    val menuItems = remember(findEnabled) {
        listOf(
            CouiMenuItem(value = 0, text = "新对话", icon = Icons.Rounded.MapsUgc),
            CouiMenuItem(value = 2, text = "在对话中查找", icon = Icons.Rounded.Search, enabled = findEnabled),
            CouiMenuItem(value = 1, text = "设置", icon = Icons.Outlined.Settings),
        )
    }
    Box {
        CouiTopAppBarIconButton(
            icon = CouiIcons.Outlined.More,
            contentDescription = "更多",
            modifier = Modifier.couiMenuAnchor(anchor),
            dark = dark,
            colors = CouiPageDefaults.themeColors(dark),
            onClick = { anchor.open { expanded = true } },
        )
        Menu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = menuItems,
            onItemClick = {
                expanded = false
                when (it.value) {
                    0 -> onNewChat()
                    2 -> onFind?.invoke()
                    else -> onOpenSettings()
                }
            },
            anchor = anchor,
        )
    }
}

private class Suggestion(val title: String, val icon: ImageVector, val prompt: String)

private val Suggestions = listOf(
    Suggestion("看看这个项目", Icons.Rounded.FolderOpen, "先浏览当前目录，概括这个项目是做什么的、结构如何。"),
    Suggestion("检查 Git 状态", Icons.Rounded.Merge, "查看当前目录的 git 状态和最近的提交，告诉我有哪些未提交的改动。"),
    Suggestion("接着上次做", Icons.Rounded.History, "回顾这个目录里最近的改动，告诉我上次做到哪了、接下来该做什么。"),
    Suggestion("设备状况", Icons.Rounded.Memory, "检查这台设备的内存、存储和负载情况，有异常就指出来。"),
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
    val dark = CouiTheme.isDark
    val colors = MiuixTheme.colorScheme
    var editingCwd by remember { mutableStateOf(false) }
    var cwdDraft by remember { mutableStateOf(cwd) }
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.align(Alignment.Center).padding(bottom = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "有什么可以帮你？",
                style = MiuixTheme.textStyles.title2.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
                color = colors.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(CircleShape).clickable {
                    cwdDraft = cwd
                    editingCwd = true
                }.padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(13.dp), tint = colors.onSurfaceVariantSummary)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = cwd,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp),
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            // 还没登录时发什么都只会报错，先把人领去登录
            if (needsLogin) {
                Text(
                    text = "这个环境里的 Claude Code 还没有登录账号",
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp),
                    color = colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(14.dp))
                CouiButton(text = "登录 Claude 账号", onClick = onLogin, role = CouiButtonRole.Primary)
            } else Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Suggestions.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { item ->
                            val shape = remember { CouiShape(15.dp, 2.2f) }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .background(colors.onSurface.copy(alpha = if (dark) 0.055f else 0.028f), shape)
                                    .border(0.5.dp, colors.onSurface.copy(alpha = if (dark) 0.10f else 0.065f), shape)
                                    .clickable { onSuggestion(item.prompt) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.primary.copy(alpha = if (dark) 0.16f else 0.10f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(imageVector = item.icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = colors.primary)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = item.title,
                                    style = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                                    color = colors.onSurface.copy(alpha = 0.88f),
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
    CouiInputDialog(
        visible = editingCwd,
        title = "工作目录",
        message = "这个对话在 Debian 里的哪个目录下进行",
        value = cwdDraft,
        onValueChange = { cwdDraft = it },
        placeholder = "/workspace",
        confirmText = "确定",
        confirmEnabled = cwdDraft.trim().startsWith("/"),
        onConfirm = {
            editingCwd = false
            if (cwdDraft.trim() != cwd) onChangeCwd(cwdDraft.trim())
        },
        onDismissRequest = { editingCwd = false },
    )
}
