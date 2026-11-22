package com.dzshzx.zeta.ui.components

import androidx.compose.ui.text.font.FontFamily
import java.util.ServiceLoader

/**
 * 随包的等宽字体（JetBrains Mono），代码块、命令、路径、日志都用它。不用 [FontFamily.Monospace]： 部分系统会把它换成比例字体。CJK
 * 等缺失字形由系统字体回退。 采用无连字版本，避免 `->`、`!=` 在命令与代码里被合成单个字形。字体文件在 app 的 res/font 里， 手机上按资源 ID 取，桌面版构建时拷进
 * jar；各平台怎么取见 [Fonts]。
 */
val MonoFont: FontFamily by lazy { Fonts.current.mono }

/** 平台怎么取随包的字体。实现登记在 META-INF/services/com.dzshzx.zeta.ui.components.Fonts 里。 */
interface Fonts {
    val mono: FontFamily

    companion object {
        val current: Fonts by lazy {
            ServiceLoader.load(Fonts::class.java, Fonts::class.java.classLoader).firstOrNull()
                ?: error("没有登记字体的实现：META-INF/services/${Fonts::class.java.name}")
        }
    }
}
