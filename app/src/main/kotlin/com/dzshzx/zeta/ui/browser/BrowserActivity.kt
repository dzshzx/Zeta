package com.dzshzx.zeta.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.get
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.browser.AgentBrowser
import com.dzshzx.zeta.phone.browser.BrowserUrls
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.FlatIconButton
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.PageBlurHost
import com.dzshzx.zeta.ui.components.coui2.TopBarMoreMenu
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.pageBlurSource
import com.dzshzx.zeta.ui.components.coui2.rememberPageBlur
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Forward
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 浏览器页：显示 Claude 在用的那个页面（[AgentBrowser] 的 WebView 挂过来），有地址栏、前进后退、刷新和清除数据。 用户在页面上点按就是接管，Claude
 * 的浏览器操作先等着，点“交还”或离开本页后继续。 单独一个 Activity（照 Eta）：WebView 留在固定的窗口里，进出动画由系统合成，不受 Compose 页面切换的裁剪影响。
 */
class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val hub = ConversationHub.get(application)
        setContent {
            val dark = hub.settings.darkTheme ?: isSystemInDarkTheme()
            DisposableEffect(dark) {
                val style =
                    if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ZetaTheme(darkTheme = hub.settings.darkTheme) { BrowserScreen(onExit = ::finish) }
        }
    }

    companion object {
        /** 打开浏览器页。从 Activity 里调用时开在同一块屏上。 */
        fun open(context: Context) {
            val intent = Intent(context, BrowserActivity::class.java)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

/** 放 WebView 的容器：手指一按下就算接管（不吃掉事件，WebView 照常收到）。 */
@SuppressLint("ViewConstructor")
private class TakeOverFrame(context: Context) : FrameLayout(context) {
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) AgentBrowser.takeOverByUser()
        return false
    }
}

@Composable
internal fun BrowserScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val colors = COUITheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var address by remember { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val url = AgentBrowser.url

    // 编辑时显示完整网址并全选，平时只显示主机名
    LaunchedEffect(editing) {
        if (editing) address = TextFieldValue(url, TextRange(0, url.length))
    }
    LaunchedEffect(url, editing) {
        if (!editing)
            address =
                TextFieldValue(
                    BrowserUrls.host(url).ifEmpty { url.takeIf { it != "about:blank" }.orEmpty() },
                )
    }

    fun go() {
        val input = address.text.trim()
        if (input.isEmpty()) return
        val problem =
            AgentBrowser.navigateByUser(context, if (input == BrowserUrls.host(url)) url else input)
        if (problem != null) {
            Toast.makeText(context, problem, Toast.LENGTH_SHORT).show()
            return
        }
        // 页面开始加载时 WebView 会抢走 View 焦点，Compose 这边的焦点不一定跟着变：显式退出编辑
        focus.clearFocus(force = true)
        keyboard?.hide()
        editing = false
        address = TextFieldValue(BrowserUrls.host(AgentBrowser.url).ifEmpty { input })
    }

    BrowserScaffold {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .background(colors.background)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .imePadding()
                    .navigationBarsPadding(),
        ) {
            BrowserToolbar(
                editing = editing,
                address = address,
                onAddressChange = { address = it },
                secure = url.startsWith("https://"),
                onFocusChange = { editing = it },
                onGo = ::go,
                onExit = onExit,
                onClearData = { confirmClear = true },
            )
            LoadingLine(visible = AgentBrowser.loading, progress = AgentBrowser.progress)
            BrowserPage(Modifier.weight(1f).fillMaxWidth())
        }
        ConfirmDialog(
            show = confirmClear,
            title = Strings.browserClearData(),
            summary = Strings.browserClearDataSummary(),
            confirmText = Strings.browserClear(),
            destructive = true,
            onConfirm = {
                confirmClear = false
                AgentBrowser.clearDataByUser(context) {
                    Toast.makeText(context, Strings.browserCleared(), Toast.LENGTH_SHORT).show()
                }
            },
            onDismissRequest = { confirmClear = false },
        )
    }
}

/** 顶上的工具栏：后退前进、地址栏、刷新和菜单；编辑地址时两边的按钮收起来。 */
@Composable
private fun BrowserToolbar(
    editing: Boolean,
    address: TextFieldValue,
    onAddressChange: (TextFieldValue) -> Unit,
    secure: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onGo: () -> Unit,
    onExit: () -> Unit,
    onClearData: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = !editing,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            BackForwardButtons(onExit)
        }
        AddressField(
            value = address,
            onValueChange = onAddressChange,
            secure = secure,
            onFocusChange = onFocusChange,
            onGo = onGo,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        AnimatedVisibility(
            visible = !editing,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            ReloadOrStopButton()
        }
        TopBarMoreMenu(
            listOf(
                DropdownEntry(
                    items =
                        listOf(
                            DropdownItem(
                                text = Strings.browserClearData(),
                                onClick = onClearData,
                            ),
                        ),
                ),
            ),
        )
    }
}

/** 后退（没有可退的就关掉浏览器）和前进。 */
@Composable
private fun BackForwardButtons(onExit: () -> Unit) {
    Row {
        IconActionButton(
            icon = COUIIcons.Back,
            contentDescription =
                if (AgentBrowser.canGoBack) Strings.browserBack() else Strings.browserClose(),
            onClick = {
                if (AgentBrowser.canGoBack) AgentBrowser.backByUser() else onExit()
            },
        )
        // COUI 没有“前进”箭头，用后退箭头左右翻过来，两个箭头一个样式
        IconActionButton(
            COUIIcons.Back,
            Strings.browserForward(),
            iconModifier = Modifier.graphicsLayer { scaleX = -1f },
            enabled = AgentBrowser.canGoForward,
        ) {
            AgentBrowser.forwardByUser()
        }
    }
}

/** 加载中是停止，否则是刷新。 */
@Composable
private fun ReloadOrStopButton() {
    IconActionButton(
        icon = if (AgentBrowser.loading) COUIIcons.Close else COUIIcons.Refresh,
        contentDescription =
            if (AgentBrowser.loading) Strings.browserStop() else Strings.browserReload(),
        enabled = AgentBrowser.hasPage(),
    ) {
        AgentBrowser.reloadOrStopByUser()
    }
}

/** 放 WebView 的区域：没有页面时的提示，浮在底部的状态条。 */
@Composable
private fun BrowserPage(modifier: Modifier) {
    val colors = COUITheme.colorScheme
    Box(modifier) {
        AndroidView(
            factory = { ctx -> TakeOverFrame(ctx).also { AgentBrowser.attach(it, ctx) } },
            onRelease = { AgentBrowser.detach(it) },
            modifier = Modifier.fillMaxSize(),
        )
        if (!AgentBrowser.hasPage() && !AgentBrowser.loading) {
            Text(
                text = Strings.browserEmpty(),
                style = COUITheme.textStyles.bodyM,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        // 浮在页面上，不挤占 WebView 的高度（挤占会让网页的视口忽大忽小、重新排版）
        StatusPill(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * 浏览器页的 COUI 骨架：菜单和对话框画在 Scaffold 的弹窗层里，没有它就弹不出来。 工具栏是自己的，不用
 * [com.dzshzx.zeta.ui.components.coui2.CouiPageScaffold] 的顶栏；
 * 照它把页面录成背景图（[com.dzshzx.zeta.ui.components.coui2.PageBlur]），菜单和对话框拿它做毛玻璃。系统栏和键盘的留白由内容自己处理。
 */
@Composable
private fun BrowserScaffold(content: @Composable () -> Unit) {
    val blur = rememberPageBlur()
    Scaffold(
        containerColor = COUITheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) {
        Box(Modifier.fillMaxSize().pageBlurSource(blur)) { PageBlurHost(blur, content) }
    }
}

/** 浮在页面底部的提示：接管时说 Claude 在等并带“交还”，Claude 操作时说它在做什么。 */
@Composable
private fun StatusPill(modifier: Modifier = Modifier) {
    val colors = COUITheme.colorScheme
    val controlling = AgentBrowser.userControlling
    val action = AgentBrowser.agentAction
    if (!controlling && action == null) return
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(
                    8.dp,
                    CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.2f),
                    spotColor = Color.Black.copy(alpha = 0.2f),
                )
                .clip(CircleShape)
                // 和 COUI 的 Snackbar 一样：最上层浮层的底色，按钮字用浮层上的主题色
                .background(colors.backgroundTop)
                .border(0.5.dp, colors.labelQuaternary, CircleShape)
                .padding(start = 16.dp, end = 6.dp)
                .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(colors.primary))
        Spacer(Modifier.width(8.dp))
        Text(
            text =
                if (controlling) Strings.browserUserControlling()
                else Strings.browserClaudeActing(action.orEmpty()),
            style = COUITheme.textStyles.bodyS,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        if (controlling) {
            Text(
                text = Strings.browserHandBack(),
                style = COUITheme.textStyles.bodyS.copy(fontWeight = FontWeight.Medium),
                color = colors.primaryTextOnPopup,
                modifier =
                    Modifier.clip(CircleShape)
                        .clickable { AgentBrowser.handBackByUser() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun LoadingLine(visible: Boolean, progress: Int) {
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (visible) {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(5, 100) / 100f)
                    .background(COUITheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun AddressField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    secure: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = COUITheme.colorScheme
    Row(
        modifier =
            modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(colors.fillSolid)
                .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (secure) COUIIcons.Lock else ZetaIcons.Globe,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = colors.labelSecondary,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty()) {
                Text(
                    Strings.browserAddressHint(),
                    style = COUITheme.textStyles.bodyM,
                    color = colors.labelSecondary,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = COUITheme.textStyles.bodyM.copy(color = colors.labelPrimary),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) },
            )
        }
    }
}

/** 聊天里浏览器工具那一步右边的小按钮：打开浏览器页看 Claude 的页面。 */
@Composable
internal fun BrowserStepButton() {
    val context = LocalContext.current
    FlatIconButton(
        icon = COUIIcons.Forward,
        contentDescription = Strings.browserViewInBrowser(),
        size = 26.dp,
        iconSize = 16.dp,
        modifier = Modifier.padding(start = 6.dp),
        tint = COUITheme.colorScheme.primary,
    ) {
        BrowserActivity.open(context)
    }
}
