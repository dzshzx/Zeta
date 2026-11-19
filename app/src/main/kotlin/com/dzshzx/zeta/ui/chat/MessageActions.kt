package com.dzshzx.zeta.ui.chat

import android.content.Context
import android.graphics.ImageDecoder
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.ImageRef
import com.dzshzx.zeta.core.InlineImage
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.SessionImage
import com.dzshzx.zeta.core.TurnActions
import com.dzshzx.zeta.core.imageMediaType
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 聊天页的消息操作，交互照 Eta：
 * - 用户消息长按：复制、编辑、删除。编辑时原文放回输入框，被编辑的消息描边，它之后的内容先藏起来，发出后替换；
 * - 每一轮最后一段回答下方：复制之后是重新生成、删除这一轮、分支。
 * 改写和删除都在原会话上剪（会话号不变），分支另开新会话；做法见 core/Rewind.kt。
 */

internal const val UserMenuEdit = 1
internal const val UserMenuDelete = 2

internal fun userMenuItems(canEdit: Boolean, canDelete: Boolean, deleteTint: Color): List<CouiMenuItem<Int>> = buildList {
    add(CouiMenuItem(value = 0, text = "复制", icon = Icons.Rounded.ContentCopy))
    if (canEdit) add(CouiMenuItem(value = UserMenuEdit, text = "编辑", icon = Icons.Rounded.Edit))
    if (canDelete) {
        add(CouiMenuItem(value = UserMenuDelete, text = "删除", icon = Icons.Rounded.DeleteOutline, iconTint = deleteTint, dividerBefore = true))
    }
}

/** 各轮的操作，按用户消息和操作行所在的那一项查。 */
@Immutable
internal class TurnIndex(list: List<TurnActions>) {
    val byUser: Map<String, TurnActions> = list.associateBy { it.user.id }
    val byLast: Map<String, TurnActions> = list.mapNotNull { turn -> turn.lastItemId?.let { it to turn } }.toMap()
    val last: TurnActions? = list.lastOrNull()
}

/** 等确认的重新生成（[regenerate]）或删除。 */
internal class PendingConfirm(val turn: TurnActions, val regenerate: Boolean)

/** 一个对话的消息操作状态：正在编辑哪条、在等哪个确认。随对话重建。 */
@Stable
internal class MessageActions(
    private val hub: ConversationHub,
    private val conversation: Conversation,
    private val input: TextFieldState,
    private val attachments: SnapshotStateList<Attachment>,
    private val uploads: SnapshotStateList<FileUpload>,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    /** 正在编辑的那一轮。 */
    var editing by mutableStateOf<TurnActions?>(null)
        private set

    var confirm by mutableStateOf<PendingConfirm?>(null)
        private set

    /** 开始编辑前输入框里的东西，取消编辑时放回去。 */
    private class Draft(val text: String, val attachments: List<Attachment>, val uploads: List<FileUpload>)

    private var draft: Draft? = null

    /** 编辑时只显示到被编辑的那条为止：发出后它和之后的内容都会被替换（照 Eta）。 */
    fun visible(items: List<com.dzshzx.zeta.core.ChatItem>): List<com.dzshzx.zeta.core.ChatItem> {
        val target = editing?.user?.id ?: return items
        val index = items.indexOfFirst { it.id == target }
        return if (index < 0) items else items.subList(0, index + 1)
    }

    fun beginEdit(turn: TurnActions) {
        if (!conversation.canRewind) return
        if (editing != null) cancelEdit()
        draft = Draft(input.text.toString(), attachments.toList(), uploads.toList())
        editing = turn
        input.setTextAndPlaceCursorAtEnd(turn.user.text)
        attachments.clear()
        uploads.clear()
        for (path in turn.user.files) uploads.add(FileUpload(UUID.randomUUID().toString(), path.substringAfterLast('/')).also { it.path = path })
        val images = turn.user.images
        if (images.isNotEmpty()) scope.launch {
            val restored = withContext(Dispatchers.IO) { images.map { reattach(hub, it) } }
            if (editing !== turn) return@launch
            attachments.addAll(restored.filterNotNull())
            if (restored.any { it == null }) toast("有图片取不回来，没有放进输入框")
        }
    }

    fun cancelEdit() {
        val saved = draft ?: return
        editing = null
        draft = null
        input.setTextAndPlaceCursorAtEnd(saved.text)
        attachments.clear()
        attachments.addAll(saved.attachments)
        uploads.clear()
        uploads.addAll(saved.uploads)
    }

    /** 发出改好的消息。现在还不能改写（这一轮没结束之类）时返回 false，内容留给输入框。 */
    fun submitEdit(text: String, attachments: List<Attachment>, files: List<String>): Boolean {
        val turn = editing ?: return false
        if (!conversation.canRewind) {
            toast("等这一轮结束再发")
            return false
        }
        editing = null
        draft = null
        conversation.rewrite(turn.user, text, attachments, files)
        return true
    }

    fun askRegenerate(turn: TurnActions) {
        confirm = PendingConfirm(turn, regenerate = true)
    }

    fun askDelete(turn: TurnActions) {
        confirm = PendingConfirm(turn, regenerate = false)
    }

    fun dismissConfirm() {
        confirm = null
    }

    fun confirmed() {
        val pending = confirm ?: return
        confirm = null
        val user = pending.turn.user
        if (!pending.regenerate) {
            conversation.dropFrom(user)
            return
        }
        // 原样重发这一轮的提问：文字、附件路径，还有图片（历史里的要从会话记录取回）
        scope.launch {
            val images = withContext(Dispatchers.IO) { user.images.map { reattach(hub, it) } }
            if (images.any { it == null }) {
                toast("有图片取不回来，没法原样重发")
                return@launch
            }
            conversation.rewrite(user, user.text, images.filterNotNull(), user.files)
        }
    }

    fun branch(turn: TurnActions) {
        turn.lastItemId?.let(hub::branch)
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * 挂在 [itemId] 下面的那一轮的操作键（重新生成、删除、分支）；[itemId] 不是哪一轮的最后一项时什么都不画。
 * 最后一轮还在进行时不出现；有别的事在进行时变灰。
 */
@Composable
internal fun TurnActionButtons(itemId: String, turns: TurnIndex, conversation: Conversation, actions: MessageActions) {
    val turn = turns.byLast[itemId] ?: return
    if (turn === turns.last && (conversation.busy || conversation.rewinding)) return
    val enabled = conversation.canRewind && actions.editing == null
    if (turn.rewindable) {
        ActionButton(Icons.Rounded.Refresh, "重新生成", enabled) { actions.askRegenerate(turn) }
        ActionButton(Icons.Rounded.DeleteOutline, "删除这一轮", enabled) { actions.askDelete(turn) }
    }
    if (turn.branchable && conversation.sessionId != null) {
        ActionButton(Icons.AutoMirrored.Rounded.CallSplit, "从这里分支到新对话", enabled) { actions.branch(turn) }
    }
}

/** 不以回答结尾的一轮（停在工具步骤或出错提示上），操作键单独占一行，和回答下方的对齐。 */
@Composable
internal fun TurnActionRow(itemId: String, turns: TurnIndex, conversation: Conversation, actions: MessageActions) {
    if (itemId !in turns.byLast) return
    Row(modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TurnActionButtons(itemId, turns, conversation, actions)
    }
}

/** 和回答下方的复制键一个样子。 */
@Composable
private fun ActionButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

/** 输入卡片上方的编辑提示条（样子同排队条）：说明发出后会替换什么，× 取消编辑。 */
@Composable
internal fun EditStrip(turn: TurnActions, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val dark = CouiTheme.isDark
    val colors = MiuixTheme.colorScheme
    val shape = remember { CouiShape(18.dp, 2.3f) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) Color(0xFF1C1E22) else Color(0xFFF6F7F9), shape)
            .border(0.5.dp, if (dark) Color(0x20FFFFFF) else Color(0x0E000000), shape)
            .padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "编辑消息",
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            color = colors.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (turn.laterTurns > 0) "发送后将替换这条消息及之后的内容" else "发送后将替换这条消息和它的回复",
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp),
            color = colors.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ActionIcon(Icons.Rounded.Close, "取消编辑", colors.onSurfaceVariantSummary, onCancel)
    }
}

/** 重新生成和删除前的确认。两样都会丢掉内容（会话记录里还在，但界面和 Claude 都不再看到），所以先问。 */
@Composable
internal fun MessageActionDialogs(actions: MessageActions) {
    val pending = actions.confirm
    // 对话框收起的动画里还要显示原来的文字
    val shown = remember { arrayOfNulls<PendingConfirm>(1) }
    if (pending != null) shown[0] = pending
    val last = shown[0]
    val later = last?.turn?.laterTurns ?: 0
    CouiConfirmDialog(
        visible = pending != null,
        title = if (last?.regenerate != false) "重新生成回复？" else "删除这一轮对话？",
        message = when {
            last == null -> null
            last.regenerate && later > 0 -> "将删除之后 $later 轮对话，并从这条消息重新生成。"
            last.regenerate -> "这一轮的回复将换成新生成的。"
            later > 0 -> "这条消息及之后 $later 轮对话将被删除。"
            else -> "这条消息和它的回复将被删除。"
        },
        confirmText = if (last?.regenerate != false) "重新生成" else "删除",
        destructive = last?.regenerate == false,
        onConfirm = actions::confirmed,
        onDismissRequest = actions::dismissConfirm,
    )
}

private const val PreviewEdge = 360
private val helperJson = Json { ignoreUnknownKeys = true }

/** 把消息里的一张图片还原成能再发出去的附件（编辑、重新生成时用）；取不回来返回 null。耗时，别在主线程调用。 */
private fun reattach(hub: ConversationHub, ref: ImageRef): Attachment? = runCatching {
    val (mediaType, data) = when (ref) {
        is InlineImage -> (imageMediaType(Base64.decode(ref.base64, Base64.DEFAULT)) ?: "image/jpeg") to ref.base64
        is SessionImage -> {
            val config = hub.settings.env ?: return null
            val result = LinuxEnv.run(
                config, "exec python3 - image " + LinuxEnv.quote(ref.file) + " " + LinuxEnv.quote(ref.ref),
                stdin = hub.helperScript, timeoutMs = 60_000,
            )
            if (result.exitCode != 0) return null
            val reply = result.stdout.lineSequence().firstOrNull { it.startsWith("{") }
                ?.let { helperJson.parseToJsonElement(it) as? JsonObject } ?: return null
            (reply.str("media_type") ?: return null) to (reply.str("data") ?: return null)
        }
        else -> return null
    }
    val bytes = Base64.decode(data, Base64.DEFAULT)
    val preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val edge = max(info.size.width, info.size.height)
        if (edge > PreviewEdge) {
            val scale = PreviewEdge.toFloat() / edge
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }
    Attachment(UUID.randomUUID().toString(), preview.asImageBitmap(), mediaType, data)
}.getOrNull()
