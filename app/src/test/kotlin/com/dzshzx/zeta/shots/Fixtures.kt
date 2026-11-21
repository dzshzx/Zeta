package com.dzshzx.zeta.shots

import android.app.Application
import androidx.compose.runtime.MutableState
import com.dzshzx.zeta.core.AccountStatus
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.LinuxEnvConfig
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.RemoteHost
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.ssh.SshKey

/**
 * 截图用的假数据。界面读的是真的 [ConversationHub]：先把设置写成“环境已装好”的样子再建 hub，
 * 只有 root 才查得到的状态（环境就绪、账号已登录）和对话内容直接摆进去，不起 Claude 进程。
 *
 * 单元测试的 PATH 里有一个总是失败的 `su`（app/src/test/no-root），所以界面里要经 root 查的东西
 * （共享文件夹的子目录、权限健康里的 root 项）在图上显示成查不到，不会碰到开发机自己的 su。
 */
object Fixtures {
    val env = LinuxEnvConfig(
        rootfs = "/data/local/zeta/debian/rootfs",
        user = "dzshzx",
        uid = 5000,
        gid = 5000,
        home = "/home/dzshzx",
        defaultCwd = "/workspace/claude",
    )

    /** 一台经手机 Debian 连的远程主机。 */
    val workstation = RemoteHost("r-00000001", "工作站", "dev-box", "/home/user", "/home/user/workspace")

    /** Claude Code 在 initialize 里报的模型清单的样子：别名和它指向的完整 ID，每个都能选全部几档思考强度。 */
    val models: List<ModelOption> by lazy {
        val all = EffortLevels.map { it.first }
        listOf(
            ModelOption("default", "Default (recommended)", "", "claude-opus-5-5", all),
            ModelOption("opus", "Opus", "", "claude-opus-5-5", all),
            ModelOption("fable", "Fable", "", "claude-fable-5-2", all),
            ModelOption("sonnet", "Sonnet", "", "claude-sonnet-5-5", all),
            ModelOption("haiku", "Haiku", "", "claude-haiku-5-5", all),
        )
    }

    /** Zeta 直连用的密钥：单元测试里没有 Android Keystore，换成内存里的。 */
    val sshKey: SshKey by lazy { SshKey.generate("zeta@shots") }

    /** 系统设置里的设备名（Robolectric 里没有，图上手机就叫型号「robolectric」）：写成真机上的样子。 */
    private fun nameThePhone(app: Application) {
        android.provider.Settings.Global.putString(app.contentResolver, android.provider.Settings.Global.DEVICE_NAME, "Xiaomi 17")
    }

    /** 一个环境已就绪、账号已登录的 hub，并装成全应用共用的那个（[ConversationHub.get] 拿到的就是它）。 */
    fun readyHub(app: Application): ConversationHub {
        nameThePhone(app)
        Settings.of(app).env = env
        val hub = ConversationHub(app) { sshKey }
        install(hub)
        hub.environment.setup = SetupState.Ready
        hub.environment.account = AccountStatus(loggedIn = true, email = "shots@example.invalid", plan = null)
        return hub
    }

    /** 手机上没有 Debian 环境的 hub（[state] 是检查的结果），装成全应用共用的那个。 */
    fun bareHub(app: Application, state: SetupState = SetupState.NoEnvironment): ConversationHub {
        nameThePhone(app)
        Settings.of(app).env = null
        val hub = ConversationHub(app) { sshKey }
        install(hub)
        hub.environment.setup = state
        return hub
    }

    /** 换掉全应用共用的 hub；每个测试开始时清空，免得拿到上一个测试（上一个 Application）留下的那个。 */
    fun install(hub: ConversationHub?) {
        ConversationHub::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, hub)
    }

    /** 把当前对话换成这些聊天内容（标题 [title]）。 */
    fun ConversationHub.showChat(title: String, items: List<ChatItem>) {
        current.title = title
        current.force("items", items)
    }

    /**
     * 改 setter 是 private 的属性：`var x by mutableStateOf(...)` 写它背后的 MutableState，普通字段直接写。
     * 属性改名后这里会报找不到字段，照新名字改测试即可。
     */
    fun Any.force(property: String, value: Any?) {
        val types = generateSequence<Class<*>>(javaClass) { it.superclass }
        types.firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == "$property\$delegate" } }?.let { field ->
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(this) as MutableState<Any?>).value = value
            return
        }
        val field = types.firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == property } }
            ?: error("${javaClass.simpleName} 没有属性 $property")
        field.isAccessible = true
        field.set(this, value)
    }
}
