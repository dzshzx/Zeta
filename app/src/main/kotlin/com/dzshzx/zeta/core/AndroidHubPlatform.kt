package com.dzshzx.zeta.core

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import com.dzshzx.zeta.core.diag.DiagLevel
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.ssh.SshLinks
import com.dzshzx.zeta.core.ssh.keystore
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.phone.PhoneBridge
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.ui.components.ProcessHub
import java.io.File
import kotlin.concurrent.thread

/**
 * 手机上的 [HubPlatform]：自带的 Debian（[environment]）、每个会话的手机工具和它们的后台屏幕、Zeta 之外经 root 连进来的客户端
 * （[PhoneBridge]）、胶囊与提醒（[alerts]）、前台服务，以及界面前后台和网络变化的监听。
 */
class AndroidHubPlatform(
    val app: Application,
    private val keyFactory: () -> SshKey = {
        SshKey.keystore(
                AppVariant.current.linuxName +
                    "@" +
                    Build.MODEL.replace(Regex("[^A-Za-z0-9._-]+"), "-"),
            )
            .also { key ->
                // 公钥那一行也放进应用的私有目录：设备测试（和用 adb 的人）从这里取，不用开设置页
                runCatching {
                    File(app.filesDir, PUBLIC_KEY_FILE).writeText(key.authorizedKey + "\n")
                }
            }
    },
) : HubPlatform {
    private lateinit var hub: ConversationHub

    override val settings: Settings = Settings.of(app)

    override val prompts: PromptList =
        object : PromptList {
            override val observed: List<SavedPrompt>
                get() = PromptStore.observed

            override fun load(): List<SavedPrompt> = PromptStore.load(app)

            override fun update(change: (List<SavedPrompt>) -> List<SavedPrompt>) =
                PromptStore.update(app, change)
        }

    override fun attach(hub: ConversationHub) {
        this.hub = hub
        DiagLog.init(File(app.filesDir, "diag/events.jsonl")) { level, text ->
            when (level) {
                DiagLevel.Info -> Log.i(TAG, text)
                DiagLevel.Warn -> Log.w(TAG, text)
                DiagLevel.Error -> Log.e(TAG, text)
            }
        }
    }

    override fun asset(name: String): ByteArray = app.assets.open(name).use { it.readBytes() }

    override fun prefs(name: String): PrefStore = SharedPrefStore(app, name)

    override val filesDir: File
        get() = app.filesDir

    /** 系统设置里的设备名（「关于手机」里能改），取不到时是型号。Zeta 开着时改了系统里的名字，下次启动才跟上。 */
    override val deviceName: String by lazy {
        android.provider.Settings.Global.getString(
                app.contentResolver,
                android.provider.Settings.Global.DEVICE_NAME,
            )
            ?.trim()
            ?.ifEmpty { null } ?: Build.MODEL
    }

    /**
     * 直连远程主机经随 APK 带的 zeta-ssh：它以原生库的名字打进 APK，装好后在 nativeLibraryDir 里（应用自己的数据目录不许执行程序）。 首选 ABI 是
     * x86_64、又有测试版带的那份时用那份：x86_64 模拟器的 arm64 转译跑不了 Go 程序（见 [SshLinks.EMULATOR_LIBRARY]，
     * 什么时候删也写在那里）。
     */
    override val sshLinks: SshLinks =
        SshLinks(keyFactory) {
            val dir = File(app.applicationInfo.nativeLibraryDir.orEmpty())
            File(dir, SshLinks.EMULATOR_LIBRARY).takeIf {
                Build.SUPPORTED_ABIS.firstOrNull() == "x86_64" && it.isFile
            } ?: File(dir, SshLinks.LIBRARY)
        }

    override val clientHint: String = clientHint("a chat app on their Android phone", "tap")

    override fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()

    /** Linux 环境：检查、安装、维护、SSH、Claude 账号的登录状态。 */
    val environment: EnvController by lazy {
        EnvController(hub) { name -> app.assets.open(name).bufferedReader().use { it.readText() } }
    }

    override val local: LocalEnvironment
        get() = environment

    /** 胶囊、结果卡片、完成与确认提醒、实时通知显示哪个对话。 */
    override val alerts: TurnAlerts by lazy { TurnAlerts(hub) }

    override fun attachConversation(conversation: Conversation): Any =
        ConversationFocus(hub, conversation)

    /** 界面是否在前台；不在前台时一轮结束或需要批准会发通知。按实例记：界面重建时新旧实例的 onStart/onStop 会交错。 */
    private val startedActivities = HashSet<Activity>()

    override fun started() {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities.add(activity)
                    hub.foreground = true
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities.remove(activity)
                    hub.foreground = startedActivities.isNotEmpty()
                }

                override fun onActivityDestroyed(activity: Activity) {
                    startedActivities.remove(activity)
                    hub.foreground = startedActivities.isNotEmpty()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                    Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) =
                    Unit
            },
        )
        ScheduleRunner.onAppStart(app)
        AccessibilityGuard.start(app)
        watchNetwork()
        bridge.start()
    }

    /**
     * 每个会话的手机工具，按 sessionId（新对话还没有时按 key）登记。后台屏幕比 Claude Code 进程活得久：
     * 进程结束、重新打开会话时新建的对话对象从这里拿回原来那份，屏上的应用还是原来的样子。 主线程和工具线程（[makeRoom]）都会访问，用它自己当锁；持锁时不调用会去拿
     * PhoneTools 锁的东西。
     */
    private val phones = HashMap<String, PhoneTools>()

    private val bridge by lazy {
        PhoneBridge(
            PhoneBridge.socketName(app.packageName),
            ::openBridged,
            ::onBridgeClosed,
            ::onBridgeCall,
        )
    }

    /**
     * Zeta 之外经 root 连进来用手机工具的客户端（工作站上的 Claude Code），按客户端标识各一个，带着它那一份手机工具（[BridgeFocus.phone]）。
     * 手机工具不进 [phones]：一次调用连一次，调用之间没有连接也要留着（控件编号、后台屏幕上的应用），不跟着对话收掉。它们的后台屏幕和对话的 一起算 [MAX_SCREENS]
     * 的上限、一起被腾（[makeRoom]），连接断开后照样闲置休眠（[onBridgeClosed]）。胶囊和对话一起从这里挑。 可观察，只在主线程上加；工具线程上的 [makeRoom]
     * 只读。
     */
    internal val bridgeFoci = mutableStateListOf<BridgeFocus>()

    /** [PhoneBridge] 的客户端 [key] 连上了：给它那一份手机工具，[busy] 时（有调用在处理）后台屏幕不休眠、不被腾掉。在主线程上调用。 */
    internal fun openBridged(key: String, busy: () -> Boolean): PhoneTools {
        val focus =
            bridgeFoci.firstOrNull { it.key == key }
                ?: BridgeFocus(
                        key,
                        PhoneTools(
                            app,
                            settings,
                            // Zeta 之外的客户端都在手机本机上
                            Backends.LOCAL,
                            beforeCreate = ::makeRoom,
                            isBusy = { busy() },
                            onChanged = hub::onActivityChanged,
                        ),
                    )
                    .also { bridgeFoci += it }
        hub.onActivityChanged()
        return focus.phone
    }

    /** [PhoneBridge] 的客户端 [key] 的一个工具调用开始（[started]）或结束了：胶囊据此显示、停留、收起。在主线程上调用。 */
    internal fun onBridgeCall(key: String, started: Boolean) {
        val focus = bridgeFoci.firstOrNull { it.key == key } ?: return
        if (started) focus.callStarted() else focus.callEnded()
    }

    /**
     * [PhoneBridge] 的客户端 [key] 断开了一条连接：和对话的一轮结束一样，后台屏幕闲置满时间就休眠（这期间又有调用就不睡）。
     * 只碰后台屏幕，不复位手机工具的暂停与接管：同一个客户端可能还有别的连接在调用。在主线程上调用。
     */
    private fun onBridgeClosed(key: String) {
        bridgeFoci.firstOrNull { it.key == key }?.phone?.slot?.onTurnEnded()
        hub.onActivityChanged()
    }

    /**
     * 手机换了网络（Wi-Fi 和移动数据之间）：远程主机的旧连接多半已经死了，两种连法都马上断开（直连的在 Zeta 里， 经 Debian 的关掉 Debian 里 ssh
     * 的主连接），上面的对话随即重连，不等保活包超时（约 45 秒）。 重新有网时，等着重连的对话也马上去连。
     */
    private fun watchNetwork() {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    private var last: Network? = null

                    override fun onAvailable(network: Network) {
                        val previous = last
                        last = network
                        if (previous != null && previous != network) {
                            hub.ssh.dropAll()
                            dropDebianLinks()
                        }
                        // 等着重连的对话不等满退避的时间
                        NetworkWake.signal()
                    }
                },
            )
        }
    }

    /** 关掉手机 Debian 里到各台经 Debian 连的主机的 ssh 主连接（[DebianSshShell.closeMasters]），在后台线程上做。 */
    private fun dropDebianLinks() {
        val targets =
            settings.remoteHosts.filter { it.via == RemoteHost.Via.Debian }.map { it.target }
        if (targets.isEmpty() || !hub.usable(Backends.LOCAL)) return
        val local = hub.shell(Backends.LOCAL) ?: return
        thread(name = "zeta-ssh-drop", isDaemon = true) {
            runCatching { local.run(DebianSshShell.closeMasters(targets), timeoutMs = 15_000) }
        }
    }

    override fun phoneFor(key: String, backend: String): PhoneTools =
        synchronized(phones) {
            phones.getOrPut(key) {
                PhoneTools(
                    app,
                    settings,
                    backend,
                    beforeCreate = ::makeRoom,
                    onScreenGone = { prunePhones() },
                    isBusy = ::phoneBusy,
                    onChanged = hub::onActivityChanged,
                )
            }
        }

    /**
     * 用手机工具 [phone] 的对话正在进行（后台任务在跑也算）。按 hub 手上的对话查，不让手机工具拿着对话：
     * 对话收掉后有后台屏幕的手机工具还被登记着，拿着对话就连它的聊天内容、图片一起留住；收掉的对话也自然算不忙。 读的都是快照状态，任何线程上都能调用。
     */
    private fun phoneBusy(phone: PhoneTools): Boolean =
        hub.liveConversations.any {
            it.phoneSession === phone && (it.busy || it.backgroundTasks > 0)
        }

    /** 对话的会话号变了：登记跟着换。新号下面要是还挂着别的一份（不该有），它已经没人找得到，收掉。 */
    override fun rekeyPhone(phone: PhoneSession, from: String, to: String) {
        val stale =
            synchronized(phones) {
                // 已经不在登记里（没人用被丢掉、销毁了）就别再登记回去，否则以后打开这个会话会拿到一份建不了屏的
                if (phones[from] !== phone) return
                phones.remove(from)
                phones.put(to, phone as PhoneTools)?.takeIf { it !== phone }
            }
        stale?.destroy()
    }

    /** 丢掉没有后台屏幕、也没有对话在用的那些（和要删的会话 [ids] 的），免得登记越积越多。在主线程上调用。 */
    override fun prunePhones(ids: Set<String>) {
        val inUse = hub.liveConversations.mapTo(HashSet()) { it.phoneSession }
        val unused =
            synchronized(phones) {
                ids.mapNotNull { phones.remove(it) } +
                    phones.values
                        .filter { it !in inUse && it.slot.alive == null }
                        .also { phones.values.removeAll(it.toSet()) }
            }
        // 销毁而不只是丢开：还没结束的工具调用就不会再给它建出一块没人管的屏
        unused.forEach { it.destroy() }
    }

    /** 有手机工具调用正在处理（对话的，或 Zeta 之外的客户端的）：这时结束本应用，那次调用会失败。在主线程上调用。 */
    internal fun phoneCallRunning(): Boolean =
        synchronized(phones) { phones.values.any { it.inCall } } ||
            bridgeFoci.any { it.phone.inCall }

    override fun dropPhones(kept: Set<PhoneSession>) {
        synchronized(phones) {
                phones.values.filter { it !in kept }.also { phones.values.removeAll(it.toSet()) }
            }
            .forEach { it.destroy() }
    }

    /**
     * [phone] 要新建一块后台屏幕（工具线程上）。全应用已有 [MAX_SCREENS] 块时（对话的和外部客户端的一起算）先腾出来：
     * 只挑对话没在进行、外部客户端没有调用在处理的，用户正看着的当前对话放最后，其余最久没用的先收；都在忙就照样建。 收屏另起线程做，这里不会去拿别的 PhoneTools
     * 的锁，两个对话同时建屏也不会互相卡住。
     */
    private fun makeRoom(phone: PhoneTools) {
        val all = synchronized(phones) { phones.values.toList() } + bridgeFoci.map { it.phone }
        val others = all.filter { it !== phone && it.slot.alive != null }
        val excess = others.size - MAX_SCREENS + 1
        if (excess <= 0) return
        val watching = hub.current.phoneSession
        others
            .filter { !it.slot.busy() }
            .sortedWith(compareBy<PhoneTools> { it === watching }.thenBy { it.slot.lastUse })
            .take(excess)
            .forEach { it.slot.releaseAsync() }
    }

    /** 有进程活着就保持前台服务，否则系统会在切到后台后很快回收本应用，连带结束 Claude Code。 */
    override fun activityChanged() {
        val working = environment.working
        val conversations = hub.liveConversations
        // 外部客户端连着时也要前台服务：不然进程退到后台被冻结，连接那头的调用就没人答
        val active = working || conversations.any { it.isProcessAlive } || bridge.connected > 0
        // 后台任务跑着也要持有唤醒锁，否则息屏休眠时任务会被挂起；和远程主机断了连接、正在重连的不算（那边照样在跑）
        val busy = working || conversations.any { it.active && !it.offline }
        val answering = conversations.filter { it.busy }
        val running = answering.map { conversation ->
            conversation.title.ifBlank { "新对话" } +
                (conversation.phone.agentDisplayApp?.let { " · 在后台操作$it" } ?: "")
        }
        AgentService.update(app, active, busy, running, alerts.liveStatus(answering))
        alerts.syncAll()
    }

    override fun unattendedName(conversation: Conversation): String? =
        ScheduleRunner.taskName(conversation)

    override fun sharedFile(uri: String): PickedFile = UriFile(app, Uri.parse(uri))

    override fun hostsRemoved(ids: Set<String>) {
        ScheduleStore.update(app) { list -> list.filter { it.backend in ids } }
        NotifyRuleStore.update(app) { list -> list.filter { it.backend in ids } }
    }

    companion object {
        private const val TAG = "Zeta"

        /** 全应用最多同时留几块后台屏幕：每块都连着一个 root 辅助进程和屏上开着的应用，太多了内存吃不消。 */
        private const val MAX_SCREENS = 3

        /** Zeta 直连用的公钥那一行（authorized_keys 的写法），在应用私有目录 files/ 下；密钥第一次用时写出。 */
        const val PUBLIC_KEY_FILE = "zeta_ssh.pub"

        /** 本进程的那个 hub，登记在 [ProcessHub]（界面的 LocalHub 也从那里取）。 */
        fun hub(app: Application): ConversationHub =
            ProcessHub.current
                ?: synchronized(this) {
                    ProcessHub.current ?: ConversationHub(app).also { ProcessHub.current = it }
                }

        /** 换掉全应用共用的 hub（单元测试装上自己建的；每个用例开始时由测试用的 Application 清空）。 */
        internal fun install(hub: ConversationHub) {
            ProcessHub.current = hub
        }
    }
}

/** 手机上的 hub：平台是 [AndroidHubPlatform]（[sshKey] 给测试换成内存里的密钥）。 */
fun ConversationHub(
    app: Application,
    sshKey: (() -> SshKey)? = null,
): ConversationHub =
    ConversationHub(
        if (sshKey == null) AndroidHubPlatform(app) else AndroidHubPlatform(app, sshKey),
    )

/** 本进程的那个 hub。 */
fun ConversationHub.Companion.get(app: Application): ConversationHub = AndroidHubPlatform.hub(app)

/** Zeta 直连用的公钥那一行写在应用私有目录 files/ 下的这个文件里。 */
val ConversationHub.Companion.PUBLIC_KEY_FILE: String
    get() = AndroidHubPlatform.PUBLIC_KEY_FILE

/** 手机上 hub 的平台。 */
val ConversationHub.android: AndroidHubPlatform
    get() = platform as AndroidHubPlatform

val ConversationHub.context: Application
    get() = android.app

/** Linux 环境：检查、安装、维护、SSH、Claude 账号的登录状态。 */
val ConversationHub.environment: EnvController
    get() = android.environment

/** 胶囊、结果卡片、完成与确认提醒、实时通知显示哪个对话。 */
val ConversationHub.alerts: TurnAlerts
    get() = android.alerts

/** Zeta 之外经 root 连进来用手机工具的客户端（[AndroidHubPlatform.bridgeFoci]）。 */
internal val ConversationHub.bridgeFoci: List<BridgeFocus>
    get() = android.bridgeFoci

/** 这个对话的手机工具：手机上每个对话都有。 */
val Conversation.phone: PhoneTools
    get() = phoneSession as PhoneTools

/** 顶部状态胶囊看到的这个对话。 */
internal val Conversation.overlay: ConversationFocus
    get() = platformState as ConversationFocus

/** 主机 [backend] 上会话 [key] 的手机工具（[AndroidHubPlatform.phoneFor]）。 */
internal fun ConversationHub.phoneFor(key: String, backend: String): PhoneTools =
    android.phoneFor(key, backend)

/** 外部客户端 [key] 连上了（[AndroidHubPlatform.openBridged]）。 */
internal fun ConversationHub.openBridged(key: String, busy: () -> Boolean): PhoneTools =
    android.openBridged(key, busy)

/** 外部客户端 [key] 的一个工具调用开始或结束了（[AndroidHubPlatform.onBridgeCall]）。 */
internal fun ConversationHub.onBridgeCall(key: String, started: Boolean) =
    android.onBridgeCall(key, started)

/** 有手机工具调用正在处理（[AndroidHubPlatform.phoneCallRunning]）。 */
internal fun ConversationHub.phoneCallRunning(): Boolean = android.phoneCallRunning()
