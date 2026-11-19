package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** 记住最后一个非空值：对话框、页面退场动画期间内容还在，不会先变空再消失。 */
@Composable
internal fun <T> rememberRetained(value: T?): T? {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    return last
}

/**
 * 每 [periodMs] 走一次的“现在”。读它的地方每次走都重组，所以只在真要显示时间的那一小块里读，
 * 别在整页的最外层读。
 */
@Composable
internal fun rememberNow(periodMs: Long = 30_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}
