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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.phone.browser.AgentBrowser
import com.dzshzx.zeta.phone.browser.BrowserUrls
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.theme.CouiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浏览器页：显示 Claude 在用的那个页面（[AgentBrowser] 的 WebView 挂过来），有地址栏、前进后退、刷新和清除数据。
 * 用户在页面上点按就是接管，Claude 的浏览器操作先等着，点“交还”或离开本页后继续。
 * 单独一个 Activity（照 Eta）：WebView 留在固定的窗口里，进出动画由系统合成，不受 Compose 页面切换的裁剪影响。
 */
class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val hub = ConversationHub.get(application)
        setContent {
            val dark = hub.darkTheme ?: isSystemInDarkTheme()
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ZetaTheme(darkTheme = hub.darkTheme) { BrowserScreen(onExit = ::finish) }
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
private fun BrowserScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
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
        if (!editing) address = TextFieldValue(BrowserUrls.host(url).ifEmpty { url.takeIf { it != "about:blank" }.orEmpty() })
    }

    fun go() {
        val input = address.text.trim()
        if (input.isEmpty()) return
        val problem = AgentBrowser.navigateByUser(context, if (input == BrowserUrls.host(url)) url else input)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(visible = !editing, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Row {
                    BarIcon(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        description = if (AgentBrowser.canGoBack) "后退" else "关闭浏览器",
                        onClick = { if (AgentBrowser.canGoBack) AgentBrowser.backByUser() else onExit() },
                    )
                    BarIcon(Icons.AutoMirrored.Rounded.ArrowForward, "前进", enabled = AgentBrowser.canGoForward) { AgentBrowser.forwardByUser() }
                }
            }
            AddressField(
                value = address,
                onValueChange = { address = it },
                secure = url.startsWith("https://"),
                onFocusChange = { editing = it },
                onGo = ::go,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            AnimatedVisibility(visible = !editing, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                BarIcon(
                    icon = if (AgentBrowser.loading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                    description = if (AgentBrowser.loading) "停止加载" else "刷新",
                    enabled = AgentBrowser.hasPage(),
                ) { AgentBrowser.reloadOrStopByUser() }
            }
            MoreMenu(onClear = { confirmClear = true })
        }
        LoadingLine(visible = AgentBrowser.loading, progress = AgentBrowser.progress)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx -> TakeOverFrame(ctx).also { AgentBrowser.attach(it, ctx) } },
                onRelease = { AgentBrowser.detach(it) },
                modifier = Modifier.fillMaxSize(),
            )
            if (!AgentBrowser.hasPage() && !AgentBrowser.loading) {
                Text(
                    text = "还没有打开网页\nClaude 用浏览器时，页面会显示在这里；也可以在上面输入网址",
                    style = MiuixTheme.textStyles.body2,
                    color = colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            // 浮在页面上，不挤占 WebView 的高度（挤占会让网页的视口忽大忽小、重新排版）
            StatusPill(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
    CouiConfirmDialog(
        visible = confirmClear,
        title = "清除浏览数据",
        message = "Cookie、登录状态、网站存储和缓存都会删掉，当前页面也会关闭。在这个浏览器里登录过的网站都要重新登录。",
        confirmText = "清除",
        destructive = true,
        onConfirm = {
            confirmClear = false
            AgentBrowser.clearDataByUser(context) { Toast.makeText(context, "已清除浏览数据", Toast.LENGTH_SHORT).show() }
        },
        onDismissRequest = { confirmClear = false },
    )
}

/** 浮在页面底部的提示：接管时说 Claude 在等并带“交还”，Claude 操作时说它在做什么。 */
@Composable
private fun StatusPill(modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val dark = CouiTheme.isDark
    val controlling = AgentBrowser.userControlling
    val action = AgentBrowser.agentAction
    if (!controlling && action == null) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, CouiCapsuleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
            .clip(CouiCapsuleShape)
            .background(if (dark) Color(0xFF2A2C30) else Color.White)
            .border(0.5.dp, if (dark) Color(0x24FFFFFF) else Color(0x14000000), CouiCapsuleShape)
            .padding(start = 16.dp, end = 6.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(colors.primary))
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (controlling) "你在操作，Claude 先等着" else "Claude 正在操作：$action",
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp),
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        if (controlling) {
            Text(
                text = "交还 Claude",
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = colors.primary,
                modifier = Modifier
                    .clip(CouiCapsuleShape)
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
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(5, 100) / 100f)
                    .background(MiuixTheme.colorScheme.primary),
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
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(CouiCapsuleShape)
            .background(colors.surfaceContainerHigh)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (secure) Icons.Rounded.Lock else Icons.Rounded.Public,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = colors.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty()) {
                Text("搜索或输入网址", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body2.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) },
            )
        }
    }
}

@Composable
private fun MoreMenu(onClear: () -> Unit) {
    val anchor = rememberCouiMenuAnchor()
    var expanded by remember { mutableStateOf(false) }
    Box {
        BarIcon(Icons.Rounded.MoreVert, "更多", modifier = Modifier.couiMenuAnchor(anchor)) { anchor.open { expanded = true } }
        Menu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = listOf(CouiMenuItem(value = 0, text = "清除浏览数据")),
            onItemClick = {
                expanded = false
                onClear()
            },
            anchor = anchor,
        )
    }
}

@Composable
private fun BarIcon(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.3f),
        )
    }
}

/** 聊天里浏览器工具那一步右边的小按钮：打开浏览器页看 Claude 的页面。 */
@Composable
internal fun BrowserStepButton() {
    val context = LocalContext.current
    Box(
        modifier = Modifier.padding(start = 6.dp).size(26.dp).clip(CircleShape).clickable { BrowserActivity.open(context) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.OpenInBrowser,
            contentDescription = "在浏览器里查看",
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
    }
}
