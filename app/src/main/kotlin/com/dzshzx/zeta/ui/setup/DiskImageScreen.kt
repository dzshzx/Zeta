package com.dzshzx.zeta.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.DiskImageController
import com.dzshzx.zeta.core.DiskImageOp
import com.dzshzx.zeta.core.DiskImageStatus
import com.dzshzx.zeta.core.EnvController
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.core.formatBytes
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.TextInputDialog
import com.dzshzx.zeta.ui.components.rememberNow
import com.dzshzx.zeta.ui.settings.ValueRow
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator

/** 磁盘镜像页上要先确认的操作。 */
private enum class ImageAsk {
    Convert,
    Resize,
    Check,
    DeleteOld,
}

/** 「磁盘镜像」：Zeta 自带的 Debian 存在镜像文件里时的大小、检查、释放空间；还是普通目录时转成镜像。 */
@Composable
internal fun DiskImageScreen(hub: ConversationHub, onBack: () -> Unit) {
    val image = hub.environment.image
    val status = image.status
    var asking by remember { mutableStateOf<ImageAsk?>(null) }
    var lastAsking by remember { mutableStateOf(ImageAsk.Check) }
    asking?.let { lastAsking = it }
    var sizeDraft by remember { mutableStateOf("") }
    // 做完的维护结果点一下收起；状态文件里一直留着上一次的，这边只记收起过哪一次
    var dismissed by remember { mutableStateOf<DiskImageOp?>(null) }
    LaunchedEffect(Unit) { image.refresh() }
    val ask: (ImageAsk) -> Unit = { kind ->
        if (kind == ImageAsk.Convert || kind == ImageAsk.Resize)
            sizeDraft =
                if (kind == ImageAsk.Resize)
                    (status?.sizeBytes?.div(DiskImageStatus.GB) ?: 0).toString()
                else DiskImageStatus.defaultSizeGb(status?.freeBytes).toString()
        asking = kind
    }

    MiuixPage(
        title = Strings.setupImageTitle(),
        onBack = onBack,
        dialogs = {
            ImageDialogs(
                asking = asking,
                kind = lastAsking,
                status = status,
                sizeDraft = sizeDraft,
                onSizeDraft = { sizeDraft = it },
                onConfirm = { kind ->
                    asking = null
                    when (kind) {
                        ImageAsk.Convert -> sizeDraft.toIntOrNull()?.let(image::convert)
                        ImageAsk.Resize -> sizeDraft.toIntOrNull()?.let(image::resize)
                        ImageAsk.Check -> image.check()
                        ImageAsk.DeleteOld -> image.deleteOld()
                    }
                },
                onDismiss = { asking = null },
            )
        },
    ) {
        when {
            status == null -> item(key = "checking") { CheckingGroup() }
            status.image -> {
                item(key = "image") { ImageGroup(status) }
                item(key = "maintain") { MaintainGroup(hub, image, status, ask) }
            }
            status.convertible -> item(key = "convert") { ConvertGroup(image, ask) }
            else -> item(key = "none") { NoImageGroup() }
        }
        status
            ?.op
            ?.takeIf { it != dismissed && recent(it) }
            ?.let { op ->
                item(key = "op") { OpGroup(op, onDismiss = { dismissed = op }) }
            }
        image.notice?.let { notice ->
            item(key = "notice") {
                MaintenanceGroup(
                    EnvController.Maintenance(
                        Strings.setupImageTitle(),
                        running = false,
                        detail = notice.text,
                        ok = notice.ok,
                    ),
                    onDismiss = image::dismissNotice,
                )
            }
        }
    }
}

@Composable
private fun CheckingGroup() {
    PreferenceGroup {
        item { ValueRow(Strings.setupStatus(), Strings.setupStateChecking()) }
    }
}

@Composable
private fun NoImageGroup() {
    PreferenceGroup(footer = Strings.setupImageNoneFooter()) {
        item { ValueRow(Strings.setupStatus(), Strings.setupImageNone()) }
    }
}

/** 「镜像」一组：挂没挂上、上限、Debian 里的用量、实际占手机多少。 */
@Composable
private fun ImageGroup(status: DiskImageStatus) {
    PreferenceGroup(title = Strings.setupImageGroup(), footer = Strings.setupImageFooter()) {
        item {
            ValueRow(
                Strings.setupStatus(),
                if (status.mounted) Strings.setupImageMounted() else Strings.setupImageNotMounted(),
            )
        }
        item { ValueRow(Strings.setupImageLimit(), limitText(status.sizeBytes)) }
        val used = status.usedBytes
        val available = status.availableBytes
        if (used != null && available != null) {
            item {
                ValueRow(
                    Strings.setupImageUsed(),
                    Strings.setupImageUsedValue(formatBytes(used), formatBytes(available)),
                )
            }
        }
        item { ValueRow(Strings.setupImageAllocated(), formatBytes(status.allocatedBytes)) }
    }
}

/** 「维护」一组：释放空间不用停 Debian；调整大小、检查要先停，有对话在进行时不让做。 */
@Composable
private fun MaintainGroup(
    hub: ConversationHub,
    image: DiskImageController,
    status: DiskImageStatus,
    ask: (ImageAsk) -> Unit,
) {
    val canStop = image.canStopDebian
    PreferenceGroup(
        title = Strings.setupImageMaintain(),
        footer =
            if (hub.conversationsWorking) Strings.setupImageChatRunning()
            else Strings.setupImageMaintainFooter(),
    ) {
        item {
            BasicComponent(
                title = Strings.setupImageTrim(),
                summary = Strings.setupImageTrimSummary(),
                enabled = image.idle && status.mounted,
                onClick = image::trim,
            )
        }
        item {
            BasicComponent(
                title = Strings.setupImageResize(),
                summary = Strings.setupImageResizeSummary(),
                enabled = canStop,
                onClick = { ask(ImageAsk.Resize) },
            )
        }
        item {
            BasicComponent(
                title = Strings.setupImageCheck(),
                summary = Strings.setupImageCheckSummary(),
                enabled = canStop,
                onClick = { ask(ImageAsk.Check) },
            )
        }
        if (status.oldDirectory) {
            item {
                BasicComponent(
                    title = Strings.setupImageDeleteOld(),
                    summary = Strings.setupImageDeleteOldSummary(),
                    enabled = image.idle && status.mounted,
                    onClick = { ask(ImageAsk.DeleteOld) },
                )
            }
        }
    }
}

/** 还是普通目录时：说明为什么要转，转成镜像。 */
@Composable
private fun ConvertGroup(image: DiskImageController, ask: (ImageAsk) -> Unit) {
    PreferenceGroup(
        title = Strings.setupImageConvertGroup(),
        footer = Strings.setupImageConvertFooter(),
    ) {
        item { ValueRow(Strings.setupStatus(), Strings.setupImageDirectory()) }
        item {
            BasicComponent(
                title = Strings.setupImageConvert(),
                summary = Strings.setupImageConvertSummary(),
                enabled = image.canStopDebian,
                onClick = { ask(ImageAsk.Convert) },
            )
        }
    }
}

@Composable
private fun ImageDialogs(
    asking: ImageAsk?,
    kind: ImageAsk,
    status: DiskImageStatus?,
    sizeDraft: String,
    onSizeDraft: (String) -> Unit,
    onConfirm: (ImageAsk) -> Unit,
    onDismiss: () -> Unit,
) {
    val sizing = kind == ImageAsk.Convert || kind == ImageAsk.Resize
    val size = sizeDraft.toIntOrNull()
    TextInputDialog(
        show = asking != null && sizing,
        title =
            if (kind == ImageAsk.Convert) Strings.setupImageConvert()
            else Strings.setupImageResize(),
        summary = sizeHint(kind, status),
        value = sizeDraft,
        onValueChange = { draft -> onSizeDraft(draft.filter(Char::isDigit).take(4)) },
        label = Strings.setupImageSizeLabel(),
        confirmText = Strings.setupImageStart(),
        confirmEnabled = size != null && size in 1..4096,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        onConfirm = { onConfirm(kind) },
        onDismissRequest = onDismiss,
    )
    ConfirmDialog(
        show = asking != null && !sizing,
        title =
            if (kind == ImageAsk.DeleteOld) Strings.setupImageDeleteOldTitle()
            else Strings.setupImageCheckTitle(),
        summary =
            if (kind == ImageAsk.DeleteOld) Strings.setupImageDeleteOldConfirm()
            else Strings.setupImageCheckConfirm(),
        confirmText =
            if (kind == ImageAsk.DeleteOld) Strings.setupImageDelete()
            else Strings.setupImageStart(),
        destructive = kind == ImageAsk.DeleteOld,
        onConfirm = { onConfirm(kind) },
        onDismissRequest = onDismiss,
    )
}

/** 输入上限时的说明：Debian 现在用了多少、手机还剩多少，以及要先停 Debian。 */
private fun sizeHint(kind: ImageAsk, status: DiskImageStatus?): String {
    val free = status?.freeBytes?.let(::formatBytes) ?: "?"
    return if (kind == ImageAsk.Convert) Strings.setupImageConvertHint(free)
    else Strings.setupImageResizeHint(status?.usedBytes?.let(::formatBytes) ?: "?", free)
}

/** 在做的、一小时内做完的维护才显示：状态文件里一直留着上一次的结果。 */
private fun recent(op: DiskImageOp): Boolean =
    op.running || System.currentTimeMillis() / 1000 - op.time < 3600

/** 做完（或被打断）的一项维护在维护卡片上的样子：标题是哪一项，内容是结果。 */
private fun opCard(op: DiskImageOp): EnvController.Maintenance =
    EnvController.Maintenance(
        title = opTitle(op.name),
        running = false,
        detail = if (op.interrupted) Strings.setupImageInterrupted() else opDetail(op),
        ok = !op.interrupted && op.succeeded,
    )

/** 一项维护：进行中的列出各步与进度，做完的是结果卡片，点一下收起。 */
@Composable
private fun OpGroup(op: DiskImageOp, onDismiss: () -> Unit) {
    if (op.running) OpProgressGroup(op) else MaintenanceGroup(opCard(op), onDismiss = onDismiss)
}

/** 进行中的一项维护：开头一行是总共用了多久，下面每一步一行，做完的写「完成」，正在做的有进度条（复制时）或转圈加这一步用了多久，后面的灰着。时间每秒都在走，免得看着像卡住了。 */
@Composable
private fun OpProgressGroup(op: DiskImageOp) {
    val now = nowSeconds()
    PreferenceGroup(title = opTitle(op.name)) {
        item(key = "total") {
            BasicComponent(
                title = Strings.setupMaintenanceRunning(),
                endActions =
                    op.started
                        .takeIf { it > 0 }
                        ?.let { started ->
                            {
                                PreferenceValue(
                                    Strings.setupImageElapsed(clockText(now - started)),
                                    clickable = false,
                                )
                            }
                        },
            )
        }
        op.steps.forEachIndexed { index, step ->
            item(key = step) { OpStepRow(op, step, index, now) }
        }
    }
}

@Composable
private fun OpStepRow(op: DiskImageOp, step: String, index: Int, now: Long) {
    val current = index == op.stepIndex
    val done = index < op.stepIndex
    val fraction = if (current) op.fraction else null
    BasicComponent(
        title = stepTitle(step),
        // 还没做到的步骤灰着
        enabled = current || done,
        summary =
            when {
                current -> runningStepText(op, now)
                step == "finish" -> Strings.setupImageStepFinishSummary()
                else -> null
            },
        endActions =
            when {
                done -> ({ PreferenceValue(Strings.setupStepDone(), clickable = false) })
                fraction != null -> ({
                        PreferenceValue("${(fraction * 100).toInt()}%", clickable = false)
                    })
                current -> ({ InfiniteProgressIndicator(size = 20.dp) })
                else -> null
            },
        bottomAction =
            fraction?.let { f ->
                { LinearProgressIndicator(progress = f, modifier = Modifier.fillMaxWidth()) }
            },
    )
}

/** 正在做的这一步的一句话：复制时是「4.5 GB / 10.1 GB · 38 MB/s · 约剩 2 分钟」，别的步骤是这一步用了多久（统计大小再加一句要多久）。 */
internal fun runningStepText(op: DiskImageOp, now: Long): String {
    val progress = op.progress
    if (op.message == "copy" && progress != null) {
        val (done, total) = progress
        return listOfNotNull(
                Strings.setupImageCopied(formatBytes(done * 1024), formatBytes(total * 1024)),
                op.bytesPerSecond?.let { Strings.setupImageSpeed(formatBytes(it)) },
                op.secondsLeft?.let { leftText(it) },
            )
            .joinToString(" · ")
    }
    return listOfNotNull(
            op.stepStarted
                .takeIf { it > 0 }
                ?.let { Strings.setupImageElapsed(clockText(now - it)) },
            Strings.setupImageMeasureHint().takeIf { op.message == "measure" },
        )
        .joinToString(" · ")
}

/** 安装向导里建磁盘镜像那一步的说明：在做哪一小步，再加 [runningStepText]。 */
internal fun installImageText(op: DiskImageOp, now: Long): String =
    listOf(stepTitle(op.steps[op.stepIndex]), runningStepText(op, now))
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

/** 每秒变一次的当前时刻（Unix 秒）：让显示的已用时间一直在走。 */
@Composable internal fun nowSeconds(): Long = rememberNow(1_000) / 1000

/** 用了多久，如 0:48、3:42、1:05:09。 */
internal fun clockText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = s / 60 % 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

private fun leftText(seconds: Long): String =
    if (seconds < 60) Strings.setupImageLeftUnderMinute()
    else Strings.setupImageLeftMinutes(((seconds + 59) / 60).toInt())

private fun opTitle(name: String): String =
    when (name) {
        "convert" -> Strings.setupImageConvertGroup()
        "resize" -> Strings.setupImageResize()
        "delete-old" -> Strings.setupImageDeleteOld()
        else -> Strings.setupImageCheck()
    }

private fun opDetail(op: DiskImageOp): String = if (op.succeeded) resultText(op) else op.message

private fun stepTitle(step: String): String =
    when (step) {
        "stop" -> Strings.setupImageStepStop()
        "measure" -> Strings.setupImageStepMeasure()
        "copy" -> Strings.setupImageStepCopy()
        "finish" -> Strings.setupImageStepFinish()
        "check" -> Strings.setupImageStepCheck()
        "resize" -> Strings.setupImageStepResize()
        "delete" -> Strings.setupImageStepDelete()
        else -> step
    }

private fun resultText(op: DiskImageOp): String =
    when (op.name) {
        "convert" -> Strings.setupImageResultConverted()
        "delete-old" -> Strings.setupImageResultDeleted()
        "resize" -> Strings.setupImageResultResized(op.message.toIntOrNull() ?: 0)
        else ->
            if (op.message == "clean") Strings.setupImageResultClean()
            else Strings.setupImageResultFixed()
    }

/** Linux 环境页「磁盘镜像」一行的说明。 */
internal fun diskImageSummary(status: DiskImageStatus?): String =
    when {
        status == null -> Strings.setupImageSummaryIdle()
        status.busy -> Strings.setupMaintenanceRunning()
        status.image ->
            status.usedBytes?.let {
                Strings.setupImageSummaryOn(limitText(status.sizeBytes), formatBytes(it))
            } ?: Strings.setupImageNotMounted()
        status.convertible -> Strings.setupImageSummaryOff()
        else -> Strings.setupImageNone()
    }

/** 镜像上限按填的时候的单位（GB，1024 进制的整数）显示，和输入框、调整大小的结果一致。 */
private fun limitText(bytes: Long): String = "${bytes / DiskImageStatus.GB} GB"
