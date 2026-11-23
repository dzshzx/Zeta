package com.dzshzx.zeta.i18n

import android.app.Application
import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.HelperServe
import com.dzshzx.zeta.core.str
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 界面语言照 Android 的应用语言走（[AppLanguage]）：没单独设时跟随系统语言（不是中英文时用简体中文），设置里选了就用选的；语言变了时开着的 核心连接再报一次
 * core.client。Robolectric 的系统语言是 en-US；测试的 Application 把界面语言固定成简体中文，这里自己 install。 也核对 android.icu
 * 和单元测试用的 ICU4J 出的字一样。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AppLanguageTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val serves = ArrayList<HelperServe>()

    @After
    fun cleanup() {
        serves.forEach { it.close() }
        UiLanguage.current = UiLanguage.SOURCE
    }

    @Test
    fun followsTheSystemUntilAChoiceIsMade() {
        AppLanguage.install(app)
        assertEquals("en", UiLanguage.current)
        assertNull(AppLanguage.choice(app))

        AppLanguage.choose(app, "zh-Hans")
        assertEquals("zh-Hans", UiLanguage.current)
        assertEquals("zh-Hans", AppLanguage.choice(app))
        // 选的存在 Android 的应用语言里，系统设置的「应用语言」看到的是同一个
        assertEquals(
            "zh-Hans",
            app.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags(),
        )

        AppLanguage.choose(app, null)
        assertEquals("en", UiLanguage.current)
        assertNull(AppLanguage.choice(app))
    }

    /** 系统设置里改的应用语言也认：配置变化时重新定。 */
    @Test
    fun picksUpChangesMadeInSystemSettings() {
        AppLanguage.install(app)
        app.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags("zh-CN")
        assertEquals("en", UiLanguage.current)
        // 系统改了应用语言以后发配置变化：Application 把它交给登记的回调
        app.onConfigurationChanged(Configuration(app.resources.configuration))
        assertEquals("zh-Hans", UiLanguage.current)
        assertEquals("zh-Hans", AppLanguage.choice(app))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun otherSystemLanguagesGetSimplifiedChinese() {
        AppLanguage.install(app)
        assertEquals("zh-Hans", UiLanguage.current)
    }

    /** 连上时 core.client 带着当时的语言；设置里换了语言，开着的连接马上再报一次新语言。 */
    @Test
    fun changingTheLanguageReintroducesOpenConnections() {
        val clients = LinkedBlockingQueue<JsonObject>()
        val serve =
            HelperServe(
                    name = "lang-test",
                    open = {
                        HelperServe.Opened(ProcessBuilder("bash", "-c", ECHO_CLIENT).start()) {
                            it.destroy()
                        }
                    },
                    subscriptions = emptyList(),
                    onMessage = { if (it.str("type") == "client") clients.put(it) },
                    onProblem = {},
                    device = "Xiaomi 17",
                )
                .also { serves += it }
        val first = clients.poll(10, TimeUnit.SECONDS)!!
        assertEquals("zh-Hans", first.str("lang"))
        assertEquals("Xiaomi 17", first.str("device"))

        AppLanguage.choose(app, "en")
        val again = clients.poll(10, TimeUnit.SECONDS)!!
        assertEquals("en", again.str("lang"))
        assertEquals("Xiaomi 17", again.str("device"))
        serve.close()
    }

    /** 手机上出字用 android.icu，单元测试用 ICU4J：两者对两份文字表和测试文字表的每句话出的字一样。 */
    @Test
    fun androidIcuAndIcu4jSayTheSame() {
        val android = IcuMessageFormatter()
        val icu4j = Icu4jMessageFormatter()
        var compared = 0
        for (catalog in listOf(Strings.catalog, CoreStrings.catalog, Catalog("test"))) {
            for (lang in UiLanguage.supported) {
                for ((key, pattern) in catalog.messages(lang)) {
                    val names = com.ibm.icu.text.MessageFormat(pattern).argumentNames
                    for (n in listOf(1, 3)) {
                        val args = names.associateWith { n }
                        val want = runCatching { icu4j.format(pattern, lang, args) }.getOrNull()
                        assertEquals(
                            "$lang $key",
                            want,
                            runCatching { android.format(pattern, lang, args) }.getOrNull(),
                        )
                        if (want != null) compared++
                    }
                }
            }
        }
        assertTrue(compared > 0)
    }

    private companion object {
        /** 假 serve：打招呼，收到 core.client 就把它原样推回来（type 改成 client），别的请求都答 ok。 */
        val ECHO_CLIENT =
            """
            echo '{"type":"zeta_hello","protocol":${HelperServe.PROTOCOL}}'
            while IFS= read -r line; do
              id=${'$'}{line#*\"id\":}; id=${'$'}{id%%,*}
              case ${'$'}line in
                *'"method":"core.client"'*) printf '%s\n' "${'$'}line" | sed 's/"method":"core.client"/"type":"client"/' ;;
              esac
              printf '{"type":"zeta_reply","id":%s,"ok":true}\n' "${'$'}id"
            done
            """
                .trimIndent()
    }
}
