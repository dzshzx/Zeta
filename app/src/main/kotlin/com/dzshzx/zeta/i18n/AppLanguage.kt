package com.dzshzx.zeta.i18n

import android.app.Application
import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.dzshzx.zeta.core.HelperServe
import kotlin.concurrent.thread

/**
 * 手机版界面的语言（docs/i18n.md）照 Android 的应用语言走（框架的 [LocaleManager]，系统设置的「应用语言」里也能改）：没单独设时跟随系统语言。 定下来的语言写进
 * [UiLanguage.current]，生成的取字函数按它出字。语言变了（设置里改、系统设置里改、跟随的系统语言变了）时 Android 发配置变化，
 * 这里重新定一次；变了就让开着的核心连接再报一次 core.client，核心之后的应答用新语言。界面由 Android 重建 Activity 跟着换（语言不在清单的 configChanges
 * 里）。
 */
object AppLanguage {
    /** 进程起来时调一次（[com.dzshzx.zeta.ZetaApp]）：定下语言，之后跟着配置变化。 */
    fun install(app: Application) {
        refresh(app)
        app.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = refresh(app)

                @Deprecated("Deprecated in Java") override fun onLowMemory() = Unit
            },
        )
    }

    /** 设置里选的：null 是跟随系统，否则是 [UiLanguage.supported] 之一。 */
    fun choice(context: Context): String? =
        manager(context)
            .applicationLocales
            .takeUnless { it.isEmpty }
            ?.let { UiLanguage.resolve(it.tags()) }

    /** 改成 [lang]（null 是跟随系统）：交给 Android 记下，它随后发配置变化、重建界面；这里先把语言定下来。 */
    fun choose(context: Context, lang: String?) {
        manager(context).applicationLocales =
            if (lang == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(lang)
        refresh(context)
    }

    /** 按应用语言（没单独设时按系统语言）重新定界面语言；和原来不一样时让开着的核心连接再报一次。 */
    fun refresh(context: Context) {
        val manager = manager(context)
        val locales = manager.applicationLocales.takeUnless { it.isEmpty } ?: manager.systemLocales
        val lang = UiLanguage.resolve(locales.tags())
        if (lang == UiLanguage.current) return
        UiLanguage.current = lang
        // 写连接可能卡在网络上，不占主线程
        thread(name = "zeta-introduce", isDaemon = true) { HelperServe.introduceAll() }
    }

    private fun manager(context: Context): LocaleManager =
        context.getSystemService(LocaleManager::class.java)

    private fun LocaleList.tags(): List<String> = (0 until size()).map { get(it).toLanguageTag() }
}
