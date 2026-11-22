package com.dzshzx.zeta.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.HubPlatform
import com.dzshzx.zeta.core.LocalEnvironment
import com.dzshzx.zeta.core.PhoneSession
import com.dzshzx.zeta.core.PickedFile
import com.dzshzx.zeta.core.PrefStore
import com.dzshzx.zeta.core.PromptList
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.TurnEvents
import com.dzshzx.zeta.core.clientHint
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.ssh.SshKey
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面上的 [HubPlatform]：只有远程主机（经 Zeta 自带的 SSH 直连），没有手机本机那台、手机工具和通知。 设置、密钥和诊断日志都放在 [filesDir]（Windows
 * 上是 %APPDATA%\Zeta）。
 */
class DesktopHubPlatform(override val filesDir: File = defaultDir()) : HubPlatform {
    override val settings: Settings by lazy { Settings.shared(this) { prefs(Settings.FILE) } }

    override fun attach(hub: ConversationHub) {
        DiagLog.init(File(filesDir, "diag/events.jsonl")) { level, text ->
            System.err.println("zeta $level $text")
        }
    }

    override fun started() = Unit

    /** 随包的资源（托管脚本、zeta-helper 等）在 jar 的 assets/ 下，和 APK 里的同名。 */
    override fun asset(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/assets/$name")) { "桌面版里没有 $name" }
            .use { it.readBytes() }

    private val stores = ConcurrentHashMap<String, PrefStore>()

    /** 同一个名字只开一份：几处各开一份就会各自缓存、互相盖掉。 */
    override fun prefs(name: String): PrefStore =
        stores.computeIfAbsent(name) { FilePrefStore(File(filesDir, "$it.json")) }

    override val prompts: PromptList by lazy { PrefsPromptList(prefs(Settings.FILE)) }

    override val deviceName: String by lazy {
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.ifBlank { null } ?: "这台电脑"
    }

    private val key: SshKey by lazy {
        DesktopKey.loadOrCreate(filesDir, "zeta-desktop@" + deviceName)
    }

    override fun sshKey(): SshKey = key

    override val clientHint: String = clientHint("a desktop chat app on their computer", "click")

    /** 界面上要弹的那一句提示（可观察），界面显示一会儿后清掉。 */
    var notice by mutableStateOf<String?>(null)

    override fun toast(text: String) {
        notice = text
    }

    override val local: LocalEnvironment? = null

    override val alerts: TurnEvents =
        object : TurnEvents {
            override fun watchChanged() = Unit

            override fun turnEnding(conversation: Conversation, text: String) = Unit

            override fun turnFinished(conversation: Conversation, stopped: Boolean, text: String) =
                Unit

            override fun pendingChanged(conversation: Conversation) = Unit
        }

    override fun phoneFor(key: String, backend: String): PhoneSession? = null

    override fun rekeyPhone(phone: PhoneSession, from: String, to: String) = Unit

    override fun prunePhones(ids: Set<String>) = Unit

    override fun dropPhones(kept: Set<PhoneSession>) = Unit

    override fun attachConversation(conversation: Conversation): Any? = null

    override fun activityChanged() = Unit

    override fun hostsRemoved(ids: Set<String>) = Unit

    override fun sharedFile(uri: String): PickedFile = DesktopFile(File(URI(uri)))

    override fun unattendedName(conversation: Conversation): String? = null

    companion object {
        /** Windows 上是 %APPDATA%\Zeta，别的系统上是 $XDG_CONFIG_HOME/zeta-desktop（默认 ~/.config）。 */
        fun defaultDir(): File {
            System.getenv("APPDATA")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    return File(it, "Zeta")
                }
            val config =
                System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                    ?: (System.getProperty("user.home") + "/.config")
            return File(config, "zeta-desktop")
        }
    }
}
