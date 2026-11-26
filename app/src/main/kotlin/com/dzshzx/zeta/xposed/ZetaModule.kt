package com.dzshzx.zeta.xposed

import android.util.Log
import com.dzshzx.zeta.core.AppVariant
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method

/**
 * Zeta 自带的 LSPosed 模块（libxposed API 102，入口登记在 resources/META-INF/xposed/java_init.list），作用域是
 * system（system_server）和系统界面（scope.list）。做三件事，各自装不上时互不影响，也都只认本包：
 * - [PowerKeyHook]：HyperOS 长按电源键时系统直接拉起小爱，默认助理是本包时改为显示它的助理会话。
 * - [AppToolBindHook]：放行本包绑定系统应用开给系统智能体的工具服务（签名级权限，Zeta 拿不到）。
 * - [FocusAuthHook]（系统界面）：放行本包的超级岛焦点通知，不用向小米申请权限。
 *
 * 正式版、测试版各带一个，在 LSPosed 里各自启用，互不干扰。本包是哪个取 LSPosed 给的模块信息（[variant]），不用 [AppVariant.current]：它经
 * ServiceLoader 读 APK 里的登记，在系统界面进程里实测读不到。
 */
class ZetaModule : XposedModule() {
    private val variant by lazy { AppVariant(moduleApplicationInfo.packageName) }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (!param.isSystemServer && param.processName != FocusAuthHook.PROCESS) detach()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName == FocusAuthHook.PROCESS && param.isFirstPackage)
            FocusAuthHook.install(this, param.defaultClassLoader, variant)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        PowerKeyHook.install(this, param.classLoader, variant)
        AppToolBindHook.install(this, param.classLoader, variant)
    }
}

/** 同时写 LSPosed 的模块日志和 logcat，标签按功能和包分开（见 AppVariant）。 */
internal class ModuleLog(private val module: XposedModule, private val tag: String) {
    fun info(message: String) {
        runCatching { module.log(Log.INFO, tag, message) }
        Log.i(tag, message)
    }
}

/** 日志里方法的写法：triggerFunction(String, String, Bundle, boolean, String)。 */
internal fun Method.describe(): String =
    name + parameterTypes.joinToString(", ", "(", ")") { it.simpleName }

/** [type] 里叫 [name] 的方法有哪些（签名对不上时写进日志，方便看 ROM 改成了什么）。 */
internal fun available(type: Class<*>?, name: String): String =
    type
        ?.declaredMethods
        ?.filter { it.name == name }
        ?.joinToString("；") { it.returnType.simpleName + " " + it.describe() }
        .orEmpty()
        .ifEmpty { "没有" }
