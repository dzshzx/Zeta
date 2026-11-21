package com.dzshzx.zeta.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PageStackTest {
    private val register =
        object : ExternalResource() {
            override fun before() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                Shadows.shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            }
        }
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(register).around(compose)

    private val tasks = AppPage.Tasks("k")

    @Test
    fun laterPageIsOnTopAndBackClosesItFirst() {
        // 审查时的问题：后台任务页画在安装页上面，返回键却先关安装页
        val stack = PageStack()
        stack.open(AppPage.Install)
        stack.open(tasks)
        assertEquals(tasks, stack.top)
        stack.pop()
        assertEquals(listOf(AppPage.Install), stack.pages)
    }

    @Test
    fun openingAnOpenKindMovesItToTheTop() {
        val stack = PageStack(listOf(AppPage.Settings, AppPage.Linux, tasks))
        stack.open(AppPage.Settings)
        assertEquals(listOf(AppPage.Linux, tasks, AppPage.Settings), stack.pages)
        stack.open(AppPage.Tasks("*"))
        assertEquals(listOf(AppPage.Linux, AppPage.Settings, AppPage.Tasks("*")), stack.pages)
    }

    @Test
    fun closeAllKeepsSetupWhenAsked() {
        val stack =
            PageStack(
                listOf(AppPage.Settings, AppPage.Install, AppPage.Linux, AppPage.Login, tasks),
            )
        stack.closeAll(keepSetup = true)
        assertEquals(listOf(AppPage.Install, AppPage.Login), stack.pages)
        stack.closeAll(keepSetup = false)
        assertEquals(emptyList<AppPage>(), stack.pages)
    }

    @Test
    fun closingPagesStayInPlaceWhileTheyAnimateOut() {
        val s = AppPage.Settings
        val l = AppPage.Linux
        val i = AppPage.Install
        assertEquals(listOf(s, l), drawOrder(listOf(s, l), listOf(s)))
        assertEquals(listOf(s, l, i), drawOrder(listOf(s, l, i), listOf(i)))
        assertEquals(listOf(s, l, i), drawOrder(listOf(s, l), listOf(s, l, i)))
        assertEquals(listOf(l, s), drawOrder(listOf(s, l), listOf(l, s)))
    }

    @Test
    fun drawsInStackOrderAndBackClosesTheTop() {
        val stack = PageStack()
        val hits = mutableListOf<AppPage>()
        compose.setContent {
            Host(stack) { page -> Box(Modifier.fillMaxSize().clickable { hits += page }) }
        }
        compose.runOnIdle {
            stack.open(AppPage.Install)
            stack.open(tasks)
        }
        compose.onRoot().performClick()
        assertEquals(listOf<AppPage>(tasks), hits)

        back()
        compose.runOnIdle { assertEquals(listOf(AppPage.Install), stack.pages) }
        compose.onRoot().performClick()
        assertEquals(listOf(tasks, AppPage.Install), hits)
    }

    @Test
    fun touchesDoNotLeakToThePageBelow() {
        val stack = PageStack(listOf(AppPage.Settings))
        val hits = mutableListOf<AppPage>()
        compose.setContent {
            Host(stack) { page ->
                if (page == AppPage.Settings) Box(Modifier.fillMaxSize().clickable { hits += page })
            }
        }
        compose.runOnIdle { stack.open(AppPage.Linux) }
        compose.onRoot().performClick()
        assertEquals(emptyList<AppPage>(), hits)
    }

    @Test
    fun pagesBelowTheTopDoNotTakeTheBackKey() {
        // 设置页开着子页时又开了 Linux 环境页：返回键关 Linux 环境页，不去关压在下面的子页
        val stack = PageStack()
        val handled = mutableListOf<AppPage>()
        compose.setContent {
            Host(stack) { page -> if (page == AppPage.Settings) BackHandler { handled += page } }
        }
        compose.runOnIdle { stack.open(AppPage.Settings) }
        back()
        assertEquals(listOf<AppPage>(AppPage.Settings), handled)

        compose.runOnIdle { stack.open(AppPage.Linux) }
        back()
        compose.runOnIdle { assertEquals(listOf<AppPage>(AppPage.Settings), stack.pages) }
        assertEquals(listOf<AppPage>(AppPage.Settings), handled)

        back()
        assertEquals(listOf<AppPage>(AppPage.Settings, AppPage.Settings), handled)
    }

    @Test
    fun stackSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        lateinit var stack: PageStack
        restoration.setContent { stack = rememberPageStack() }
        val sub = AppPage.Subagent("k", "toolu_1")
        compose.runOnIdle {
            stack.open(AppPage.Login)
            stack.open(tasks)
            stack.open(AppPage.Settings)
            stack.open(sub)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(listOf(AppPage.Login, tasks, AppPage.Settings, sub), stack.pages)
        }
    }

    /** 和 MainActivity 一样：先注册关最上面一页的返回键，再画页面。 */
    @Composable
    private fun Host(stack: PageStack, page: @Composable (AppPage) -> Unit) {
        BackHandler(enabled = stack.any) { stack.pop() }
        PageStackHost(stack, page)
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
