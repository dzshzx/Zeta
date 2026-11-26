package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * 小米超级岛（焦点通知）的参数：通知的 extras 里放 [FocusParam.EXTRA]（JSON）、[FocusParam.PICS]（图标）、
 * [FocusParam.ACTIONS]（按钮），HyperOS 照它画岛和通知栏里的焦点卡片。小米只给申请过权限的应用画，Zeta 的 LSPosed 模块在系统界面里
 * 放行本包、并让实时通知也用 Zeta 自己的参数（xposed/FocusAuthHook.kt）；模块没启用时系统不认这几项，实时通知照常由系统转成岛，
 * 提醒照常是横幅。字段照 HyperOS 3、4 的焦点通知协议（param_v2）。
 *
 * 用在两处（发通知在 AgentService.kt 的 Notifier）：常驻的实时通知（[of]，停下来等用户时岛自己展开、带回答按钮），
 * 对话的提醒（跑完、中断、等你，[alert]：岛弹出来停 [ALERT_ISLAND_SECONDS] 秒后收起，通知留在通知栏）。这里是不碰 Android 的部分。
 */

/**
 * 岛和焦点卡片上显示什么。大岛左边是图标加 [short]（“待确认”“已完成”这类短词），右边是 [right]（等的是什么、或对话标题）； [float] 时岛自己展开，[timesOut]
 * 时岛停 [FocusParam.ALERT_ISLAND_SECONDS] 秒后收起。
 */
internal data class IslandContent(
    val title: String,
    val text: String,
    val short: String,
    val right: String,
    val note: String? = null,
    val replies: List<String> = emptyList(),
    val progress: TodoProgress? = null,
    val float: Boolean = false,
    val timesOut: Boolean = false,
)

internal object FocusParam {
    const val EXTRA = "miui.focus.param"
    const val PICS = "miui.focus.pics"
    const val ACTIONS = "miui.focus.actions"

    /** 应用图标（圆形、彩色）在 [PICS] 里的键：焦点卡片左边、大岛小岛、息屏都用它，和小爱一样。 */
    const val PIC_APP = "miui.focus.pic_app_icon"

    /** 第 [index] 个回答按钮在 [ACTIONS] 里的键。 */
    fun actionKey(index: Int) = "miui.focus.action_reply$index"

    /** 大岛右边那句截到几个字（再接省略号），再长岛放不下。 */
    const val ISLAND_CHARS = 11

    /** 提醒的岛弹出来停几秒。 */
    const val ALERT_ISLAND_SECONDS = 5

    /** 常驻的实时通知：停下来等用户时岛自己展开。 */
    fun of(live: LiveStatus) =
        IslandContent(
            title = live.title,
            text = live.text,
            short = live.short,
            right = live.subject,
            note = live.note,
            replies = live.replies.map { it.label },
            progress = live.progress,
            float = live.kind.needsUser,
        )

    /**
     * 对话的提醒：标题是对话标题，[text] 是“已完成”“需要你确认：…”这类一句，[short]、[right] 是大岛左右两边（见 [IslandContent]）。 [silent]
     * 的不弹岛，只进通知栏。
     */
    fun alert(
        title: String,
        text: String,
        short: String,
        right: String,
        replies: List<String>,
        silent: Boolean,
    ) =
        IslandContent(
            title = title,
            text = text,
            short = short,
            right = right,
            replies = replies,
            float = !silent,
            timesOut = true,
        )

    /** [content] 的焦点参数 JSON，放进 [EXTRA]；按钮的键是 [actionKey]，顺序同 [IslandContent.replies]。 */
    fun build(content: IslandContent): String {
        val param = buildJsonObject {
            putJsonObject("param_v2") {
                put("protocol", 1)
                put("business", "zeta")
                put("updatable", true)
                put("ticker", content.text)
                put("enableFloat", content.float)
                put("islandFirstFloat", content.float)
                put("isShowNotification", true)
                put("aodTitle", content.short)
                put("aodPic", PIC_APP)
                put("outEffectSrc", "outer_glow")
                putJsonObject("iconTextInfo") {
                    put("title", content.title)
                    put("colorTitle", "#000000")
                    put("colorTitleDark", "#FFFFFF")
                    put("content", content.note?.let { "$it · ${content.text}" } ?: content.text)
                    putJsonObject("animIconInfo") {
                        put("type", 0)
                        put("src", PIC_APP)
                    }
                }
                content.progress?.let { p ->
                    putJsonObject("progressInfo") { put("progress", p.done * 100 / p.total) }
                }
                if (content.replies.isNotEmpty()) {
                    put(
                        "textButton",
                        buildJsonArray {
                            content.replies.forEachIndexed { i, label ->
                                add(
                                    buildJsonObject {
                                        put("type", 1)
                                        put("actionTitle", label)
                                        put("action", actionKey(i))
                                    },
                                )
                            }
                        },
                    )
                }
                put("param_island", island(content))
            }
        }
        return param.toString()
    }

    /** 岛（照小爱）：小岛只有应用图标；大岛左边应用图标加短词，右边是截短的 [IslandContent.right]。 */
    private fun island(content: IslandContent): JsonObject = buildJsonObject {
        put("islandProperty", 1)
        put("islandPriority", 2)
        if (content.timesOut) put("islandTimeout", ALERT_ISLAND_SECONDS)
        putJsonObject("bigIslandArea") {
            putJsonObject("imageTextInfoLeft") {
                put("type", 1)
                putJsonObject("picInfo") {
                    put("type", 1)
                    put("pic", PIC_APP)
                }
                putJsonObject("textInfo") { put("title", content.short) }
            }
            putJsonObject("imageTextInfoRight") {
                put("type", 2)
                putJsonObject("textInfo") { put("title", content.right.clipLine(ISLAND_CHARS)) }
            }
        }
        putJsonObject("smallIslandArea") {
            putJsonObject("picInfo") {
                put("type", 1)
                put("pic", PIC_APP)
            }
        }
    }
}
