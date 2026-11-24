package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.Attachments
import com.dzshzx.zeta.core.CodeRewind
import com.dzshzx.zeta.core.ComposerDraft
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.CoreImage
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.ImageRef
import com.dzshzx.zeta.core.ImageStore
import com.dzshzx.zeta.core.InlineImage
import com.dzshzx.zeta.core.SessionImage
import com.dzshzx.zeta.core.Turn
import com.dzshzx.zeta.core.imageMediaType
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.FlatIconButton
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.rememberRetained
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.preference.CheckboxPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonLocation
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 聊天页的消息操作，交互照 Eta：
 * - 用户消息长按：复制、收藏为常用指令（见 SavedPromptsUi.kt）、编辑、删除。编辑时原文放回输入框，被编辑的消息描边，它之后的内容先藏起来，发出后替换；
 * - 每段回答下方：复制、朗读；一轮的最后一段回答还有重新生成、删除这一轮、分支。
 * 改写和删除都在原会话上剪（会话号不变），分支另开新会话，都由核心做；哪一轮能做什么、操作行挂在哪一项照核心标在用户消息上的（见 core/Rewind.kt）。
 */

/** 各轮的操作，按用户消息和操作行所在的那一项查。 */
@Immutable
class TurnIndex(list: List<Turn>) {
    val byUser: Map<String, Turn> = list.associateBy { it.user.id }
    val byLast: Map<String, Turn> =
        list.mapNotNull { turn -> turn.user.roundLast?.let { it to turn } }.toMap()
    val last: Turn? = list.lastOrNull()
}

/**
 * 等确认的重新生成（[regenerate]）或删除。这条消息之后改过文件（[changes]，问过 Claude Code 才知道）时， 和 TUI 的 /rewind
 * 一样可以一起还原代码（[code]），删除时也可以只还原代码（[codeOnly]）。
 */
@Stable
class PendingConfirm(val turn: Turn, val regenerate: Boolean) {
    var changes by mutableStateOf<CodeRewind.Changes?>(null)
    var code by mutableStateOf(true)
    var codeOnly by mutableStateOf(false)
}

/** 一个对话的消息操作状态：正在编辑哪条、在等哪个确认。编辑时借这个对话的输入栏草稿 [draft] 放被编辑的消息。随对话重建。 */
@Stable
class MessageActions(
    private val hub: ConversationHub,
    private val conversation: Conversation,
    private val draft: ComposerDraft,
    private val scope: CoroutineScope,
) {
    /** 正在编辑的那一轮。 */
    var editing by mutableStateOf<Turn?>(null)
        private set

    var confirm by mutableStateOf<PendingConfirm?>(null)
        private set

    /** 开始编辑前输入框里的东西，取消编辑时放回去。 */
    private class Saved(
        val text: String,
        val attachments: List<Attachment>,
        val uploads: List<FileUpload>,
    )

    private var saved: Saved? = null

    /** 编辑时只显示到被编辑的那条为止：发出后它和之后的内容都会被替换（照 Eta）。 */
    fun visible(items: List<com.dzshzx.zeta.core.ChatItem>): List<com.dzshzx.zeta.core.ChatItem> {
        val target = editing?.user?.id ?: return items
        val index = items.indexOfFirst { it.id == target }
        return if (index < 0) items else items.subList(0, index + 1)
    }

    fun beginEdit(turn: Turn) {
        if (!conversation.canRewind) return
        if (editing != null) cancelEdit()
        saved =
            Saved(draft.input.text.toString(), draft.attachments.toList(), draft.uploads.toList())
        editing = turn
        draft.input.setTextAndPlaceCursorAtEnd(turn.user.text)
        draft.attachments.clear()
        draft.uploads.clear()
        turn.user.files.forEach(draft.uploads::addPath)
        val images = turn.user.images
        if (images.isNotEmpty())
            scope.launch {
                val restored =
                    withContext(Dispatchers.IO) {
                        images.map { reattach(hub, conversation.backend, it) }
                    }
                if (editing !== turn) return@launch
                draft.attachments.addAll(draft.putBackImages(restored.filterNotNull()))
                if (restored.any { it == null }) toast(Strings.composerImagesNotRestored())
            }
    }

    fun cancelEdit() {
        val before = saved ?: return
        editing = null
        saved = null
        draft.input.setTextAndPlaceCursorAtEnd(before.text)
        draft.attachments.clear()
        draft.attachments.addAll(before.attachments)
        draft.uploads.clear()
        draft.uploads.addAll(before.uploads)
    }

    /** 发出改好的消息。现在还不能改写（这一轮没结束之类）时返回 false，内容留给输入框。 */
    fun submitEdit(text: String, attachments: List<Attachment>, files: List<String>): Boolean {
        val turn = editing ?: return false
        if (!conversation.canRewind) {
            toast(Strings.composerWaitTurnEnd())
            return false
        }
        editing = null
        saved = null
        conversation.rewrite(turn.user, text, attachments, files)
        return true
    }

    fun askRegenerate(turn: Turn) = ask(PendingConfirm(turn, regenerate = true))

    fun askDelete(turn: Turn) = ask(PendingConfirm(turn, regenerate = false))

    private fun ask(pending: PendingConfirm) {
        confirm = pending
        conversation.previewCodeRewind(pending.turn.user) { pending.changes = it }
    }

    fun dismissConfirm() {
        confirm = null
    }

    fun confirmed() {
        val pending = confirm ?: return
        confirm = null
        val user = pending.turn.user
        val withCode = pending.changes != null && pending.code
        if (pending.changes != null && pending.codeOnly) {
            conversation.restoreCode(user)
            return
        }
        if (!pending.regenerate) {
            if (withCode) conversation.restoreCode(user, drop = true)
            else conversation.dropFrom(user)
            return
        }
        // 原样重发这一轮的提问：文字、图片、附件路径都由核心从那条消息取
        conversation.regenerate(user, withCode)
    }

    /** 从这一轮之后分支（conv.fork 认这一轮里的任何一项）。 */
    fun branch(turn: Turn) = hub.branch(turn.user.id)

    private fun toast(text: String) {
        hub.platform.toast(text)
    }
}

/** 挂在 [itemId] 下面的那一轮的操作键（重新生成、删除、分支）；[itemId] 不是哪一轮的最后一项时什么都不画。 最后一轮还在进行时不出现；有别的事在进行时变灰。 */
@Composable
fun TurnActionButtons(
    itemId: String,
    turns: TurnIndex,
    conversation: Conversation,
    actions: MessageActions,
) {
    val turn = turns.byLast[itemId] ?: return
    if (turn === turns.last && (conversation.busy || conversation.rewinding)) return
    val enabled = conversation.canRewind && actions.editing == null
    if (turn.user.rewindable) {
        ActionButton(COUIIcons.Refresh, Strings.composerRegenerate(), enabled) {
            actions.askRegenerate(turn)
        }
        ActionButton(COUIIcons.Delete, Strings.composerDeleteTurn(), enabled) {
            actions.askDelete(turn)
        }
    }
    if (turn.user.forkable) {
        ActionButton(ZetaIcons.Branch, Strings.composerBranch(), enabled) { actions.branch(turn) }
    }
}

/** 回答下方复制键后面的朗读键（用系统的朗读引擎念）。 */
@Composable fun ReadAloudButton(answer: AnswerItem) = ChatPlatform.current.ReadAloudButton(answer)

/** 不以回答结尾的一轮（停在工具步骤或出错提示上），操作键单独占一行，和回答下方的对齐。 */
@Composable
fun TurnActionRow(
    itemId: String,
    turns: TurnIndex,
    conversation: Conversation,
    actions: MessageActions,
) {
    if (itemId !in turns.byLast) return
    Row(
        modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TurnActionButtons(itemId, turns, conversation, actions)
    }
}

/** 和回答下方的复制键一个样子：30dp 的平按钮，15dp 的次要色图标。 */
@Composable
private fun ActionButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) =
    FlatIconButton(
        icon,
        description,
        size = 30.dp,
        iconSize = 15.dp,
        enabled = enabled,
        tint = COUITheme.colorScheme.labelSecondary,
        onClick = onClick,
    )

/** 输入卡片上方的编辑提示条（样子同排队条）：说明发出后会替换什么，× 取消编辑。 */
@Composable
fun EditStrip(turn: Turn, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = COUITheme.colorScheme
    val shape = remember { SquircleShape(ChatStripCorner) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .chatPanel(shape)
                .padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            COUIIcons.Edit,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = colors.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = Strings.composerEditing(),
            style = COUITheme.textStyles.bodyS,
            fontWeight = FontWeight.Medium,
            color = colors.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text =
                if (turn.laterTurns > 0) Strings.composerEditReplacesLater()
                else Strings.composerEditReplacesReply(),
            style = COUITheme.textStyles.bodyS,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ActionIcon(COUIIcons.Close, Strings.composerCancelEdit(), colors.labelSecondary, onCancel)
    }
}

/**
 * 重新生成和删除前的确认。两样都会丢掉内容（会话记录里还在，但界面和 Claude 都不再看到），所以先问。 这条消息之后改过文件时多给还原代码的选择，和 TUI 的 /rewind
 * 一样：删除时三选一（一起还原、只删对话、只还原代码），重新生成时勾选。
 */
@Composable
fun MessageActionDialogs(actions: MessageActions) {
    val pending = actions.confirm
    // 对话框收起的动画里还要显示原来的文字
    val last = rememberRetained(pending)
    val changes = last?.changes
    ConfirmDialog(
        show = pending != null,
        title =
            if (last?.regenerate != false) Strings.composerRegenerateTitle()
            else Strings.composerDeleteTurnTitle(),
        summary = last?.let(::confirmSummary),
        confirmText =
            when {
                last?.regenerate != false -> Strings.composerRegenerate()
                changes != null && last.codeOnly -> Strings.composerRestoreCode()
                else -> Strings.composerDelete()
            },
        destructive = last?.regenerate == false,
        onConfirm = actions::confirmed,
        onDismissRequest = actions::dismissConfirm,
        content = if (last == null || changes == null) null else ({ CodeRestoreChoice(last) }),
    )
}

/** 确认框的说明：会丢掉哪些对话，这条消息之后改过文件时再说改了多少。 */
private fun confirmSummary(confirm: PendingConfirm): String {
    val later = confirm.turn.laterTurns
    val turns =
        when {
            confirm.regenerate && later > 0 -> Strings.composerRegenerateLater(later)
            confirm.regenerate -> Strings.composerRegenerateOnly()
            later > 0 -> Strings.composerDeleteLater(later)
            else -> Strings.composerDeleteOnly()
        }
    val files =
        confirm.changes?.let {
            "\n" +
                Strings.composerFilesChangedSince(
                    it.files.size,
                    it.insertions.toString(),
                    it.deletions.toString(),
                )
        }
    return turns + files.orEmpty()
}

/** 还原代码的选择：重新生成时勾选；删除时三选一（一起还原、只删对话、只还原代码）。 */
@Composable
private fun CodeRestoreChoice(confirm: PendingConfirm) {
    val margin = PaddingValues(vertical = 10.dp)
    Column {
        if (confirm.regenerate) {
            CheckboxPreference(
                title = Strings.composerRestoreCodeToo(),
                checked = confirm.code,
                onCheckedChange = { confirm.code = it },
                insideMargin = margin,
            )
            return@Column
        }
        // 每项是（标题，code，codeOnly）
        listOf(
                Triple(Strings.composerDeleteAndRestore(), true, false),
                Triple(Strings.composerDeleteConversationOnly(), false, false),
                Triple(Strings.composerRestoreCodeOnly(), true, true),
            )
            .forEach { (title, code, codeOnly) ->
                RadioButtonPreference(
                    title = title,
                    selected =
                        if (codeOnly) confirm.codeOnly
                        else !confirm.codeOnly && confirm.code == code,
                    onClick = {
                        confirm.code = code
                        confirm.codeOnly = codeOnly
                    },
                    radioButtonLocation = RadioButtonLocation.End,
                    insideMargin = margin,
                )
            }
    }
}

/** 把消息里的一张图片还原成能再发出去的附件（编辑、重新生成时用）；取不回来返回 null。耗时，别在主线程调用。 */
private fun reattach(hub: ConversationHub, backend: String, ref: ImageRef): Attachment? =
    runCatching {
        val (mediaType, data) =
            when (ref) {
                is InlineImage ->
                    (imageMediaType(Base64.getMimeDecoder().decode(ref.base64)) ?: "image/jpeg") to
                        ref.base64
                is SessionImage,
                is CoreImage -> {
                    val reply = ImageStore.fetch(hub, backend, ref) ?: return null
                    (reply.str("media_type") ?: return null) to (reply.str("data") ?: return null)
                }
                else -> return null
            }
        Attachments.restore(
            UUID.randomUUID().toString(),
            mediaType,
            Base64.getMimeDecoder().decode(data),
            ref.original,
        )
    }
    .getOrNull()
