package com.dzshzx.zeta.shots

import android.app.Application
import androidx.compose.runtime.MutableState
import com.dzshzx.zeta.core.AccountStatus
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LinuxEnvConfig
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.SetupState

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

    /** 一个环境已就绪、账号已登录的 hub，并装成全应用共用的那个（[ConversationHub.get] 拿到的就是它）。 */
    fun readyHub(app: Application): ConversationHub {
        Settings(app).env = env
        val hub = ConversationHub(app)
        install(hub)
        hub.force("setup", SetupState.Ready)
        hub.force("account", AccountStatus(loggedIn = true, email = "shots@example.invalid", plan = null))
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
     * 改 `var x by mutableStateOf(...)` 且 setter 是 private 的属性：直接写它背后的 MutableState。
     * 属性改名后这里会报找不到字段，照新名字改测试即可。
     */
    fun Any.force(property: String, value: Any?) {
        val field = generateSequence<Class<*>>(javaClass) { it.superclass }
            .firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == "$property\$delegate" } }
            ?: error("${javaClass.simpleName} 没有用 mutableStateOf 委托的属性 $property")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(this) as MutableState<Any?>).value = value
    }
}
