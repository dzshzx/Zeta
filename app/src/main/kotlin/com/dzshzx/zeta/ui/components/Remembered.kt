package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
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

/**
 * [read] 读到的值，只在界面看得见（生命周期至少 STARTED）时跟着变；看不见时停在最后一份、也不读 [read]，
 * 回到前台再一次补上。Compose 在界面停止后只暂停动画帧，重组和测量照做：随回答流式输出频繁变的状态，
 * 经它读就不会让看不见的界面跟着重组、测量。
 */
@Composable
internal fun <T : Any> whileStarted(read: () -> T): T {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val last = remember { LastValue<T>() }
    if (state.isAtLeast(Lifecycle.State.STARTED) || last.value == null) last.value = read()
    return checkNotNull(last.value)
}

/** [whileStarted] 记下的最后一份：普通字段，不是快照状态，看不见时不读 [read] 就不订阅它的变化。 */
private class LastValue<T : Any> {
    var value: T? = null
}
