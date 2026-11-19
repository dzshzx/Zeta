package com.dzshzx.zeta.ui.chat

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.PromptStore
import com.dzshzx.zeta.core.SavedPrompt
import com.dzshzx.zeta.core.SavedPrompts
import com.dzshzx.zeta.ui.components.StatusError
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiTextField
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import java.util.UUID
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 常用指令：用户存下来的提问，点一下填进输入框，改一改再发。入口在输入框的“+”菜单里；
 * 长按自己发过的消息可以“收藏”。列表里新建、编辑、上移下移、删除。不进 `/` 菜单，那里是 Claude Code 的斜杠命令。
 */

/** 正在编辑的一条；[id] 为 null 是新建。 */
internal class PromptDraft(val id: String?, val name: String, val text: String)

/** 聊天页上常用指令的界面状态：列表开没开、在编辑哪条、在等哪条的删除确认。三个对话框一次只开一个，编辑和删除完回到列表。 */
@Stable
internal class PromptsUi(private val context: Context, private val input: TextFieldState) {
    var listOpen by mutableStateOf(false)
        private set
    var editing by mutableStateOf<PromptDraft?>(null)
        private set
    var deleting by mutableStateOf<SavedPrompt?>(null)
        private set

    val prompts: List<SavedPrompt> get() = PromptStore.observed

    fun open() {
        PromptStore.load(context)
        listOpen = true
    }

    fun close() {
        listOpen = false
    }

    /** 填进输入框：空着就整个换上，有字就插在光标处（选中了字就替换选中的）。 */
    fun pick(prompt: SavedPrompt) {
        listOpen = false
        insertPrompt(input, prompt.text)
    }

    /** 新建：输入框里正好有字时先填上，方便把正在写的存下来。 */
    fun startNew() {
        listOpen = false
        editing = PromptDraft(null, "", input.text.toString().takeIf { it.isNotBlank() }.orEmpty())
    }

    fun startEdit(prompt: SavedPrompt) {
        listOpen = false
        editing = PromptDraft(prompt.id, prompt.name, prompt.text)
    }

    fun problem(name: String, text: String): String? = SavedPrompts.problem(prompts, name, text, editing?.id)

    fun save(name: String, text: String) {
        val draft = editing ?: return
        if (problem(name, text) != null) return
        PromptStore.update(context) { list ->
            val id = draft.id
            if (id == null) SavedPrompts.add(list, UUID.randomUUID().toString(), name, text) else SavedPrompts.update(list, id, name, text)
        }
        editing = null
        listOpen = true
    }

    fun cancelEdit() {
        editing = null
        listOpen = true
    }

    fun move(prompt: SavedPrompt, delta: Int) = PromptStore.update(context) { SavedPrompts.move(it, prompt.id, delta) }

    fun askDelete(prompt: SavedPrompt) {
        listOpen = false
        deleting = prompt
    }

    fun confirmDelete() {
        val prompt = deleting ?: return
        PromptStore.update(context) { SavedPrompts.remove(it, prompt.id) }
        deleting = null
        listOpen = true
    }

    fun dismissDelete() {
        deleting = null
        listOpen = true
    }

    /** 长按消息里的“收藏”：直接存，不弹编辑框；同样的内容存过就不再存。 */
    fun saveMessage(text: String) {
        val list = PromptStore.load(context)
        val message = when {
            SavedPrompts.findSame(list, text) != null -> "已经在常用指令里了"
            else -> SavedPrompts.problem(list, "", text) ?: run {
                PromptStore.update(context) { SavedPrompts.add(it, UUID.randomUUID().toString(), "", text) }
                "已收藏，在输入框左边的“+”里"
            }
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun rememberPromptsUi(input: TextFieldState): PromptsUi {
    val context = LocalContext.current
    return remember(input) { PromptsUi(context.applicationContext, input) }
}

/** 把常用指令放进输入框：空着就整个换上；有字就插在光标处，替换选中的字，光标停在插入内容的后面。 */
internal fun insertPrompt(input: TextFieldState, text: String) {
    if (input.text.isBlank()) {
        input.setTextAndPlaceCursorAtEnd(text)
        return
    }
    input.edit {
        val start = selection.min.coerceIn(0, length)
        val end = selection.max.coerceIn(start, length)
        replace(start, end, text)
        selection = TextRange(start + text.length)
    }
}

/** 常用指令的列表、编辑和删除确认。 */
@Composable
internal fun SavedPromptsDialogs(ui: PromptsUi) {
    PromptListDialog(ui)
    PromptEditorDialog(ui)
    val deleting = ui.deleting
    val shown = remember { arrayOfNulls<SavedPrompt>(1) }
    if (deleting != null) shown[0] = deleting
    CouiConfirmDialog(
        visible = deleting != null,
        title = "删除这条常用指令？",
        message = shown[0]?.let { "“${SavedPrompts.displayName(it)}”删除后找不回来。" },
        confirmText = "删除",
        destructive = true,
        onConfirm = ui::confirmDelete,
        onDismissRequest = ui::dismissDelete,
    )
}

@Composable
private fun PromptListDialog(ui: PromptsUi) {
    val prompts = ui.prompts
    CouiConfirmDialog(
        visible = ui.listOpen,
        title = "常用指令",
        message = null,
        confirmText = "新建",
        confirmEnabled = prompts.size < SavedPrompts.MAX,
        cancelText = "关闭",
        onConfirm = ui::startNew,
        onDismissRequest = ui::close,
    ) {
        if (prompts.isEmpty()) {
            Text(
                text = "还没有常用指令。点“新建”写一条，或者长按自己发过的消息，选“收藏”。点一条就把它填进输入框，可以改了再发。",
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                prompts.forEachIndexed { index, prompt ->
                    PromptRow(prompt, first = index == 0, last = index == prompts.lastIndex, ui = ui)
                }
            }
        }
    }
}

private const val MenuEdit = 0
private const val MenuUp = 1
private const val MenuDown = 2
private const val MenuDelete = 3

@Composable
private fun PromptRow(prompt: SavedPrompt, first: Boolean, last: Boolean, ui: PromptsUi) {
    val colors = MiuixTheme.colorScheme
    val anchor = rememberCouiMenuAnchor()
    var menu by remember { mutableStateOf(false) }
    val deleteTint = StatusError
    val items = remember(first, last, deleteTint) {
        listOf(
            CouiMenuItem(value = MenuEdit, text = "编辑", icon = Icons.Rounded.Edit),
            CouiMenuItem(value = MenuUp, text = "上移", icon = Icons.Rounded.ArrowUpward, enabled = !first),
            CouiMenuItem(value = MenuDown, text = "下移", icon = Icons.Rounded.ArrowDownward, enabled = !last),
            CouiMenuItem(value = MenuDelete, text = "删除", icon = Icons.Rounded.DeleteOutline, iconTint = deleteTint, dividerBefore = true),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { ui.pick(prompt) }
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 起了名字的：名字加两行内容；没起名字的直接显示内容，不把开头重复一遍
        val named = prompt.name.isNotBlank()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (named) prompt.name.trim() else prompt.text.trim(),
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                color = colors.onSurface,
                maxLines = if (named) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (named) {
                Text(
                    text = prompt.text.trim(),
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            Box(
                modifier = Modifier
                    .couiMenuAnchor(anchor)
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { anchor.open { menu = true } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(18.dp), tint = colors.onSurfaceVariantSummary)
            }
            Menu(
                expanded = menu,
                onDismissRequest = { menu = false },
                items = items,
                onItemClick = {
                    menu = false
                    when (it.value) {
                        MenuEdit -> ui.startEdit(prompt)
                        MenuUp -> ui.move(prompt, -1)
                        MenuDown -> ui.move(prompt, 1)
                        else -> ui.askDelete(prompt)
                    }
                },
                anchor = anchor,
            )
        }
    }
}

@Composable
private fun PromptEditorDialog(ui: PromptsUi) {
    val draft = ui.editing
    // 对话框收起的动画里还要显示原来的内容
    val shown = remember { arrayOfNulls<PromptDraft>(1) }
    if (draft != null) shown[0] = draft
    val current = shown[0]
    var name by remember(current) { mutableStateOf(current?.name.orEmpty()) }
    var text by remember(current) { mutableStateOf(current?.text.orEmpty()) }
    val problem = ui.problem(name, text)
    CouiConfirmDialog(
        visible = draft != null,
        title = if (current?.id == null) "新建常用指令" else "编辑常用指令",
        message = null,
        confirmText = "保存",
        confirmEnabled = problem == null,
        onConfirm = { ui.save(name, text) },
        onDismissRequest = ui::cancelEdit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            CouiTextField(
                value = name,
                onValueChange = { name = it.take(SavedPrompts.MAX_NAME) },
                label = "名字（可不填）",
                placeholder = SavedPrompts.defaultName(text).ifEmpty { "不填就用内容的开头" },
            )
            CouiTextField(
                value = text,
                onValueChange = { text = it },
                label = "内容",
                placeholder = "点一下这条，就把这里的文字填进输入框",
                singleLine = false,
                minLines = 3,
                maxLines = 8,
            )
            // 空着时不报错，“保存”变灰就够了
            if (problem != null && text.isNotEmpty()) {
                Text(text = problem, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
            }
        }
    }
}
