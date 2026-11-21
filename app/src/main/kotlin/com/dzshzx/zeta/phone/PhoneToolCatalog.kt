package com.dzshzx.zeta.phone

import com.dzshzx.zeta.phone.tools.PhoneTool
import com.dzshzx.zeta.phone.tools.PhoneToolGroups
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 交给 Claude 的整份手机工具清单（MCP 的 tools/list）：屏幕这一套加上各工具组的（含浏览器、定时任务、系统应用工具）。
 * 每个工具的中文都只从这里取：工具权限页上的名字（[labels]），聊天里的步骤和顶部胶囊上这一步做了什么（[title]）。
 * 调用按名字分派，重名的只有一个调得到，所以名字不能重复（PhoneToolGroupsTest 核对）。
 */
internal object PhoneToolCatalog {
    val tools: List<PhoneTool>
        get() = ScreenToolDefs.tools + PhoneToolGroups.all.flatMap { it.tools }

    val definitions: List<JsonElement>
        get() = tools.map { it.definition }

    /** 全部手机工具的名字和中文名字（设置里的工具权限页用）。 */
    val labels: List<Pair<String, String>>
        get() = tools.map { it.name to it.label }

    /** 这一步做了什么，一句中文（「点按「搜索」」「打开 Wi-Fi」）。[scene] 是胶囊才知道的现场，有它时说得更具体； 聊天里没有，传 null。不认识的名字返回 null。 */
    fun title(name: String, input: JsonObject, scene: CallScene? = null): String? =
        ScreenToolDefs.title(name, input, scene) ?: PhoneToolGroups.find(name)?.title(name, input)

    /** MCP initialize 交给 Claude 的说明：怎么用这一整套手机工具。 */
    const val INSTRUCTIONS =
        "These tools operate the Android phone this conversation runs on. " +
            "Use `screen` to read what is on the display: it lists the visible controls, each with an index and its centre. " +
            "Prefer acting on a control by its index (`node`), which stays valid until the next observation; " +
            "when you already know a control's text or resource id, `match` / `id` find it on the live screen without calling `screen` first; " +
            "use x/y only when the control is not listed. " +
            "All coordinates are in screenshot pixels, the size reported at the top of every observation. " +
            "Every action returns a fresh list of controls, so you rarely need to call `screen` again after acting. " +
            "Use `scroll` to page through lists: it reports whether the content actually moved and whether the end was reached. " +
            "Use `wait` after something slow (loading, a new page, an app starting) instead of polling with `screen`. " +
            "Apps you open with `open_app` run on a hidden background screen by default, so the user can keep using their phone meanwhile " +
            "(they can watch it live in Zeta); `open_app` also opens links, deep links and settings pages (`uri`, `action`). " +
            "Every tool acts on the current screen, which is the one you last opened an app on; " +
            "each observation starts by naming it. The background screen has no home screen or status bar: switch apps with `open_app`, go back with `press_key` back. " +
            "Call `show_to_user` to move the app from the background screen to the main screen when the user must act themselves " +
            "(payment, password, captcha, a login or permission prompt meant for them) or when they want to see the result in that app; after it you are on the main screen. " +
            "Use `display: main` with `open_app` only when the user wants it done in front of them. " +
            "Ask for `screenshot: true` only when the list is not enough (images, games, canvas-drawn pages); it costs far more than the list. " +
            "Many system apps can do things themselves when asked: check `app_tools` before driving a system app's screen. " +
            "`shell` runs a command as root on the Android side, outside the Linux environment your Bash tool lives in: " +
            "use it for pm, am, cmd, settings, dumpsys, getprop, logcat and the like. " +
            "If something needs the user's own hands, bring it to them with `show_to_user` and tell them what to do."
}

/**
 * 胶囊比聊天多知道的现场：当前操作的是不是后台屏幕（[onAgentDisplay]），后台屏幕上最近打开的应用（[agentDisplayApp]），
 * 控件编号对应的控件（[control]，这次调用要用的那批编号）。
 */
internal class CallScene(
    val onAgentDisplay: Boolean,
    val agentDisplayApp: String?,
    val control: (Int) -> SceneControl?,
)

/** 编号对应的控件：上面的字（[label]，密码框没有）、是不是密码框。 */
internal class SceneControl(val label: String?, val password: Boolean)
