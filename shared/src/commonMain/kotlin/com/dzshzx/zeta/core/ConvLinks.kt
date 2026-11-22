package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.conv.ConvClient
import com.dzshzx.zeta.core.conv.ConvListener
import com.dzshzx.zeta.core.conv.ConvOptions
import com.dzshzx.zeta.core.conv.ConvSpec
import com.dzshzx.zeta.core.conv.ConvSubagent
import com.dzshzx.zeta.core.conv.ConvTurn
import com.dzshzx.zeta.core.conv.CoreConversation
import com.dzshzx.zeta.core.conv.HostCpu
import kotlinx.serialization.json.JsonObject

/** 对话用的一条 serve 连接：发请求，不用了关掉。手机和桌面是 [HelperServe]，测试里换成假的。 */
interface ConvConnection : ServeRequests {
    fun close()
}

/**
 * 各台主机上对话用的 serve 连接（docs/core.md「对话」）：每台主机一条，上面一个 [ConvClient]，打开着的 [Conversation] 都挂在它上面。
 * 有对话要打开时连上，最后一个对话放开后再等 [IDLE_CLOSE_MS] 断开。核心推来的经 [Conversation] 的 core… 方法交给对应的对话
 * （在连接的读线程上调，对话自己切到主线程）。连接断了由 [HelperServe] 重连，[ConvClient] 照原名重新打开各对话。
 *
 * 和会话列表的订阅（[SessionList]）分开：那条只在界面在前台时连着，这条在对话开着时一直连着（后台跑着的对话也要收推送）。
 */
class ConvLinks(private val hub: ConversationHub) {
    /** 连一台主机：[onMessage] 收推来的每一行，[onProblem] 连上时收 null、断开时收原因（同 [HelperServe]）。测试里换掉。 */
    var connect:
        (
            backend: String,
            onMessage: (JsonObject) -> Unit,
            onProblem: (String?) -> Unit,
        ) -> ConvConnection =
        ::serve

    private val lock = Any()
    private val links = HashMap<String, Link>()
    private val main = MainHandler()

    /** 主机 [backend] 的连接断着时的原因（正在重连）；连着、没有连接时为 null。 */
    fun problem(backend: String): String? = synchronized(lock) { links[backend]?.problem }

    /**
     * 问一次主机 [backend] 整机的 CPU 使用率（输入栏上的负载标识）：借这台主机对话用的连接，没有就连上，用完照常空闲 [IDLE_CLOSE_MS]
     * 后断开（负载标识只在对话里有了消息以后才问，那时这条连接本来就开着）。问不成时为 null。阻塞，别在主线程上调。
     */
    fun hostCpu(backend: String): HostCpu? {
        val link = acquire(backend)
        return try {
            runCatching { link.client.hostCpu() }.getOrNull()
        } finally {
            release(link)
        }
    }

    /** 为 [owner] 在它的主机上打开一个对话（conv.open），阻塞到核心答了。打不开时抛 [ServeError]。别在主线程上调。 */
    fun open(owner: Conversation, spec: ConvSpec): CoreConversation {
        val link = acquire(owner.backend)
        return try {
            link.client.register(spec).also { link.bind(it, owner) }.also { it.openFirst() }
        } catch (e: Exception) {
            link.dropUnbound()
            release(link)
            throw e
        }
    }

    /** 从 [source] 的条目 [last] 所在那一轮之后分支（conv.fork），新对话交给 [owner]。阻塞，别在主线程上调。 */
    fun fork(
        owner: Conversation,
        source: CoreConversation,
        last: String,
        options: ConvOptions,
    ): CoreConversation {
        val link = acquire(owner.backend)
        return try {
            source.fork(last, options) { link.bind(it, owner) }
        } catch (e: Exception) {
            link.dropUnbound()
            release(link)
            throw e
        }
    }

    /** 放开对话 [core]（[end] 时先结束进程，conv.end）。阻塞，别在主线程上调。 */
    fun close(core: CoreConversation, end: Boolean) {
        val link = synchronized(lock) { links.values.firstOrNull { it.owns(core) } }
        if (end) runCatching { core.end() }
        runCatching { core.close() }
        link?.let {
            it.unbind(core)
            release(it)
        }
    }

    /** 主机 [backend] 的连法变了：马上断开它的连接（上面的对话已经放开），下次按新的连法连。 */
    fun reset(backend: String) {
        val link = synchronized(lock) { links.remove(backend) } ?: return
        link.connection.close()
    }

    private fun acquire(backend: String): Link =
        synchronized(lock) {
            val link =
                links[backend]?.takeUnless { it.dead } ?: Link(backend).also { links[backend] = it }
            link.users++
            main.removeCallbacks(link.closer)
            link
        }

    private fun release(link: Link) {
        synchronized(lock) {
            link.users--
            if (link.users > 0) return
        }
        main.postDelayed(link.closer, IDLE_CLOSE_MS)
    }

    private fun serve(
        backend: String,
        onMessage: (JsonObject) -> Unit,
        onProblem: (String?) -> Unit,
    ): ConvConnection {
        val serve =
            HelperServe(
                name = "conv-$backend",
                open = {
                    val shell =
                        hub.shell(backend) ?: error(hub.unavailable(backend) ?: "还没有可用的 Linux 环境")
                    HelperServe.open(shell, hub.helperProgram) {
                        hub.linkProblem(backend, it.exitCode, it.stderr)
                    }
                },
                subscriptions = emptyList(),
                onMessage = onMessage,
                onProblem = onProblem,
            )
        return object : ConvConnection, ServeRequests by serve {
            override fun close() = serve.close()
        }
    }

    /** 一台主机的连接和挂在上面的对话。 */
    private inner class Link(val backend: String) : ConvListener {
        var users = 0

        /** 第一次就没连上：[HelperServe] 不再重连，下次要用时另建一条。 */
        @Volatile var dead = false
        @Volatile var problem: String? = null
        private var everUp = false

        /** 核心那边的对话 → 这边的对话。拿着 [lock] 改。 */
        private val owners = HashMap<CoreConversation, Conversation>()

        val closer = Runnable {
            val idle =
                synchronized(lock) {
                    (users == 0).also { if (it && links[backend] === this) links.remove(backend) }
                }
            if (idle) connection.close()
        }

        val client =
            ConvClient(
                object : ServeRequests {
                    override fun call(method: String, params: JsonObject) =
                        connection.call(method, params)

                    override fun call(method: String, params: JsonObject, timeoutMs: Long) =
                        connection.call(method, params, timeoutMs)
                },
                this,
            )
        val connection: ConvConnection =
            connect(backend, { client.handle(it) }) { why -> linkChanged(why) }

        private fun linkChanged(why: String?) {
            if (why == null) {
                everUp = true
                problem = null
                client.connected()
                return
            }
            if (!everUp) dead = true
            problem = why
            client.disconnected()
        }

        fun bind(core: CoreConversation, owner: Conversation) {
            synchronized(lock) { owners[core] = owner }
        }

        fun unbind(core: CoreConversation) {
            synchronized(lock) { owners.remove(core) }
        }

        fun owns(core: CoreConversation): Boolean = synchronized(lock) { core in owners }

        /** 打开没成：登记了、却没打开的去掉。 */
        fun dropUnbound() {
            synchronized(lock) {
                val open = client.conversations.toSet()
                owners.keys.retainAll(open)
            }
        }

        private fun owner(conv: CoreConversation): Conversation? =
            synchronized(lock) { owners[conv] }

        override fun changed(conv: CoreConversation) {
            owner(conv)?.coreChanged(conv)
        }

        override fun turn(conv: CoreConversation, turn: ConvTurn) {
            owner(conv)?.coreTurn(conv, turn)
        }

        override fun phone(conv: CoreConversation, message: JsonObject) {
            owner(conv)?.corePhone(conv, message)
        }

        override fun subagent(conv: CoreConversation, subagent: ConvSubagent) {
            owner(conv)?.coreSubagent(subagent)
        }

        override fun taskOutput(conv: CoreConversation, task: String, output: TaskOutput) {
            owner(conv)?.coreTaskOutput(task, output)
        }
    }

    companion object {
        /** 最后一个对话放开后连接再留多久：切对话、闲置收掉又马上打开时不用重连。 */
        const val IDLE_CLOSE_MS = 60_000L
    }
}
