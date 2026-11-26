package com.dzshzx.zeta.xposed

import android.content.Context
import com.dzshzx.zeta.core.AppVariant
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 让本包的通知按 Zeta 自己写的焦点参数上小米超级岛（通知 extras 里的 `miui.focus.param`，见 core/FocusParam.kt）。
 *
 * HyperOS 的焦点通知插件（miui.systemui.plugin，跑在系统界面进程里）鉴权时（`fetchAuthResult`），国行系统上不带自定义视图的焦点通知先看
 * `SignatureChecker.checkSignatures(packageName)`，和系统界面同签名才放行，否则交给 `AuthManager` 去小米服务器核对申请过的权限，
 * 没申请过就不上岛。这里拦它，查的是本包时答「是」，别的包原样交给系统。
 *
 * Zeta 的通知不申请 Android 16 的实时通知（见 core/AgentService.kt），所以插件不会拿系统转出的参数换掉 Zeta 的，不用再拦
 * `NotificationSettingsManager.isDynamicIslandLiveUpdateBlackList`。
 *
 * 它在插件的 ClassLoader 里，插件晚于系统界面加载，所以先在系统界面的 ClassLoader 里找，找不到就等
 * `PluginInstance$PluginFactory.createPluginContext` 建出插件的 Context 再装。模块没启用或装不上时不上岛。 装没装上、第一次放行写进
 * LSPosed 的模块日志和 logcat，标签 [AppVariant.islandTag]。
 */
internal object FocusAuthHook {
    const val PROCESS = "com.android.systemui"
    private const val PLUGIN_FACTORY =
        "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"

    /** 要拦的：类、方法，都是 `boolean 方法(String packageName)`。 */
    private val targets =
        listOf("miui.systemui.notification.focus.SignatureChecker" to "checkSignatures")

    private val installed =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>()))

    private val granted = Collections.synchronizedSet(mutableSetOf<String>())

    fun install(module: XposedModule, loader: ClassLoader, variant: AppVariant) {
        val log = ModuleLog(module, variant.islandTag)
        try {
            if (installInto(module, loader, log, variant)) return
            val factory = Class.forName(PLUGIN_FACTORY, false, loader)
            val methods = factory.declaredMethods.filter { it.name == "createPluginContext" }
            if (methods.isEmpty()) {
                log.info("没装：$PLUGIN_FACTORY 里没有 createPluginContext")
                return
            }
            for (method in methods) {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId(
                        "${variant.packageName}.focus.plugin.${method.parameterCount}",
                    )
                    .intercept { chain ->
                        val result = chain.proceed()
                        (result as? Context)?.classLoader?.let {
                            installInto(module, it, log, variant)
                        }
                        result
                    }
            }
            log.info("等焦点通知插件加载")
        } catch (e: Exception) {
            log.info("没装：安装时出错 ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** [loader] 是焦点通知插件的（找得到第一个类）就都装上，返回是不是；同一个 ClassLoader 只装一次。 别的插件的 ClassLoader 里没有这些类，不记日志。 */
    private fun installInto(
        module: XposedModule,
        loader: ClassLoader,
        log: ModuleLog,
        variant: AppVariant,
    ): Boolean {
        if (loader in installed) return true
        if (runCatching { Class.forName(targets.first().first, false, loader) }.isFailure)
            return false
        installed += loader
        for ((className, name) in targets) {
            val type = runCatching { Class.forName(className, false, loader) }.getOrNull()
            val method = type?.let { pick(it, name) }
            if (method == null) {
                log.info(
                    "没装：插件里没有 ${className.substringAfterLast('.')}.$name(String)，有的是：${available(type, name)}",
                )
                continue
            }
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("${variant.packageName}.focus.$name")
                .intercept { chain ->
                    if (chain.getArg(0) == variant.packageName) {
                        if (granted.add(name)) log.info("对本包答是：$name")
                        true
                    } else chain.proceed()
                }
            log.info("装上了：${className.substringAfterLast('.')}.${method.describe()}")
        }
        return true
    }

    /** `boolean name(String)`；同名的别的重载（比如三个参数的 checkSignatures）不拦。 */
    private fun pick(type: Class<*>, name: String): Method? =
        type.declaredMethods.firstOrNull {
            it.name == name &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
}
