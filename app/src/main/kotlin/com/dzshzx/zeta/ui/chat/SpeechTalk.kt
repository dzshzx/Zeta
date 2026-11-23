package com.dzshzx.zeta.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.voice.SpeechInput
import com.dzshzx.zeta.core.voice.SpeechState
import com.dzshzx.zeta.i18n.Strings
import kotlinx.coroutines.flow.StateFlow

/** 手机上按住说话的识别：系统自带的识别服务（[SpeechInput]），开始前先看麦克风权限，没有就用 [askPermission] 去要。 */
internal class SpeechTalk(
    private val speech: SpeechInput,
    private val context: Context,
    private val askPermission: () -> Unit,
) : TalkInput {
    override val state: StateFlow<SpeechState>
        get() = speech.state

    override fun start(onResult: (String) -> Unit, onError: (String) -> Unit): Boolean {
        if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            askPermission()
            return false
        }
        speech.start(onResult = onResult, onError = { onError(it.message) })
        return true
    }

    override fun finish() = speech.finish()

    override fun cancel() = speech.cancel()
}

/** 手机上有能用的识别服务时给一个 [SpeechTalk]，没有时为 null。 */
@Composable
internal fun rememberSpeechTalk(): TalkInput? {
    val context = LocalContext.current
    val available = remember { SpeechInput.available(context) }
    if (!available) return null
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val text = if (granted) Strings.chatHoldToTalkReady() else Strings.chatNoMicPermission()
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    return remember {
        SpeechTalk(SpeechInput(context), context) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
