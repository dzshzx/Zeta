package com.dzshzx.zeta.core

import java.util.ServiceLoader

/**
 * 本平台登记在各模块 META-INF/services 里的 [T] 的实现（不经中枢、全进程一份的平台部分，见 AGENTS.md「架构」）；没登记时报错。 各接口的 current 用
 * lazy 存一份。只接着取 iterator()，R8 能把这次查找改写成直接构造。
 */
inline fun <reified T : Any> platformService(): T {
    val found = ServiceLoader.load(T::class.java, T::class.java.classLoader).iterator()
    return if (found.hasNext()) found.next()
    else error("没有登记 ${T::class.java.simpleName} 的实现：META-INF/services/${T::class.java.name}")
}
