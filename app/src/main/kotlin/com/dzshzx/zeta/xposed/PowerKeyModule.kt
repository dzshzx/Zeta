package com.dzshzx.zeta.xposed

import android.app.KeyguardManager
import android.content.ContentResolver
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.dzshzx.zeta.assistant.ZetaVoiceInteractionSession
import com.dzshzx.zeta.core.AppVariant
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Zeta 自带的 LSPosed 模块（libxposed API 102，入口登记在 resources/META-INF/xposed/java_init.list），
 * 作用域只有 system（system_server）。做一件事：HyperOS 长按电源键时系统直接拉起小爱，不看默认数字助理是谁；
 * 默认助理是本包时改为显示它的助理会话，见 [PowerKeyHook]。正式版、测试版各带一个，在 LSPosed 里各自启用，
 * 互不干扰：每个只认自己，默认助理不是自己就放行。
 */
class PowerKeyModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (!param.isSystemServer) detach()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        PowerKeyHook.install(this, param.classLoader)
    }
}

/**
 * 拦 `ShortCutActionsUtils.triggerFunction`（签名见 [PowerKeySignature]）。只处理功能 launch_voice_assistant、
 * 来源是电源键的那几次（[PowerKeyPolicy]）：默认助理是本包、没锁屏时请系统显示当前默认助理的会话
 * （`IVoiceInteractionManagerService.showSessionForActiveService`，和 SystemUI 唤起助理同一个入口），
 * 成功才消费这次动作，失败走原逻辑。别的按键和动作原样放行。
 *
 * 诊断：装没装上、每次电源键走了哪条路，都写进 LSPosed 的模块日志和 logcat（标签 [PowerKeyPolicy.LOG_TAG]）。
 * Zeta 的 设置 › 诊断 › 数字助理 读的就是这两处。
 */
internal object PowerKeyHook {
    private val gate = PowerKeyGate()

    fun install(module: XposedModule, loader: ClassLoader) {
        val log = Logger(module)
        try {
            val type = runCatching { Class.forName(PowerKeySignature.CLASS, false, loader) }.getOrNull()
            if (type == null) {
                log.info("没装：系统里没有 ${PowerKeySignature.CLASS}（不是 HyperOS，或者改了名）")
                return
            }
            val method = PowerKeySignature.pick(type)
            if (method == null) {
                log.info("没装：${PowerKeySignature.METHOD} 签名对不上，系统里有的是：${PowerKeySignature.available(type)}")
                return
            }
            val contextField = type.declaredFields.firstOrNull { it.name == "mContext" && Context::class.java.isAssignableFrom(it.type) }
            if (contextField == null) {
                log.info("没装：${PowerKeySignature.CLASS} 里没有 Context 类型的 mContext")
                return
            }
            contextField.isAccessible = true
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("${AppVariant.current.packageName}.power-key")
                .intercept { chain -> intercept(chain, contextField, log) }
            log.info("装上了：${PowerKeySignature.CLASS.substringAfterLast('.')}.${PowerKeySignature.describe(method)}")
        } catch (e: Exception) {
            log.info("没装：安装时出错 ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun intercept(chain: XposedInterface.Chain, contextField: Field, log: Logger): Any? {
        val function = chain.getArg(0) as? String
        val source = chain.getArg(1) as? String
        if (!PowerKeyPolicy.matches(function, source)) return chain.proceed()
        val context = runCatching { contextField.get(chain.getThisObject()) as? Context }.getOrNull()
        if (context == null) {
            log.info("$source：取不到 Context，走原逻辑")
            return chain.proceed()
        }
        val now = SystemClock.uptimeMillis()
        val assistant = secureString(context.contentResolver, "voice_interaction_service")
        val locked = runCatching { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)
        val route = PowerKeyPolicy.route(function, source, assistant, locked, gate.duplicate(now))
        val who = PowerKeyPolicy.packageOf(assistant) ?: "没有"
        return when (route) {
            PowerKeyRoute.NotOurs -> chain.proceed()
            PowerKeyRoute.Duplicate -> {
                log.info("$source：${route.label}")
                true
            }
            PowerKeyRoute.NotMine, PowerKeyRoute.Locked -> {
                log.info("$source：${route.label}（默认助理 $who）")
                chain.proceed()
            }
            PowerKeyRoute.Show -> {
                val failure = showSession(source)
                if (failure == null) {
                    gate.shown(now)
                    log.info("$source：已显示 $who 的助理会话，消费这次动作")
                    true
                } else {
                    log.info("$source：显示 $who 的会话失败（$failure），走原逻辑")
                    chain.proceed()
                }
            }
        }
    }

    /** 当前用户的 secure 设置。system_server 里用隐藏的 getStringForUser，取不到时退回 getString。 */
    private fun secureString(resolver: ContentResolver, key: String): String? = runCatching {
        val user = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
        Settings.Secure::class.java.getMethod("getStringForUser", ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType)
            .invoke(null, resolver, key, user) as String?
    }.getOrElse { runCatching { Settings.Secure.getString(resolver, key) }.getOrNull() }

    /** 请系统显示当前默认助理的会话。成功返回 null，失败返回原因。 */
    private fun showSession(source: String?): String? {
        val identity = Binder.clearCallingIdentity()
        return try {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "voiceinteraction") as? IBinder ?: return "取不到 voiceinteraction 服务"
            val service = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return "解析不了 voiceinteraction 服务"
            val method = showMethod(service.javaClass) ?: return "voiceinteraction 没有 showSessionForActiveService"
            val args = Bundle().apply {
                putInt(INVOCATION_TYPE, INVOCATION_POWER_LONG_PRESS)
                putString(ZetaVoiceInteractionSession.SOURCE_EXTRA, source)
            }
            val values = method.parameterTypes.mapIndexed { index, kind ->
                when {
                    index == 0 -> args
                    kind == Int::class.javaPrimitiveType -> SHOW_FLAGS
                    else -> null
                }
            }
            if (method.invoke(service, *values.toTypedArray()) == true) null else "showSessionForActiveService 返回 false"
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            cause.javaClass.simpleName + (cause.message?.let { ": $it" } ?: "")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** showSessionForActiveService(Bundle, int, …)：Android 14 起多了 attributionTag，参数个数不同，按开头两个参数认。 */
    private fun showMethod(type: Class<*>): Method? = type.methods.firstOrNull {
        it.name == "showSessionForActiveService" && it.parameterTypes.size >= 2 &&
            it.parameterTypes[0] == Bundle::class.java && it.parameterTypes[1] == Int::class.javaPrimitiveType
    }

    /** 和 SystemUI 唤起助理一样：手势来源，带上应用内容和截图（Android 17 起应用内容里的屏幕文字要另外要）。 */
    private val SHOW_FLAGS = VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE or
        VoiceInteractionSession.SHOW_WITH_ASSIST or
        VoiceInteractionSession.SHOW_WITH_SCREENSHOT or
        if (Build.VERSION.SDK_INT >= 37) VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT else 0

    /** AssistUtils 的 invocation_type：长按电源键是 6。 */
    private const val INVOCATION_TYPE = "invocation_type"
    private const val INVOCATION_POWER_LONG_PRESS = 6

    /** 同时写 LSPosed 的模块日志和 logcat。 */
    private class Logger(private val module: XposedModule) {
        fun info(message: String) {
            runCatching { module.log(Log.INFO, PowerKeyPolicy.LOG_TAG, message) }
            Log.i(PowerKeyPolicy.LOG_TAG, message)
        }
    }
}
