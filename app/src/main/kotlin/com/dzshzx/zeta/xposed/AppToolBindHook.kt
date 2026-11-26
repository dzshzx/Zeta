package com.dzshzx.zeta.xposed

import android.content.pm.PackageManager
import android.os.Binder
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.phone.tools.AppToolCall
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统应用开给系统智能体的工具服务，多数在清单里要求签名级权限 [AppToolCall.BIND_PERMISSION]（小爱定义的）， Zeta 绑定时被 ActivityManager
 * 拒绝（`Not allowed to bind to service`）。这里拦 `ActivityManagerService.checkComponentPermission` （绑定服务时
 * ActiveServices 用它查服务要求的权限）：查的正是这个权限、调用方是本包时答「有」，别的一律原样交给系统。
 *
 * 只放行绑定这一步。工具服务自己还看调用方是不是系统签名，请求仍由 Zeta 的 root 辅助进程以 system 身份发（phone/tools/AppToolCall.kt）。
 * 装没装上、第一次放行，写进 LSPosed 的模块日志和 logcat，标签 [AppVariant.appToolsTag]。
 */
internal object AppToolBindHook {
    private const val CLASS = "com.android.server.am.ActivityManagerService"
    private const val METHOD = "checkComponentPermission"

    /** 调用方 uid 是不是本包；system_server 活着期间 uid 不变，查一次记下。 */
    private val mine = ConcurrentHashMap<Int, Boolean>()

    fun install(module: XposedModule, loader: ClassLoader, variant: AppVariant) {
        val log = ModuleLog(module, variant.appToolsTag)
        try {
            val type = Class.forName(CLASS, false, loader)
            val methods = pick(type)
            if (methods.isEmpty()) {
                log.info(
                    "没装：$METHOD 签名对不上，系统里有的是：" +
                        type.declaredMethods
                            .filter { it.name == METHOD }
                            .joinToString("；") { describe(it) },
                )
                return
            }
            for (method in methods) {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId(
                        "${variant.packageName}.app-tool-bind.${method.parameterCount}",
                    )
                    .intercept { chain ->
                        val uid = chain.getArg(UID_ARG) as Int
                        if (
                            chain.getArg(0) == AppToolCall.BIND_PERMISSION &&
                                isMine(uid, log, variant.packageName)
                        )
                            PackageManager.PERMISSION_GRANTED
                        else chain.proceed()
                    }
            }
            log.info("装上了：" + methods.joinToString("；") { describe(it) })
        } catch (e: Exception) {
            log.info("没装：安装时出错 ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * checkComponentPermission(String permission, int pid, int uid, …, boolean
     * exported)：各版本中间可能多一个参数，按头尾认，uid 都是第三个。
     */
    private fun pick(type: Class<*>): List<Method> =
        type.declaredMethods.filter {
            val p = it.parameterTypes
            it.name == METHOD &&
                it.returnType == Int::class.javaPrimitiveType &&
                p.size in 5..6 &&
                p[0] == String::class.java &&
                p[1] == Int::class.javaPrimitiveType &&
                p[UID_ARG] == Int::class.javaPrimitiveType &&
                p.last() == Boolean::class.javaPrimitiveType
        }

    private const val UID_ARG = 2

    private fun isMine(uid: Int, log: ModuleLog, self: String): Boolean =
        mine.getOrPut(uid) {
            val identity = Binder.clearCallingIdentity()
            val packages =
                try {
                    val pm =
                        Class.forName("android.app.AppGlobals")
                            .getMethod("getPackageManager")
                            .invoke(null)
                    pm.javaClass
                        .getMethod("getPackagesForUid", Int::class.javaPrimitiveType)
                        .invoke(pm, uid) as Array<*>?
                } catch (_: Exception) {
                    null
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
            (packages?.contains(self) == true).also {
                if (it) log.info("放行 uid $uid 绑定系统应用的工具服务")
            }
        }

    private fun describe(method: Method): String =
        method.name + "(" + method.parameterTypes.joinToString(", ") { it.simpleName } + ")"
}
