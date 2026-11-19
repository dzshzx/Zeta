package com.dzshzx.zeta.xposed

import android.os.Bundle
import com.dzshzx.zeta.core.AppVariant
import java.lang.reflect.Method

/** 一次电源键快捷动作该怎么走。 */
enum class PowerKeyRoute(val label: String) {
    /** 不是电源键唤起助理（别的按键、别的动作）：原样放行，不记日志。 */
    NotOurs("不是电源键唤起助理"),

    /** 同一次按键刚显示过 Zeta 的会话，又派发来一次（imperceptible 和 long_press 两路）：吞掉。 */
    Duplicate("同一次按键的重复派发，已吞掉"),

    /** 默认数字助理不是 Zeta：走原逻辑（小爱）。 */
    NotZeta("默认助理不是 Zeta，走原逻辑"),

    /** 锁屏中：走原逻辑，Zeta 不在锁屏上弹出对话。 */
    Locked("锁屏中，走原逻辑"),

    /** 显示当前默认助理（Zeta）的会话，成功才消费这次动作。 */
    Show("显示 Zeta 的助理会话"),
}

/**
 * HyperOS 长按电源键交给 Zeta 的判断（LSPosed 模块在 system_server 里用，见 [PowerKeyHook]）。
 * 只管 `ShortCutActionsUtils.triggerFunction` 里功能是 launch_voice_assistant、来源是电源键的那几次；纯函数，单元测试覆盖。
 */
object PowerKeyPolicy {
    /** 模块日志（LSPosed 模块日志和 logcat）的标签；Zeta 的诊断页按它找记录。 */
    const val LOG_TAG = "ZetaPowerKey"

    const val FUNCTION = "launch_voice_assistant"

    /** 长按电源键，以及 HyperOS 按下到一定时长时先派发的那一路（`postPowerLongPress`）。 */
    val SOURCES = setOf("long_press_power_key", "imperceptible_press_power_key")

    /** 同一次按键的几路派发间隔多久以内算重复。 */
    const val DEDUP_WINDOW_MS = 1_000L

    fun matches(function: String?, source: String?): Boolean = function == FUNCTION && source in SOURCES

    /**
     * secure 设置 voice_interaction_service 里的组件（“包名/类名”，类名可以是 .开头的短写）属于 Zeta 的哪个包；
     * 不是 Zeta（正式版或测试版）返回 null。
     */
    fun zetaPackage(component: String?): String? {
        val pkg = component?.trim()?.substringBefore('/', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() } ?: return null
        return pkg.takeIf { AppVariant.isZetaPackage(it) }
    }

    fun route(function: String?, source: String?, assistant: String?, locked: Boolean, duplicate: Boolean): PowerKeyRoute = when {
        !matches(function, source) -> PowerKeyRoute.NotOurs
        zetaPackage(assistant) == null -> PowerKeyRoute.NotZeta
        duplicate -> PowerKeyRoute.Duplicate
        locked -> PowerKeyRoute.Locked
        else -> PowerKeyRoute.Show
    }
}

/** 记下上一次成功显示会话的时刻，[PowerKeyPolicy.DEDUP_WINDOW_MS] 以内再来的算同一次按键。 */
class PowerKeyGate(private val windowMs: Long = PowerKeyPolicy.DEDUP_WINDOW_MS) {
    private var last: Long? = null

    @Synchronized
    fun duplicate(now: Long): Boolean = last?.let { now - it in 0..windowMs } == true

    @Synchronized
    fun shown(now: Long) {
        last = now
    }
}

/**
 * 要拦的 `triggerFunction` 重载。HyperOS 4 上四参数的那个直接转调五参数的（第五个参数是 "mesh_heavy"），
 * 所以有五参数的就只拦它，免得同一次按键进两次；没有才拦四参数的。返回类型必须是 boolean，对不上就不拦。
 */
object PowerKeySignature {
    const val CLASS = "com.miui.server.input.util.ShortCutActionsUtils"
    const val METHOD = "triggerFunction"

    private val four = listOf(String::class.java, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType)
    private val five = four + String::class.java

    fun pick(type: Class<*>): Method? {
        val candidates = type.declaredMethods.filter { it.name == METHOD && it.returnType == Boolean::class.javaPrimitiveType }
        return candidates.firstOrNull { it.parameterTypes.toList() == five } ?: candidates.firstOrNull { it.parameterTypes.toList() == four }
    }

    /** 日志里的写法：triggerFunction(String, String, Bundle, boolean, String)。 */
    fun describe(method: Method): String = method.name + method.parameterTypes.joinToString(", ", "(", ")") { it.simpleName }

    /** 这个类里有哪些 triggerFunction（签名对不上时写进日志，方便看 ROM 改成了什么）。 */
    fun available(type: Class<*>): String =
        type.declaredMethods.filter { it.name == METHOD }.joinToString("；") { it.returnType.simpleName + " " + describe(it) }.ifEmpty { "没有" }
}
