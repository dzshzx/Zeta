package com.dzshzx.zeta.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.ui.chat.Composer
import com.dzshzx.zeta.ui.chat.HoldToTalk
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 单独画聊天页的输入栏（贴在屏幕底部），回调都是空的；[state] 是输入框里的字，[voice] 是按住说话。 */
fun ShotTest.shootComposer(
    name: String,
    state: TextFieldState = TextFieldState(),
    voice: HoldToTalk? = null,
) =
    shoot(name) {
        Scaffold(containerColor = MiuixTheme.colorScheme.surface) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding).padding(12.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Composer(
                    state = state,
                    busy = false,
                    enabled = true,
                    attachments = emptyList(),
                    files = emptyList(),
                    commands = emptyList(),
                    onPickImages = {},
                    onPickFiles = {},
                    onPickFolder = {},
                    onEnterPath = {},
                    onOpenPrompts = {},
                    onRemoveAttachment = {},
                    onRemoveFile = {},
                    onSlashTyped = {},
                    onSend = {},
                    onStop = {},
                    voice = voice,
                )
            }
        }
    }
