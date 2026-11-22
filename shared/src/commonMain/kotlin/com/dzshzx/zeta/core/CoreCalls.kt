package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 在主机 [backend] 上起 `zeta-helper serve` 的办法（给 [HelperServe] 的 open）：那台用不了时抛出原因， 装 zeta-helper
 * 时远程主机连不上之类给一句人话（[ConversationHub.linkProblem]）。
 */
fun ConversationHub.serveOpener(backend: String): () -> HelperServe.Opened = {
    val shell = shell(backend) ?: error(unavailable(backend) ?: "还没有可用的 Linux 环境")
    HelperServe.open(shell, helperProgram) { linkProblem(backend, it.exitCode, it.stderr) }
}

/** 到主机 [backend] 上核心的一条单用的连接：不订阅什么，那边推来的交给 [onMessage]（在连接自己的线程上）。用完 [HelperServe.close]。 */
fun ConversationHub.openCore(backend: String, onMessage: (JsonObject) -> Unit = {}): HelperServe =
    HelperServe(
        name = backend,
        open = serveOpener(backend),
        subscriptions = emptyList(),
        onMessage = onMessage,
        onProblem = {},
        onHello = helloFrom(backend),
    )

/**
 * 经主机 [backend] 上的核心调一次方法 [method]（docs/core.md「会话」「存储与文件」），[params] 和 id、method 平级。
 * 侧栏正订阅着那台的会话列表、连接好着时借那条连接（[SessionList.connection]）；没有时（界面在后台、那台不在侧栏里、 断着）起一条只为这次的连接，答完就关。 成了返回
 * result（没有时是 null）；没成抛 [ServeError]，原因给人看，核心给了代号时带 code。 最多等 [timeoutMs]。阻塞，别在主线程上调用。
 */
fun ConversationHub.callCore(
    backend: String,
    method: String,
    params: JsonObject,
    timeoutMs: Long = HelperServe.CALL_TIMEOUT_MS,
): JsonElement? {
    sessionList.connection(backend)?.let {
        return it.call(method, params, timeoutMs)
    }
    val serve = openCore(backend)
    try {
        return serve.call(method, params, timeoutMs)
    } finally {
        serve.close()
    }
}

/** 一串字符串写成 JSON 数组（核心方法的参数用）。 */
fun jsonStrings(values: Iterable<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
