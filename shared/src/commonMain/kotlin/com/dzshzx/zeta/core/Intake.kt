package com.dzshzx.zeta.core

/** 从外面送进对话的东西从哪来。 */
enum class IntakeSource {
    Share,
    Open,
    Screenshot,
}

/**
 * 从外面送进对话的东西：别的应用分享来的文字和文件（ACTION_SEND / SEND_MULTIPLE）、 用 Zeta 打开的文件（ACTION_VIEW），或者一张截屏。
 *
 * 先定放进哪个对话（[offerIntake]：新对话，或者接着最近那个），再由那个对话的输入栏取走（[ConversationHub.incoming]）：
 * 文字进输入框，图片成图片附件，别的文件复制进 Linux 成文件附件。都不自动发送，用户补一句话再发。
 */
class Intake(
    val source: IntakeSource,
    val text: String? = null,
    /** 文件的地址（content:// 或 file://），按收到的顺序，不重复。 */
    val uris: List<String> = emptyList(),
    /** 已经做好的图片附件（截屏）。 */
    val images: List<Attachment> = emptyList(),
) {
    val isEmpty: Boolean
        get() = text.isNullOrBlank() && uris.isEmpty() && images.isEmpty()

    /** 用户还没选放进哪个对话，又送来一次：合在一起。 */
    operator fun plus(other: Intake): Intake =
        Intake(
            source = if (source == other.source) source else IntakeSource.Share,
            text =
                listOfNotNull(text, other.text)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .ifBlank { null },
            uris = (uris + other.uris).distinct(),
            images = images + other.images,
        )

    /** 给用户看的一句：收到了什么。 */
    val summary: String
        get() =
            intakeSummary(
                source,
                hasText = !text.isNullOrBlank(),
                files = uris.size,
                images = images.size,
            )

    companion object
}

fun intakeSummary(
    source: IntakeSource,
    hasText: Boolean,
    files: Int,
    images: Int,
): String {
    val parts = buildList {
        if (images > 0)
            add(
                if (source == IntakeSource.Screenshot) countOf(images, "张截屏")
                else countOf(images, "张图片"),
            )
        if (files > 0) add(countOf(files, "个文件"))
        if (hasText) add("一段文字")
    }
    return parts.joinToString("和").ifEmpty { "空的内容" }
}

private fun countOf(n: Int, unit: String) =
    (if (n == 1) "一" else n.toString()) + (if (n == 1) unit else " $unit")

/** 把分享和打开的 Intent 拆成 [Intake]。只处理取出来的值，不碰 Android 对象，方便单元测试。 */
object IntakeParser {
    /** 分享和打开的几种 Intent 动作（android.content.Intent 的同名常量）。 */
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    /** 文字超过这么长就不塞进输入框，存成一个文本文件当附件。 */
    const val MAX_INLINE_TEXT = 20_000

    /**
     * [streams] 是 EXTRA_STREAM 里的地址，[data] 是 ACTION_VIEW 的地址，[clipUris]、[clipTexts] 是 ClipData 里的。
     * 分享网页时浏览器常把标题放在 [subject]、网址放在 [text]：标题接在前面一行。 有的应用把文件地址也写进 EXTRA_TEXT，和文件重复的文字不要。
     */
    fun parse(
        action: String?,
        text: String?,
        subject: String?,
        streams: List<String>,
        data: String?,
        clipUris: List<String>,
        clipTexts: List<String>,
    ): Intake? =
        when (action) {
            IntakeParser.ACTION_VIEW ->
                data
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Intake(IntakeSource.Open, uris = listOf(it)) }
            IntakeParser.ACTION_SEND,
            IntakeParser.ACTION_SEND_MULTIPLE -> {
                val uris = (streams + clipUris).filter { it.isNotBlank() }.distinct()
                val body =
                    text?.trim()?.takeIf { it.isNotEmpty() }
                        ?: clipTexts
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString("\n")
                            .ifEmpty { null }
                            .takeIf { uris.isEmpty() }
                val kept = body?.takeUnless { it in uris }
                val title = subject?.trim()?.takeIf { it.isNotEmpty() }
                val full =
                    if (kept != null && title != null && !kept.contains(title)) "$title\n$kept"
                    else kept
                Intake(IntakeSource.Share, text = full, uris = uris).takeUnless { it.isEmpty }
            }
            else -> null
        }

    /** 把收到的文字接在输入框已有的草稿后面（另起一行）。 */
    fun mergeDraft(draft: String, text: String): String =
        if (draft.isBlank()) text else draft.trimEnd() + "\n" + text
}

/** 送进来的文件多大算大、多大不收。 */
object FileLimits {
    /** 超过它，附件上注明大小。 */
    const val NOTE_BYTES = 10_000_000L

    /** 超过它不复制：复制进 Linux 要多占一份同样大的空间，Claude 也读不完这么大的文件。 */
    const val MAX_BYTES = 200_000_000L

    /** 一次最多收几个文件。 */
    const val MAX_FILES = 20

    fun tooLarge(size: Long?): Boolean = size != null && size > MAX_BYTES

    /** 附件上显示的名字：大文件后面注明大小。 */
    fun label(name: String, size: Long?): String =
        if (size != null && size >= NOTE_BYTES) "$name · ${formatBytes(size)}" else name

    /** 没收某个文件的原因（[size] 不知道时是读到一半才发现超过上限）。 */
    fun tooLargeReason(name: String, size: Long?): String =
        "「$name」" +
            (if (size != null) "有 ${formatBytes(size)}，" else "") +
            "超过 ${formatBytes(MAX_BYTES)} 的上限，没有复制"
}

/** 「放进哪个对话」的一个候选：[blank] 是还什么都没有的新对话，[active] 是还在进行（在跑、等确认、后台任务没完）。 */
data class IntakeCandidate(val key: String, val blank: Boolean, val active: Boolean)

object IntakeRouting {
    /**
     * 除了新对话，还能接着放进去的那个对话：当前对话有内容就是它（用户最近在看的）； 当前是空白的新对话时，取后台最近切走、还在进行的那个；都没有返回
     * null，直接放进当前这个空白对话，不用问。 [parked] 按切走的先后排，最后一个是最近的。
     */
    fun pick(current: IntakeCandidate, parked: List<IntakeCandidate>): IntakeCandidate? =
        if (!current.blank) current else parked.lastOrNull { it.active }
}

private fun Conversation.candidate() =
    IntakeCandidate(
        key = openKey,
        blank = items.isEmpty() && sessionId == null && !busy,
        active = active,
    )

/** 能接着放进去的那个对话（见 [IntakeRouting.pick]）；没有时为 null。在主线程上调用。 */
fun ConversationHub.intakeTarget(): Conversation? {
    val others = parkedConversations
    val picked =
        IntakeRouting.pick(current.candidate(), others.map { it.candidate() }) ?: return null
    return if (picked.key == current.openKey) current
    else others.firstOrNull { it.openKey == picked.key }
}

/**
 * 外面送来了东西。当前是空白的新对话、后台也没有在进行的对话时，直接交给当前对话的输入栏；
 * 否则挂起来（[ConversationHub.pendingIntake]），等用户选放进新对话还是接着最近那个。在主线程上调用。
 */
fun ConversationHub.offerIntake(intake: Intake) {
    if (intake.isEmpty) return
    pendingIntake?.let {
        pendingIntake = it + intake
        return
    }
    if (intakeTarget() == null) deliverIntake(intake) else pendingIntake = intake
}

/** 用户选好了：[intoNew] 放进新对话，否则放进 [intakeTarget]（不是当前对话时先切过去）。在主线程上调用。 */
fun ConversationHub.placeIntake(intoNew: Boolean) {
    val intake = pendingIntake ?: return
    pendingIntake = null
    if (intoNew) {
        startNew()
    } else {
        intakeTarget()?.takeIf { it !== current }?.let { openConversation(it.openKey) }
    }
    deliverIntake(intake)
}

/** 用户不要了（关掉了「放进哪个对话」）。 */
fun ConversationHub.dropIntake() {
    pendingIntake = null
}

private fun ConversationHub.deliverIntake(intake: Intake) {
    incoming = incoming?.plus(intake) ?: intake
}
