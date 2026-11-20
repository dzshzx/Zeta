package com.dzshzx.zeta.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Zeta 自带的 LSPosed 模块（libxposed API 102，入口登记在 resources/META-INF/xposed/java_init.list），
 * 作用域只有 system（system_server）。做两件事，各自装不上时互不影响，也都只认本包：
 * - [PowerKeyHook]：HyperOS 长按电源键时系统直接拉起小爱，默认助理是本包时改为显示它的助理会话。
 * - [AppToolBindHook]：放行本包绑定系统应用开给系统智能体的工具服务（签名级权限，Zeta 拿不到）。
 * 正式版、测试版各带一个，在 LSPosed 里各自启用，互不干扰。
 */
class ZetaModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (!param.isSystemServer) detach()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        PowerKeyHook.install(this, param.classLoader)
        AppToolBindHook.install(this, param.classLoader)
    }
}

/** 同时写 LSPosed 的模块日志和 logcat，标签按功能和包分开（见 AppVariant）。 */
internal class ModuleLog(private val module: XposedModule, private val tag: String) {
    fun info(message: String) {
        runCatching { module.log(Log.INFO, tag, message) }
        Log.i(tag, message)
    }
}
