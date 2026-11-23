package com.dzshzx.zeta.ui.chat

import android.content.Context
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.dzshzx.zeta.core.ImageExport
import com.dzshzx.zeta.core.saveToGallery
import com.dzshzx.zeta.core.share
import com.dzshzx.zeta.core.writeShareFile
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.app.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 手机上全屏看图的平台部分：存进相册、交给系统分享面板、沉浸的窗口。经 AndroidChatPlatform 交给共用的 ImageViewer。

/** 存相册、分享都在这个界面的协程里跑，结果用 Toast 说。 */
@Composable
internal fun rememberPhoneImageExporter(): ImageExporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        object : ImageExporter {
            override fun save(image: ImageExport) {
                scope.saveImage(context, image)
            }

            override fun share(image: ImageExport) {
                scope.shareImage(context, image)
            }
        }
    }
}

/** 存进相册，成没成都提示一句。 */
private fun CoroutineScope.saveImage(context: Context, image: ImageExport) = launch {
    val saved = withContext(Dispatchers.IO) { image.saveToGallery(context) }
    Toast.makeText(
            context,
            if (saved) Strings.chatSavedToGallery() else Strings.chatSaveFailed(),
            Toast.LENGTH_SHORT,
        )
        .show()
}

/** 写好要分享的文件，打开系统分享面板；没成提示一句。 */
private fun CoroutineScope.shareImage(context: Context, image: ImageExport) = launch {
    val file =
        withContext(Dispatchers.IO) { runCatching { image.writeShareFile(context) }.getOrNull() }
    val shared = file != null && image.share(context.findActivity() ?: context, file)
    if (!shared) Toast.makeText(context, Strings.chatShareFailed(), Toast.LENGTH_SHORT).show()
}

/** 看图窗口铺进刘海区域，系统栏图标用浅色，不压暗后面，也不放系统的窗口动画（进出由 [ImageViewer] 自己淡入淡出）。 */
@Composable
internal fun ImmersiveWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setDimAmount(0f)
            window.setWindowAnimations(0)
            window.attributes =
                window.attributes.also {
                    it.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            window.isNavigationBarContrastEnforced = false
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        onDispose {}
    }
}
