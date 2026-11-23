package com.dzshzx.zeta.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagTopic
import com.dzshzx.zeta.core.voice.SpeechBackend
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.tools.rootShell
import com.dzshzx.zeta.xposed.PowerKeyPolicy

/** 默认数字助理现在是谁。 */
data class AssistantOwner(
    /** 本包是默认数字助理。 */
    val self: Boolean,
    /** 默认助理的包名；没有设置时为 null。 */
    val packageName: String?,
    val label: String?,
    /** 系统默认语音识别服务（secure `voice_recognition_service`）的包名；没有设置时为 null。 */
    val recognizerPackage: String? = null,
) {
    /** 开机时默认助理保得住：默认助理是 Zeta 时，系统默认识别要是它自己的识别服务（见 docs/assistant.md「开机保持默认助理」）。 */
    val keepsOnBoot: Boolean
        get() =
            packageName == null ||
                !AppVariant.isZetaPackage(packageName) ||
                recognizerPackage == packageName

    /** 诊断页「系统语音识别」那一行：是谁。保不住默认助理的提醒在那一行的副标题上。 */
    val recognizerSummary: String
        get() = name(recognizerPackage, recognizerPackage)

    /** 设置页、诊断页上的一句。 */
    val summary: String
        get() =
            if (self) {
                if (AppVariant.current.isDev) Strings.assistantRoleSelfDev()
                else Strings.assistantRoleSelf()
            } else name(packageName, label)

    private fun name(pkg: String?, label: String?): String =
        when {
            pkg == null -> Strings.assistantRoleNone()
            AppVariant.isZetaPackage(pkg) -> Strings.assistantRoleOther(AppVariant(pkg).label)
            else -> Strings.assistantRoleOther(label ?: pkg)
        }
}

/** 默认数字助理：查现在是谁，打开系统的默认助理设置让用户自己选。不替用户换。 Android 的助理角色不能由应用弹窗请求（系统不让），所以只跳设置。 */
object AssistantRole {
    fun owner(context: Context): AssistantOwner {
        val self = runCatching {
            context
                .getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }
            .getOrDefault(false)
        val component =
            listOf("assistant", "voice_interaction_service").firstNotNullOfOrNull { key ->
                runCatching { Settings.Secure.getString(context.contentResolver, key) }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        val pkg = PowerKeyPolicy.packageOf(component) ?: if (self) context.packageName else null
        val recognizer =
            PowerKeyPolicy.packageOf(
                runCatching {
                    Settings.Secure.getString(context.contentResolver, SpeechBackend.SETTING)
                }
                    .getOrNull(),
            )
        return AssistantOwner(
            self,
            pkg,
            pkg?.let { AssistCaptures.appLabel(context, it) },
            recognizer,
        )
    }

    /**
     * 把系统默认语音识别服务换成本包的 [ZetaRecognitionService]（它转给手机原有的识别服务），开机后默认助理才保得住； 为什么、为什么不直接写小米的，见
     * docs/assistant.md「开机保持默认助理」。普通应用写不了 secure 设置，用 root； 已经是本服务就不动。在
     * [ZetaVoiceInteractionService.onReady] 里调（不在主线程）。
     */
    fun claimRecognizer(context: Context) {
        val ours = ComponentName(context, ZetaRecognitionService::class.java).flattenToShortString()
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, SpeechBackend.SETTING)
        }
            .getOrNull()
        val command = recognizerCommand(current, ours) ?: return
        val result = rootShell(command)
        if (result.exitCode == 0) {
            DiagLog.info(DiagTopic.App, "系统默认语音识别换成 Zeta（原来是 ${current ?: "空"}），开机后默认助理保得住")
        } else {
            DiagLog.warn(DiagTopic.App, "没能把系统默认语音识别换成 Zeta（${result.exitCode}）：重启后默认助理可能被换掉")
        }
    }

    /** 要换时的命令；[current] 已经是 [ours] 时为 null。 */
    internal fun recognizerCommand(current: String?, ours: String): String? {
        val same =
            current?.let(ComponentName::unflattenFromString)?.let {
                it == ComponentName.unflattenFromString(ours)
            } ?: false
        return if (same) null else "settings put secure ${SpeechBackend.SETTING} '$ours'"
    }

    /** 系统的默认数字助理设置；HyperOS 上没有这一页时退到默认应用设置。 */
    fun openSettings(context: Context): Boolean {
        for (action in
            listOf(
                Settings.ACTION_VOICE_INPUT_SETTINGS,
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            )) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (
                context.packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0),
                ) != null && runCatching { context.startActivity(intent) }.isSuccess
            ) {
                return true
            }
        }
        return false
    }
}

/** 电源键接管模块（xposed/PowerKeyHook.kt）的状态，从它写的日志里看。 */
data class PowerKeyStatus(
    /** 装上了没有：true 装上了，false 没装（[installNote] 是原因），null 日志里没有安装记录。 */
    val installed: Boolean?,
    val installNote: String?,
    /** 最近的记录，旧的在前，每条前面是时间。 */
    val recent: List<String>,
) {
    val summary: String
        get() =
            when (installed) {
                true -> Strings.assistantPowerKeyInstalled()
                false -> Strings.assistantPowerKeyNotInstalled()
                null ->
                    if (recent.isEmpty()) Strings.assistantPowerKeyNoRecord()
                    else Strings.assistantPowerKeyHasRecord()
            }
}

/** 读电源键模块的日志：LSPosed 的模块日志（本次开机以来的，在 /data/adb/lspd/log，要 root），没有时退到 logcat。 解析是纯函数，单元测试覆盖。 */
object PowerKeyLog {
    private const val MAX = 40

    /** 在后台线程调用。读不到返回 null（没有 root）。 */
    fun read(): PowerKeyStatus? {
        val tag = PowerKeyPolicy.LOG_TAG
        val script =
            "f=\$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 1); " +
                "[ -n \"\$f\" ] && grep -a '$tag' \"\$f\" | tail -n $MAX; echo '$SPLIT'; logcat -d -v time -s '$tag:I' 2>/dev/null | tail -n $MAX"
        val result = runCatching { LinuxEnv.runRoot(script, 15_000) }.getOrNull() ?: return null
        if (result.exitCode != 0 && result.stdout.isBlank()) return null
        return parse(result.stdout)
    }

    internal const val SPLIT = "@@zeta-logcat@@"

    /** LSPosed 日志里有就用它（从开机起都在），没有再看 logcat（会滚掉）。只认本包模块的标签 [tag]。 */
    fun parse(output: String, tag: String = PowerKeyPolicy.LOG_TAG): PowerKeyStatus {
        val lspd = output.substringBefore(SPLIT)
        val logcat = output.substringAfter(SPLIT, "")
        val lines = messages(lspd, tag).ifEmpty { messages(logcat, tag) }
        val install = lines.lastOrNull { it.second.startsWith("装上了") || it.second.startsWith("没装") }
        return PowerKeyStatus(
            installed = install?.second?.startsWith("装上了"),
            installNote = install?.second?.substringAfter('：', "")?.ifBlank { null },
            recent =
                lines.takeLast(MAX).map { (time, message) ->
                    if (time != null) "$time $message" else message
                },
        )
    }

    private val TIME = Regex("\\d{2}:\\d{2}:\\d{2}")
    private val LEAD = Regex("^[^\\u4e00-\\u9fffa-z]+")

    /** 每行取时间（时:分:秒）和标签后面的消息。 */
    private fun messages(text: String, tag: String): List<Pair<String?, String>> =
        text
            .lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf(tag).takeIf { it >= 0 } ?: return@mapNotNull null
                val message =
                    line.substring(at + tag.length).replace(LEAD, "").trim().trimEnd(']').trim()
                if (message.isEmpty()) null else TIME.find(line.substring(0, at))?.value to message
            }
            .toList()
}
