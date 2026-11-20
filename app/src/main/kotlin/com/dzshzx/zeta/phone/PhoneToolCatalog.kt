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
    val tools: List<PhoneTool> get() = PhoneTools.SCREEN_TOOLS + PhoneToolGroups.all.flatMap { it.tools }

    val definitions: List<JsonElement> get() = tools.map { it.definition }

    /** 全部手机工具的名字和中文名字（设置里的工具权限页用）。 */
    val labels: List<Pair<String, String>> get() = tools.map { it.name to it.label }

    /**
     * 这一步做了什么，一句中文（「点按「搜索」」「打开 Wi-Fi」）。[scene] 是胶囊才知道的现场，有它时说得更具体；
     * 聊天里没有，传 null。不认识的名字返回 null。
     */
    fun title(name: String, input: JsonObject, scene: CallScene? = null): String? =
        PhoneTools.screenTitle(name, input, scene) ?: PhoneToolGroups.find(name)?.title(name, input)
}

/**
 * 胶囊比聊天多知道的现场：当前操作的是不是后台屏幕（[onBackground]），后台屏幕上最近打开的应用（[backgroundApp]），
 * 控件编号对应的控件（[control]，这次调用要用的那批编号）。
 */
internal class CallScene(val onBackground: Boolean, val backgroundApp: String?, val control: (Int) -> SceneControl?)

/** 编号对应的控件：上面的字（[label]，密码框没有）、是不是密码框。 */
internal class SceneControl(val label: String?, val password: Boolean)
