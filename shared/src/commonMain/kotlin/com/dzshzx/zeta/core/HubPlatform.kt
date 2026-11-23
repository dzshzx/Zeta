package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.ssh.SshLinks
import java.io.File
import kotlinx.serialization.json.JsonObject

/**
 * [ConversationHub] 要平台做的事。实现是 app 的 AndroidHubPlatform：自带的 Debian、手机工具、通知和顶部胶囊。 hub 建好后先调
 * [attach]，之后各项按需调用，都在主线程上，除非另外写明。
 */
interface HubPlatform {
    /** hub 建出来了（它的各项还没初始化，只记下它，别调用它）。 */
    fun attach(hub: ConversationHub)

    /** hub 的各项都初始化完了：手机上这时才开始监听网络和界面前后台、接上外部入口、排定时任务。 */
    fun started()

    /** 随包的资源文件（zeta-helper、claude-switch、zeta_accounts.sh 等），按名字读出全部字节。 */
    fun asset(name: String): ByteArray

    /** Zeta 自己的设置（全进程一份，界面也读写同一份）。 */
    val settings: Settings

    /** 名为 [name] 的设置文件（手机上是 shared_prefs/<name>.xml）。 */
    fun prefs(name: String): PrefStore

    /** 应用自己的私有目录：输入草稿里的图片、诊断日志放这里。 */
    val filesDir: File

    /** 这台机器自己的名字（手机是系统设置里的设备名），手机本机那台的显示名用它。 */
    val deviceName: String

    /**
     * Zeta 直连远程主机的 SSH（[SshLinks]），一个进程一份：随 APK 带的 Go 程序 zeta-ssh，用 Zeta 自己的密钥（第一次要时才进 Keystore 取）。
     */
    val sshLinks: SshLinks

    /** 追加给 Claude Code 的一句说明：对面是什么界面、怎样把图片给用户看。 */
    val clientHint: String

    /** 弹一句不用回应的提示（手机上是 Toast）。 */
    fun toast(text: String)

    /** 手机本机这台的 Linux 环境（Debian）。 */
    val local: LocalEnvironment?

    /** 一轮的进展怎么提醒用户（完成、等确认、胶囊、实时通知）。 */
    val alerts: TurnEvents

    /** 主机 [backend] 上会话 [key] 的手机工具：登记过就接着用原来那份；没有时是 null。 */
    fun phoneFor(key: String, backend: String): PhoneSession?

    /** 对话的会话号从 [from] 换成了 [to]：按会话登记的手机工具跟着换键。 */
    fun rekeyPhone(phone: PhoneSession, from: String, to: String)

    /** hub 手上的对话变了（换、收、删）：没人用的手机工具收掉。[ids] 是要删的会话，它们的一并收掉。 */
    fun prunePhones(ids: Set<String> = emptySet())

    /** 要靠手机 Debian 的对话都收掉了（换环境）：只留 [kept] 里的手机工具，别的一并收掉。 */
    fun dropPhones(kept: Set<PhoneSession>)

    /** 新建了对话 [conversation]（它还没初始化完，只记下它）：返回给它附的东西，存在 [Conversation.platformState]。 */
    fun attachConversation(conversation: Conversation): Any?

    /** 对话的活动变了（起进程、一轮开始结束、等确认）：前台服务、常驻通知、胶囊跟着更新。 */
    fun activityChanged()

    /** 外面送进来的一个文件地址 [uri]（分享来的 content://）。 */
    fun sharedFile(uri: String): PickedFile

    /** 常用指令（聊天页输入框左边的“+”里）。 */
    val prompts: PromptList

    /** 起了对话 [conversation]、还在跑的定时任务或通知触发规则叫什么；不是后台运行起的为 null。 */
    fun unattendedName(conversation: Conversation): String?

    /** 远程主机清单变了：已经删掉的主机（不在 [ids] 里）名下的定时任务和通知触发规则一起删。 */
    fun hostsRemoved(ids: Set<String>)
}

/** 手机本机那台的 Linux 环境（Debian）的状态，hub 据此判断要靠它的主机能不能用。 */
interface LocalEnvironment {
    /** 检查完了、就绪（可观察）。 */
    val ready: Boolean

    /** 正在装、换或维护环境（这时要常驻前台服务）。 */
    val working: Boolean

    /** 没在装、换环境，能动手机本机的登录状态。 */
    val canSwitchEnvironment: Boolean

    /** 查过登录状态、手机上的 Claude Code 没登录（聊天页的空对话里给登录入口）。 */
    val loggedOut: Boolean

    /** 正在登录 Claude 账号（登录流程开始了、还没完成或取消）。 */
    val loggingIn: Boolean

    /** 还没检查或没就绪时去检查一次。 */
    fun checkSetup()

    /** 账号页切换后知道了登录着的是 [email]（订阅 [plan]）：聊天页的登录提示、设备页的账号入口跟着变。 */
    fun showAccount(email: String, plan: String)

    /** 账号页把在用的号存档并退出了（登录另一个号之前）：知道现在没登录，不必再起 Claude Code 去问。 */
    fun showLoggedOut()

    /** 重新查一次登录着的账号。 */
    fun refreshAccount()
}

/** 一轮的进展交给谁提醒（手机上是 TurnAlerts：胶囊、结果卡片、完成与确认提醒、实时通知）。 */
interface TurnEvents {
    /** 界面上的当前对话或前后台变了：正看着的那个的提醒不必留着。 */
    fun watchChanged()

    /** [conversation] 这一轮就要结束，[text] 是停留的那一句（“已完成”）。 */
    fun turnEnding(conversation: Conversation, text: String)

    /** [conversation] 这一轮结束了：[stopped] 是用户叫停的，[text] 是结束的那一句。 */
    fun turnFinished(conversation: Conversation, stopped: Boolean, text: String = "已完成")

    /** [conversation] 等用户回答的请求变了。 */
    fun pendingChanged(conversation: Conversation)
}

/** 对话正被一次后台运行（定时任务、通知触发）跑着的标记，见 [Conversation.unattended]。 */
interface Unattended {
    /** 这次运行不出声：完成和确认提醒都不响。 */
    val quiet: Boolean
}

/** 一个对话的手机工具（SDK 类型的 MCP 服务器 phone，消息走控制通道）。 */
interface PhoneSession {
    /** 起进程时交给 --mcp-config 的配置。 */
    val mcpConfig: String

    /** 对话正被后台运行跑着时挂上（工具调用据此认出后台运行）。 */
    var unattended: Unattended?

    /** 后台屏幕上正开着的应用的名字；没有是 null。 */
    val agentDisplayApp: String?

    /** 应答一条 MCP 消息（在工具线程上调用）。 */
    fun handle(message: JsonObject): JsonObject

    /** 用户回了消息 [messageId]（或插话）：等着用户的工具调用不再等。 */
    fun onReply(messageId: String)

    /** 用户叫停了：等着的调用不再等。 */
    fun cancelWaiting()

    /** 这一轮结束了。 */
    fun onTurnEnded()

    /** 对话的进程没了：放开暂停、接管这些和进程连着的状态。 */
    fun detach()
}

/** [HubPlatform.clientHint] 的写法：两端只差 [app]（什么设备上的什么应用）和看远程图片的动作 [tap]。 */
fun clientHint(app: String, tap: String): String =
    "The user is reading this conversation in Zeta, $app. It renders Markdown. " +
        "To show the user an image file from this machine, include it in your reply as a Markdown image with an absolute path, " +
        "for example ![chart](/home/user/out/chart.png); the app displays it inline. Remote (http/https) Markdown images are shown too, after the user ${tap}s them. " +
        "Lines of the form [Attached file: /path] at the start of a user message are files the user attached; read them with your tools."
