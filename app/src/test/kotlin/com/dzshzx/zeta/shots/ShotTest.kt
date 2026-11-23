package com.dzshzx.zeta.shots

import android.animation.ValueAnimator
import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.dzshzx.zeta.i18n.UiLanguage
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 和手机（Xiaomi 17：1220×2656 像素，520 dpi）一样大的屏，出的图和真机截图同尺寸，可以并排对照。 */
const val PhoneQualifiers = "w375dp-h817dp-port-520dpi"

/**
 * 截图测试的基类：在 JVM 上用 Robolectric（原生图形）把 Compose 界面画出来，Roborazzi 存成 PNG。 图写到 app/build/shots/（构建参数
 * zeta.shots.dir），每次跑都重新画；和上一次不一样时旁边多一张对照图，见 [capture]。
 *
 * SDK 用 Robolectric 支持的最高版本；真机才有的东西（root、无障碍、悬浮窗、虚拟屏、Claude 进程）这里都没有， 要 root 才拿得到的数据在图上是“查不到”的样子，见
 * [Fixtures]。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = PhoneQualifiers)
abstract class ShotTest {
    private val setup =
        object : ExternalResource() {
            override fun before() {
                // View 一侧的动画（COUI 对话框和玻璃效果用的 ValueAnimator）也直接跳到终点：
                // 它们每一帧都要在 CPU 上重画整屏的模糊，放完一个对话框的进场动画要十几秒
                ValueAnimator::class
                    .java
                    .getMethod("setDurationScale", Float::class.javaPrimitiveType)
                    .invoke(null, 0f)
                // 测试用的空 Activity 不进应用的清单（免得装到手机上的包里多一个可启动的入口），开测前登记给 Robolectric
                val app = ApplicationProvider.getApplicationContext<Application>()
                Shadows.shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                // tools/shots --lang：整批按这种界面语言画（Application 起来时定成了简体中文）
                lang?.let { UiLanguage.current = it }
            }
        }

    /** Compose 的动画时长按 0 算，一开始就是放完的样子；无限动画（转圈）停在固定的一帧。 */
    val compose:
        AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule(effectContext = NoMotion)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(setup).around(compose)

    /** 当前测试的 Application（每个测试一份新的）。 */
    val app: Application
        get() = ApplicationProvider.getApplicationContext()

    /**
     * 浅色、深色各出一张：`<name>-light.png`、`<name>-dark.png`。两种配色各从头组合一遍（和冷启动时一样）， [prepare]
     * 在每种配色下都做一遍（点开对话框、展开折叠的步骤）；[target] 只截这个节点，默认整屏（含对话框）。 时钟只在 [settle] 里往前走，同样的代码每次画出同样的图。
     */
    fun shoot(
        name: String,
        target: SemanticsMatcher? = null,
        settleMs: Long = 500,
        prepare: AndroidComposeTestRule<*, *>.() -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        var dark by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            key(dark) { ZetaTheme(darkTheme = dark, windowBackdrop = false, content = content) }
        }
        for (mode in listOf(false, true)) {
            dark = mode
            settle(settleMs)
            compose.prepare()
            settle(settleMs)
            capture(
                if (target != null) compose.onNode(target) else compose.onRoot(),
                "$name${lang?.let { "-$it" }.orEmpty()}-${if (mode) "dark" else "light"}",
            )
        }
    }

    /**
     * 存成 app/build/shots/<name>.png。上一次的同名图还在、而这次画得不一样时，旁边写一张 <name>_compare.png
     * （左旧、中差异、右新）；没变或第一次画就不写，上一次留下的对照图先删掉。
     */
    fun capture(node: SemanticsNodeInteraction, name: String) {
        val file = File(shotsDir, "$name.png")
        File(shotsDir, "${name}_compare.png").delete()
        val options =
            if (file.exists()) RoborazziOptions()
            else RoborazziOptions(taskType = RoborazziTaskType.Record)
        node.captureRoboImage(file.path, options)
    }

    /**
     * 把时钟往前拨 [ms] 毫秒（延时、去抖之类到点）。后台线程（Dispatchers.Default、IO）上的活（Markdown 解析、读设置） 不算在 Compose
     * 测试的空闲判断里，再等几轮真实时间，让它们的结果落到界面上。
     */
    fun settle(ms: Long = 500) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ms)
        repeat(5) {
            compose.waitForIdle()
            Thread.sleep(20)
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
    }

    /**
     * 把节点滚进屏幕，在 [shoot] 的 prepare 里用，代替 performScrollTo。performScrollTo 每轮发一次滚动、再看节点进没进屏，
     * 直到不用再滚；滚动要等动画帧才动，而这里的时钟不自动前进，帧不来，它就一直重发（协程和内存不停涨，最后 OOM）。
     * 所以只在滚动这一下让时钟自动走，滚完再停住；页面上有转圈这类无限动画也照样滚得完。
     */
    fun SemanticsNodeInteraction.scrollIntoView(): SemanticsNodeInteraction {
        withAutoAdvance { performScrollTo() }
        return this
    }

    private fun withAutoAdvance(scroll: () -> Unit) {
        compose.mainClock.autoAdvance = true
        try {
            scroll()
        } finally {
            compose.mainClock.autoAdvance = false
        }
    }

    /**
     * 把懒加载列表滚到文字是 [text] 的那一项（它还没滚进屏时不在界面树里，[scrollIntoView] 找不到），返回那个节点。和 [scrollIntoView]
     * 一样只在滚动这一下让时钟自动走。
     */
    fun AndroidComposeTestRule<*, *>.scrollListTo(text: String): SemanticsNodeInteraction {
        withAutoAdvance {
            onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
        }
        return onNodeWithText(text)
    }

    /** 点文字是 [text] 的节点，再让时钟走过菜单、弹窗展开的动画；在 [shoot] 的 prepare 里或直接对 [compose] 用。 */
    fun AndroidComposeTestRule<*, *>.tap(text: String) = tapNode(onNodeWithText(text))

    /** 同 [tap]，点的是给定的节点（比如按描述找的图标按钮）。 */
    fun AndroidComposeTestRule<*, *>.tapNode(node: SemanticsNodeInteraction) {
        node.performClick()
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }

    private object NoMotion : MotionDurationScale {
        override val scaleFactor: Float
            get() = 0f
    }

    companion object {
        val shotsDir: File = File(System.getProperty("zeta.shots.dir") ?: "build/shots")

        /** tools/shots --lang 给的界面语言（构建参数 zeta.shots.lang）；没给时为 null，用测试默认的简体中文。 */
        val lang: String? =
            System.getProperty("zeta.shots.lang")?.takeIf { it in UiLanguage.supported }
    }
}
