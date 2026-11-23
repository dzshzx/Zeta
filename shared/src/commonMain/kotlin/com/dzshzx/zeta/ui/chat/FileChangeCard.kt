package com.dzshzx.zeta.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.DiffHunk
import com.dzshzx.zeta.core.DiffKind
import com.dzshzx.zeta.core.DiffLine
import com.dzshzx.zeta.core.FileChange
import com.dzshzx.zeta.core.FileEditTools
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.WorkStep
import com.dzshzx.zeta.core.fileChangeOf
import com.dzshzx.zeta.ui.components.FoldEnter
import com.dzshzx.zeta.ui.components.FoldExit
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LinkText
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.appendLink
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 折叠时先露出的对比行数。 */
private const val PreviewLines = 12

/** 展开后最多画这么多行，再多的只报个数（整份重写的大文件）。 */
private const val MaxShownLines = 1500

/** 一行太长时只画前面这些字。 */
private const val MaxLineChars = 400

/** 工作组折叠时，在摘要下面直接露出的改动最多几处。 */
private const val CollapsedCards = 3

/** 改文件的那一步能画成对比时返回改动；失败了的（没改成）不画，照普通步骤显示报错。 */
@Composable
fun rememberFileChange(step: ToolStep): FileChange? =
    remember(step.name, step.input, step.patch, step.status) {
        if (step.status == ToolStatus.Failed) null
        else fileChangeOf(step.name, step.input, step.patch)
    }

/** 时间线里改文件的一步：标题行带增删行数，点标题行展开或收起下面的对比，默认收着。 */
@Composable
fun FileEditStepRow(step: ToolStep, change: FileChange) {
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val colors = COUITheme.colorScheme
    val dark = COUITheme.colorScheme.isDark
    val mutedInk = colors.labelPrimary.copy(alpha = if (dark) 0.56f else 0.52f)
    val iconInk = colors.labelPrimary.copy(alpha = if (dark) 0.50f else 0.45f)
    val presentation = step.presentation()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = presentation.icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (step.running) colors.primary else iconInk,
            )
            Spacer(Modifier.width(10.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                PulsingText(
                    text = presentation.title,
                    active = step.running,
                    color = mutedInk,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                ChangeCounts(change)
            }
            Chevron(open)
        }
        AnimatedVisibility(visible = open, enter = FoldEnter, exit = FoldExit) {
            FileChangeCard(
                change,
                header = false,
                modifier = Modifier.padding(start = 25.dp, bottom = 8.dp),
            )
        }
    }
}

/** 工作组折叠时摘要下面露出的改动：最多 [CollapsedCards] 处，多出来的点一下展开整组看。 */
@Composable
fun CollapsedFileChanges(steps: List<WorkStep>, onShowAll: () -> Unit) {
    val edits = steps.filter {
        it is ToolStep && it.name in FileEditTools && it.status != ToolStatus.Failed
    }
    if (edits.isEmpty()) return
    val changes = edits.mapNotNull { step -> key(step.id) { rememberFileChange(step as ToolStep) } }
    if (changes.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        changes.take(CollapsedCards).forEach { change -> FileChangeCard(change, header = true) }
        if (changes.size > CollapsedCards) {
            LinkText(
                text =
                    buildAnnotatedString {
                        appendLink(
                            "还有 ${changes.size - CollapsedCards} 处改动，展开查看",
                            onClick = onShowAll,
                        )
                    },
                style = COUITheme.textStyles.bodyS.copy(fontSize = 12.5.sp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/** “+3 −1”。 */
@Composable
private fun ChangeCounts(change: FileChange) {
    val palette = rememberDiffPalette()
    val style =
        COUITheme.textStyles.bodyS.copy(
            fontFamily = MonoFont,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
        )
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (change.added > 0 || change.removed == 0)
            Text("+${change.added}", style = style, color = palette.added)
        if (change.added > 0 && change.removed > 0) Spacer(Modifier.width(5.dp))
        if (change.removed > 0) Text("−${change.removed}", style = style, color = palette.removed)
    }
}

/**
 * 一处文件改动的红绿对比：行号、增删记号、内容；长的先只露前 [PreviewLines] 行。 [header] 时是工作组折叠时露出的卡片：顶上一行文件名和增删行数，
 * 点它展开或收起对比，默认收成这一行；不带 [header] 的在时间线里，标题行已经管着收起展开。
 */
@Composable
fun FileChangeCard(change: FileChange, header: Boolean, modifier: Modifier = Modifier) {
    val colors = COUITheme.colorScheme
    val dark = COUITheme.colorScheme.isDark
    val shape = RoundedCornerShape(10.dp)
    var open by
        rememberSaveable(change.path, change.lineCount, change.added, header) {
            mutableStateOf(!header)
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.labelPrimary.copy(alpha = if (dark) 0.05f else 0.028f), shape)
                .border(
                    0.5.dp,
                    colors.labelPrimary.copy(alpha = if (dark) 0.10f else 0.07f),
                    shape,
                ),
    ) {
        if (header) {
            FileChangeHeader(change, open) { open = !open }
        } else {
            kindLabel(change)?.let { label ->
                Text(
                    text = label,
                    style = COUITheme.textStyles.bodyXS.copy(fontSize = 11.sp),
                    color = colors.labelSecondary,
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp),
                )
            }
        }
        AnimatedVisibility(visible = open, enter = FoldEnter, exit = FoldExit) {
            Column {
                if (header) CardDivider()
                ChangeBody(change)
            }
        }
    }
}

/** 卡片里的对比本身：长的先只露前 [PreviewLines] 行，底下带展开全部的一行。 */
@Composable
private fun ChangeBody(change: FileChange) {
    val palette = rememberDiffPalette()
    var expanded by
        rememberSaveable(change.path, change.lineCount, change.added) { mutableStateOf(false) }
    val long = change.lineCount > PreviewLines
    val limit =
        when {
            !long -> change.lineCount
            expanded -> MaxShownLines
            else -> PreviewLines
        }
    val shown = remember(change, limit) { takeLines(change.hunks, limit) }
    val numberWidth = remember(change) { numberWidth(change) }
    if (change.hunks.isEmpty()) NoLinesNote(change)
    HunkList(shown, change.numbered, numberWidth, palette)
    val hidden = change.lineCount - shown.sumOf { it.lines.size }
    if (long) ExpandToggle(change.lineCount, hidden, expanded) { expanded = !expanded }
    if (change.truncated) TruncatedNote()
}

/** 记录里的内容被截断时的说明。 */
@Composable
private fun TruncatedNote() {
    Text(
        text = "内容太长，记录里只留了前一部分，对比到截断处为止",
        style = COUITheme.textStyles.bodyXS.copy(fontSize = 11.sp),
        color = COUITheme.colorScheme.labelSecondary,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 7.dp),
    )
}

/** 卡片顶上那一行和对比之间的分隔线。 */
@Composable
private fun CardDivider() {
    val colors = COUITheme.colorScheme
    Box(
        Modifier.fillMaxWidth()
            .height(0.5.dp)
            .background(colors.labelPrimary.copy(alpha = if (colors.isDark) 0.10f else 0.07f)),
    )
}

/** 没有可对比的行时的说明：新建的是空文件，否则是没有改动。 */
@Composable
private fun NoLinesNote(change: FileChange) {
    Text(
        text = if (change.kind == FileChange.Kind.Create) "空文件" else "没有改动",
        style = COUITheme.textStyles.bodyS.copy(fontSize = 12.sp),
        color = COUITheme.colorScheme.labelSecondary,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** 要显示的各段对比，段与段之间是省略记号。 */
@Composable
private fun HunkList(
    shown: List<DiffHunk>,
    numbered: Boolean,
    numberWidth: Int,
    palette: DiffPalette,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        shown.forEachIndexed { index, hunk ->
            if (index > 0) HunkGap(palette)
            HunkText(hunk.lines, numbered, numberWidth, palette)
        }
    }
}

/** 卡片顶上的一行：新建或修改的图标、文件名、改动方式、增删行数和展开箭头，点它展开或收起对比。 */
@Composable
private fun FileChangeHeader(change: FileChange, open: Boolean, onToggle: () -> Unit) {
    val colors = COUITheme.colorScheme
    val dark = colors.isDark
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                if (change.kind == FileChange.Kind.Create) ZetaIcons.FileAdd else COUIIcons.Edit,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = colors.labelPrimary.copy(alpha = if (dark) 0.50f else 0.45f),
        )
        Spacer(Modifier.width(7.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = change.path.substringAfterLast('/'),
                style =
                    COUITheme.textStyles.bodyM.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                color = colors.labelPrimary.copy(alpha = 0.86f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            kindLabel(change)?.let { label ->
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = COUITheme.textStyles.bodyXS.copy(fontSize = 11.sp),
                    color = colors.labelSecondary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        ChangeCounts(change)
        Chevron(open)
    }
}

/** 长改动底下展开、收起的一行；展开后还有太长没显示的行时注明。 */
@Composable
private fun ExpandToggle(lineCount: Int, hidden: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                when {
                    !expanded -> "展开全部改动 · 共 $lineCount 行"
                    hidden > 0 -> "收起 · 还有 $hidden 行太长未显示"
                    else -> "收起"
                },
            style =
                COUITheme.textStyles.bodyS.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            color = COUITheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Chevron(expanded)
    }
}

private fun kindLabel(change: FileChange): String? =
    when {
        change.kind == FileChange.Kind.Create -> "新文件"
        change.kind == FileChange.Kind.Overwrite -> "整份重写"
        change.replaceAll -> "全部替换"
        else -> null
    }

/** 两段改动之间省略的没变的行。 */
@Composable
private fun HunkGap(palette: DiffPalette) {
    Box(
        modifier =
            Modifier.fillMaxWidth().padding(vertical = 2.dp).height(18.dp).background(palette.gap),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "⋯",
            style = COUITheme.textStyles.bodyXS.copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = palette.number,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** 一段对比画成两块文字：左边行号和记号固定不动，右边内容不折行、可以横向滑动。 每行的底色画在整行后面，按右边文字的排版结果取每行的上下边。 */
@Composable
private fun HunkText(
    lines: List<DiffLine>,
    numbered: Boolean,
    numberWidth: Int,
    palette: DiffPalette,
) {
    val style = remember {
        TextStyle(fontFamily = MonoFont, fontSize = 11.5.sp, lineHeight = 17.sp)
    }
    val gutter =
        remember(lines, numbered, numberWidth, palette) {
            gutterText(lines, numbered, numberWidth, palette)
        }
    val body = remember(lines) { lines.joinToString("\n") { displayLine(it.text) } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Row(
        modifier =
            Modifier.fillMaxWidth().drawBehind {
                val result = layout ?: return@drawBehind
                drawLineBackgrounds(lines, result, palette)
            },
    ) {
        Text(
            gutter,
            style = style,
            color = palette.text,
            softWrap = false,
            modifier = Modifier.padding(start = 8.dp, end = 6.dp),
        )
        Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Text(
                text = body,
                style = style,
                color = palette.text,
                softWrap = false,
                onTextLayout = { layout = it },
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}

/** 一段对比左边固定的那块：每行的行号（删掉的行用旧行号）和增删记号。 */
private fun gutterText(
    lines: List<DiffLine>,
    numbered: Boolean,
    numberWidth: Int,
    palette: DiffPalette,
): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { index, line ->
        if (index > 0) append('\n')
        if (numbered) {
            val number = if (line.kind == DiffKind.Removed) line.oldLine else line.newLine
            withStyle(SpanStyle(color = palette.number)) {
                append((number?.toString() ?: "").padStart(numberWidth))
            }
            append(' ')
        }
        when (line.kind) {
            DiffKind.Added -> withStyle(SpanStyle(color = palette.added)) { append('+') }
            DiffKind.Removed -> withStyle(SpanStyle(color = palette.removed)) { append('-') }
            DiffKind.Context -> append(' ')
        }
    }
}

/** 在整行后面画增删行的底色，上下边取右边文字排版出的每行位置。 */
private fun DrawScope.drawLineBackgrounds(
    lines: List<DiffLine>,
    result: TextLayoutResult,
    palette: DiffPalette,
) {
    lines.forEachIndexed { index, line ->
        val color =
            when (line.kind) {
                DiffKind.Added -> palette.addedBackground
                DiffKind.Removed -> palette.removedBackground
                DiffKind.Context -> return@forEachIndexed
            }
        if (index >= result.lineCount) return@forEachIndexed
        val top = result.getLineTop(index)
        drawRect(
            color,
            topLeft = Offset(0f, top),
            size = Size(size.width, result.getLineBottom(index) - top),
        )
    }
}

/** 制表符展开，回车和别的换行符去掉，免得一行排成几行、和行号对不上。 */
private fun displayLine(text: String): String {
    val clean = text.replace("\t", "    ").replace("\r", "").replace(' ', ' ').replace(' ', ' ')
    return if (clean.length > MaxLineChars) clean.take(MaxLineChars) + " …" else clean
}

/** 从头取最多 [limit] 行，最后一段可能只取一部分。 */
private fun takeLines(hunks: List<DiffHunk>, limit: Int): List<DiffHunk> {
    var left = limit
    val result = ArrayList<DiffHunk>()
    for (hunk in hunks) {
        if (left <= 0) break
        result.add(if (hunk.lines.size <= left) hunk else DiffHunk(hunk.lines.take(left)))
        left -= hunk.lines.size
    }
    return result
}

/** 行号栏的宽度（字符数）：最大行号有几位；不带行号的是 0。 */
private fun numberWidth(change: FileChange): Int =
    if (change.numbered) maxLineNumber(change.hunks).toString().length else 0

private fun maxLineNumber(hunks: List<DiffHunk>): Int =
    hunks.maxOfOrNull { hunk -> hunk.lines.maxOf { maxOf(it.oldLine ?: 0, it.newLine ?: 0) } } ?: 0

private class DiffPalette(
    val added: Color,
    val removed: Color,
    val addedBackground: Color,
    val removedBackground: Color,
    val number: Color,
    val text: Color,
    val gap: Color,
)

@Composable
private fun rememberDiffPalette(): DiffPalette {
    val dark = COUITheme.colorScheme.isDark
    val ink = COUITheme.colorScheme.labelPrimary
    val error = COUITheme.colorScheme.error
    val success = COUITheme.colorScheme.success
    return remember(dark, ink, error, success) { diffPalette(dark, ink, error, success) }
}

private fun diffPalette(dark: Boolean, ink: Color, error: Color, success: Color): DiffPalette =
    DiffPalette(
        added = if (dark) Color(0xFF5BC27A) else Color(0xFF1E8E3E),
        removed = if (dark) error.copy(alpha = 0.95f) else error,
        addedBackground = success.copy(alpha = if (dark) 0.17f else 0.12f),
        removedBackground = error.copy(alpha = if (dark) 0.18f else 0.10f),
        number = ink.copy(alpha = if (dark) 0.38f else 0.36f),
        text = ink.copy(alpha = if (dark) 0.88f else 0.84f),
        gap = ink.copy(alpha = if (dark) 0.04f else 0.03f),
    )
