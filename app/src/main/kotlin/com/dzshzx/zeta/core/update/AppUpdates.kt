package com.dzshzx.zeta.core.update

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.BuildConfig
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.Notifier
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.ui.MainActivity
import com.dzshzx.zeta.ui.settings.SettingsLink
import com.dzshzx.zeta.ui.settings.SettingsSubPage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置「关于」里检查更新那一行和「版本更新」页显示的状态。 */
internal sealed interface UpdateState {
    /** 这次启动以来还没查过。 */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class CheckFailed(val reason: String) : UpdateState

    /** 有新版本 [manifest]，下载、安装走到了 [step]。 */
    data class Available(val manifest: UpdateManifest, val step: UpdateStep = UpdateStep.Ready) :
        UpdateState
}

/** 有新版本以后的一步。 */
internal sealed interface UpdateStep {
    /** 还没下载。 */
    data object Ready : UpdateStep

    /** 下了 [done] 字节。 */
    data class Downloading(val done: Long) : UpdateStep

    data object Verifying : UpdateStep

    /** 下好、核对过了，有对话正在回答，等它们都结束再装。 */
    data object WaitingIdle : UpdateStep

    /** 没有 root，要用户先允许 Zeta「安装未知应用」。 */
    data object NeedsPermission : UpdateStep

    /** 下好、核对过了，等用户点安装（取消过系统的确认，或取消了等待）。 */
    data object Downloaded : UpdateStep

    /** 正在装：[root] 时经 pm 直接装、装好重新打开，否则在系统的安装界面上等用户确认。 */
    data class Installing(val root: Boolean) : UpdateStep

    data class Failed(val reason: String) : UpdateStep
}

/**
 * 应用内更新：只有官方签的包才查（[UpdateTrust]）。设置「关于」里手动查；应用回到前台时（[onForeground]）每天最多自动查一次，
 * 有新版本发一条通知，点开到「版本更新」页。不常驻、不定时唤醒。
 *
 * 新版本的 APK 下载到应用缓存目录的 update/，核对大小、sha256（对清单）和包名、versionCode、签名（对已装的本应用，见 [ApkCheck]），
 * 哪一项不对都删掉文件、说原因。装包会结束本应用的界面：进行中的对话由托管脚本跑完，正在执行的手机工具调用却会失败， 所以有对话正在回答（手机工具只在一轮回答里被调用）时先不装，等都结束了再装。
 *
 * 全进程一份（[get]），状态是 mutableStateOf，界面读了跟着变。都在主线程上调用。
 */
internal class AppUpdates private constructor(private val app: Context) {
    private val settings = Settings.of(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = UpdateHttp(userAgent = "Zeta/${BuildConfig.VERSION_NAME}")
    private val dir
        get() = File(app.cacheDir, "update")

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    private var job: Job? = null

    /** 这次启动以来删过 update/ 下剩下的文件没有（[onForeground]）。 */
    private var cleaned = false

    /** 每次开始下载、取消时加一：被取消的那次留下的进度回调不再改状态。 */
    @Volatile private var run = 0

    /** 界面在前台（MainActivity 在 onStart 和 onStop 之间）：只在这时装，见 [install]。 */
    private var foreground = false

    private var enabledOverride: Boolean? = null
    private val signedByTrusted by lazy {
        UpdateTrust.shouldCheck(
            ApkCheck.readInstalled(app.packageManager, app.packageName),
            UpdateTrust.trustedCert(settings),
        )
    }

    /** 本包检查更新吗：现在签它的是正式发版证书（测试版可以用调试覆盖换掉，见 [UpdateTrust]）。 */
    val enabled: Boolean
        get() = enabledOverride ?: signedByTrusted

    /** 设置里点「检查更新」。 */
    fun check() {
        if (!enabled || state == UpdateState.Checking || state is UpdateState.Available) return
        settings.updateCheckedAt = System.currentTimeMillis()
        scope.launch { checkNow(notify = false) }
    }

    /** 应用回到前台：打开了自动检查、今天还没查过就查一次，有新版本发通知。 */
    fun onForeground() {
        foreground = true
        if (!enabled) return
        if (!cleaned) {
            cleaned = true
            cleanDownloads()
        }
        if (
            !settings.autoUpdateCheck ||
                state == UpdateState.Checking ||
                state is UpdateState.Available
        )
            return
        val today = LocalDate.now()
        if (
            LocalDate.ofInstant(
                Instant.ofEpochMilli(settings.updateCheckedAt),
                ZoneId.systemDefault(),
            ) == today
        )
            return
        settings.updateCheckedAt = System.currentTimeMillis()
        scope.launch { checkNow(notify = true) }
    }

    private suspend fun checkNow(notify: Boolean) {
        state = UpdateState.Checking
        state =
            try {
                val manifest = http.manifest(UpdateTrust.manifestUrl(settings))
                if (manifest.isNewerThan(BuildConfig.VERSION_CODE)) {
                    if (notify) notifyOnce(manifest)
                    UpdateState.Available(manifest)
                } else {
                    UpdateState.UpToDate
                }
            } catch (e: UpdateException) {
                UpdateState.CheckFailed(e.reason)
            }
    }

    /** 「更新」：下载、核对，再等空闲安装。失败后点「重试」也是它。 */
    fun start() {
        val available = state as? UpdateState.Available ?: return
        if (job?.isActive == true) return
        val id = ++run
        job = scope.launch { download(id, available.manifest) }
    }

    /** 下好、核对过以后的「安装」（取消过确认、刚允许了安装未知应用）。 */
    fun installNow() {
        val available = state as? UpdateState.Available ?: return
        if (job?.isActive == true) return
        val file = File(dir, available.manifest.apk)
        if (!file.isFile) return start()
        val id = ++run
        job = scope.launch { install(id, file) }
    }

    /** 下载中取消回到「更新」，等空闲时取消回到「安装」。 */
    fun cancel() {
        val available = state as? UpdateState.Available ?: return
        val back =
            when (available.step) {
                is UpdateStep.Downloading -> UpdateStep.Ready
                UpdateStep.WaitingIdle -> UpdateStep.Downloaded
                else -> return
            }
        run++
        job?.cancel()
        state = available.copy(step = back)
    }

    /** 系统的安装确认被用户取消了（[UpdateInstallReceiver]）。 */
    fun installCancelled() = setStep(run, UpdateStep.Downloaded)

    /** 交给系统安装后装不上（[UpdateInstallReceiver]）。 */
    fun installFailed(reason: String) = setStep(run, UpdateStep.Failed(reason))

    /** 界面退到后台。 */
    fun onBackground() {
        foreground = false
    }

    private fun setStep(id: Int, step: UpdateStep) {
        val available = state as? UpdateState.Available ?: return
        if (id == run) state = available.copy(step = step)
    }

    private suspend fun download(id: Int, manifest: UpdateManifest) {
        val file = File(dir, manifest.apk)
        try {
            if (!withContext(Dispatchers.IO) { downloaded(file, manifest) }) {
                withContext(Dispatchers.IO) { cleanDownloads() }
                dir.mkdirs()
                setStep(id, UpdateStep.Downloading(0))
                var shown = 0L
                http.download(manifest, file) { done ->
                    // 大约每 1% 更新一次界面
                    if (done - shown >= manifest.size / 100 || done == manifest.size) {
                        shown = done
                        setStep(id, UpdateStep.Downloading(done))
                    }
                }
            }
            setStep(id, UpdateStep.Verifying)
            val reason = withContext(Dispatchers.IO) { verify(file, manifest) }
            if (reason != null) {
                file.delete()
                return setStep(id, UpdateStep.Failed("核对失败：$reason，已删掉下载的文件"))
            }
            install(id, file)
        } catch (e: UpdateException) {
            setStep(id, UpdateStep.Failed(e.reason))
        }
    }

    /** 上次已经下好、核对过的同一个文件（大小、sha256 对得上）就不再下。 */
    private fun downloaded(file: File, manifest: UpdateManifest): Boolean =
        file.isFile && file.length() == manifest.size && UpdateHttp.sha256(file) == manifest.sha256

    private fun verify(file: File, manifest: UpdateManifest): String? {
        val installed =
            ApkCheck.readInstalled(app.packageManager, app.packageName) ?: return "读不出本应用自己的签名"
        return ApkCheck.verdict(ApkCheck.readArchive(app.packageManager, file), manifest, installed)
    }

    /** 等到没有对话在回答、界面在前台时再装：在后台装的话，有 root 时新版本会盖到用户正用着的应用上面， 没有 root 时系统的确认界面又被后台启动的限制拦下。 */
    private suspend fun install(id: Int, file: File) {
        if (busy() || !foreground) {
            setStep(id, UpdateStep.WaitingIdle)
            while (busy() || !foreground) delay(IDLE_POLL_MS)
        }
        if (withContext(Dispatchers.IO) { LinuxEnv.hasRoot() }) {
            setStep(id, UpdateStep.Installing(root = true))
            val component = app.packageName + "/" + MainActivity::class.java.name
            val display = MainActivity.liveDisplay ?: 0
            // 装成了本应用随即被结束，新版本由脚本重新打开；走到下一行的是没装上
            val reason =
                withContext(Dispatchers.IO) {
                    UpdateInstaller.installAsRoot(file, component, display)
                }
            if (reason != null) setStep(id, UpdateStep.Failed(reason))
            return
        }
        if (!UpdateInstaller.canRequestInstalls(app)) return setStep(id, UpdateStep.NeedsPermission)
        setStep(id, UpdateStep.Installing(root = false))
        withContext(Dispatchers.IO) { UpdateInstaller.installWithSession(app, file) }
            ?.let { setStep(id, UpdateStep.Failed(it)) }
    }

    /** 有对话正在回答（含一轮里正在调用的手机工具）。 */
    private fun busy(): Boolean =
        (app as? Application)?.let { ConversationHub.get(it).liveConversations.any { c -> c.busy } }
            ?: false

    /** 删掉 update/ 下的旧文件：装好以后剩下的、下到一半的。 */
    private fun cleanDownloads() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** 同一个新版本只发一次通知，点开到「版本更新」页。 */
    private fun notifyOnce(manifest: UpdateManifest) {
        if (settings.updateNotified == manifest.versionCode) return
        settings.updateNotified = manifest.versionCode
        val open =
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(SettingsLink.EXTRA, SettingsSubPage.Update.link)
        val pending =
            PendingIntent.getActivity(
                app,
                Notifier.ID_UPDATE,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        runCatching {
            Notifier.post(
                app,
                null,
                Notifier.ID_UPDATE,
                "Zeta 有新版本 ${manifest.versionName}",
                "点开看更新说明、下载并安装",
                pending,
                channel = Notifier.CHANNEL_UPDATE,
            )
        }
    }

    /** 截图测试用：不读签名、不联网，直接摆出 [state]。 */
    @VisibleForTesting
    fun preview(enabled: Boolean, state: UpdateState) {
        enabledOverride = enabled
        this.state = state
    }

    companion object {
        private const val IDLE_POLL_MS = 2_000L

        @Volatile private var shared: AppUpdates? = null

        /** 本进程的那一份。单元测试里每个用例是新的 Application，跟着换一份。 */
        fun get(context: Context): AppUpdates {
            val app = context.applicationContext
            shared
                ?.takeIf { it.app === app }
                ?.let {
                    return it
                }
            return synchronized(this) {
                shared?.takeIf { it.app === app } ?: AppUpdates(app).also { shared = it }
            }
        }
    }
}
