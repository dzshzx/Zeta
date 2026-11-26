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

/** 岛和焦点卡片上显示什么。[short] 是大岛左边图标旁的短词，[float] 时岛自己展开，[islandSeconds] 不为 null 时岛停这么久后收起。 */
internal data class IslandContent(
    val title: String,
    val text: String,
    val short: String,
    val note: String? = null,
    val replies: List<String> = emptyList(),
    val progress: TodoProgress? = null,
    val float: Boolean = false,
    val islandSeconds: Int? = null,
)

/**
 * 一条通知的焦点参数：[json] 放进 [FocusParam.EXTRA]，[actions] 是按钮在 [FocusParam.ACTIONS] 里的键，顺序同
 * [IslandContent.replies]。
 */
internal data class Focus(val json: String, val actions: List<String>)

internal object FocusParam {
    const val EXTRA = "miui.focus.param"
    const val PICS = "miui.focus.pics"
    const val ACTIONS = "miui.focus.actions"

    /** 应用图标（圆形、彩色）在 [PICS] 里的键：焦点卡片左边、大岛小岛、息屏都用它，和小爱一样。 */
    const val PIC_APP = "miui.focus.pic_app_icon"

    /** 第 [index] 个回答按钮在 [ACTIONS] 里的键。 */
    fun actionKey(index: Int) = "miui.focus.action_reply$index"

    /** 大岛右边那句最多几个字，再长岛放不下。 */
    const val ISLAND_CHARS = 12

    /** 大岛左边的短词最多几个字。 */
    const val SHORT_CHARS = 6

    /** 提醒的岛弹出来停几秒。 */
    const val ALERT_ISLAND_SECONDS = 5

    /** 常驻的实时通知：停下来等用户时岛自己展开。 */
    fun of(live: LiveStatus) =
        IslandContent(
            title = live.title,
            text = live.text,
            short = live.short,
            note = live.note,
            replies = live.replies.map { it.label },
            progress = live.progress,
            float = live.kind.needsUser,
        )

    /**
     * 对话的提醒：标题是对话标题，[text] 是“已完成”“出错了”“需要你确认：…”这类一句，[short] 是大岛左边的短词（等你时的“待确认”）， 不给时 [text]
     * 够短就用它、否则只有图标。[silent] 的不弹岛，只进通知栏。
     */
    fun alert(title: String, text: String, short: String?, replies: List<String>, silent: Boolean) =
        IslandContent(
            title = title,
            text = text,
            short = short ?: text.takeIf { it.length <= SHORT_CHARS }.orEmpty(),
            replies = replies,
            float = !silent,
            islandSeconds = ALERT_ISLAND_SECONDS,
        )

    fun build(content: IslandContent): Focus {
        val actions = content.replies.indices.map(::actionKey)
        val param = buildJsonObject {
            putJsonObject("param_v2") {
                put("protocol", 1)
                put("business", "zeta")
                put("updatable", true)
                put("ticker", content.text)
                put("enableFloat", content.float)
                put("islandFirstFloat", content.float)
                put("isShowNotification", true)
                put("aodTitle", content.short.ifEmpty { content.text.clip(ISLAND_CHARS) })
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
                                        put("action", actions[i])
                                    },
                                )
                            }
                        },
                    )
                }
                put("param_island", island(content))
            }
        }
        return Focus(param.toString(), actions)
    }

    /** 岛（照小爱）：小岛只有应用图标；大岛左边应用图标加短词，右边是截短的那一句。 */
    private fun island(content: IslandContent): JsonObject = buildJsonObject {
        put("islandProperty", 1)
        put("islandPriority", 2)
        content.islandSeconds?.let { put("islandTimeout", it) }
        putJsonObject("bigIslandArea") {
            putJsonObject("imageTextInfoLeft") {
                put("type", 1)
                putJsonObject("picInfo") {
                    put("type", 1)
                    put("pic", PIC_APP)
                }
                putJsonObject("textInfo") { put("title", content.short) }
            }
            // 右边不重复左边的短词：短词就是那一句时（“已完成”）放对话标题；“需要你确认：建文件”这类只放冒号后面的
            val right =
                if (content.short == content.text) content.title
                else content.text.substringAfter('：')
            putJsonObject("imageTextInfoRight") {
                put("type", 2)
                putJsonObject("textInfo") { put("title", right.clip(ISLAND_CHARS)) }
            }
        }
        putJsonObject("smallIslandArea") {
            putJsonObject("picInfo") {
                put("type", 1)
                put("pic", PIC_APP)
            }
        }
    }

    private fun String.clip(max: Int): String {
        val line = lineSequence().firstOrNull().orEmpty().trim()
        return if (line.length <= max) line else line.take(max - 1) + "…"
    }
}
