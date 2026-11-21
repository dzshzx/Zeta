package com.dzshzx.zeta.ui.pane

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.SessionSummary

/** 别的页面（设置里的存储页）请侧栏定位到一个会话：侧栏露出来以后滚到那一行、短暂高亮，不替用户选中， 删不删由用户在侧栏里照常操作（长按菜单或多选）。在主线程上读写。 */
internal object SessionReveal {
    var request by mutableStateOf<String?>(null)
}

/** 侧栏列表（不在搜索时）里会话 [id] 那一行的位置：各组依次是一个组标题加组里的会话，空组不出现。没有返回 null。 */
internal fun revealIndex(groups: List<List<SessionSummary>>, id: String): Int? {
    var index = 0
    for (group in groups) {
        if (group.isEmpty()) continue
        index++ // 组标题
        val at = group.indexOfFirst { it.id == id }
        if (at >= 0) return index + at
        index += group.size
    }
    return null
}
