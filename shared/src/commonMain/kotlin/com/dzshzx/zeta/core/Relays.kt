package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.i18n.CoreStrings
import java.net.URI
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 一台主机上的一个中转（claude-switch 的非账号目标，docs/core.md「账号与额度」）：一份 Claude Code 设置，地址、读密钥的 apiKeyHelper、
 * 默认模型。换过去同换号（accounts.switch）。密钥的值不回传，这里只有配没配、能不能改。
 */
@Immutable
data class ClaudeRelay(
    val name: String,
    val baseUrl: String,
    /** 这个中转上新对话的默认模型；没设时为空串。盖过这台 settings.json 的默认模型，对话里选的又盖过它。 */
    val model: String,
    val hasKey: Boolean,
    /** Zeta 能改它的密钥：用户自己放的、密钥不是从家目录里的文件读的中转只读。 */
    val keyEditable: Boolean,
    val current: Boolean,
)

/** 中转报的一个模型（accounts.relay_models）。 */
@Immutable
data class RelayModel(val id: String, val displayName: String) {
    /** 列表里显示的名字：有显示名用显示名，没有用 id。 */
    val title: String
        get() = displayName.ifBlank { id }
}

internal fun relayModel(item: JsonElement): RelayModel? {
    val o = item as? JsonObject ?: return null
    val id = o.str("id")?.ifBlank { null } ?: return null
    return RelayModel(id, o.str("displayName").orEmpty())
}

/**
 * 中转编辑页的草稿：保存前不改列表。[key] 是这次填的密钥，修改时为空表示不改；[model] 为空是不设默认模型。 这里的校验是客户端先做的一遍（同核心的 helper/accounts
 * validRelay），话用核心文字表里的同一句，核心还会再核对一次。
 */
@Immutable
data class RelayDraft(
    val name: String = "",
    val baseUrl: String = "",
    val key: String = "",
    val model: String = "",
) {
    /**
     * 草稿哪里不对，没问题时为 null。[create] 是添加；[taken] 是这台上已有的账号名和中转名（添加时不能重名）； [keyEditable] 为假时不看密钥（密钥框禁用）。
     */
    fun problem(
        create: Boolean,
        taken: Collection<String>,
        keyEditable: Boolean = true,
        harness: String = Harnesses.CLAUDE,
    ): String? {
        val name = name.trim()
        val isAgy = Harnesses.normalize(harness) == Harnesses.AGY
        val reserved = if (isAgy) name == Harnesses.AGY else name == BUILTIN
        val badUrl =
            if (isAgy) baseUrl.isNotBlank() && !validBaseUrl(baseUrl) else !validBaseUrl(baseUrl)
        return when {
            create && (!NAME.matches(name) || reserved) -> CoreStrings.errRelayName()
            create && name in taken -> CoreStrings.errRelayNameTaken()
            badUrl -> CoreStrings.errRelayURL()
            keyEditable && key.any { it.isWhitespace() } -> CoreStrings.errRelayKey()
            create && key.isBlank() -> CoreStrings.errRelayKeyRequired()
            model.trim().length > MAX_MODEL || model.trim().any { it.isWhitespace() } ->
                CoreStrings.errRelayModel()
            else -> null
        }
    }

    companion object {
        /** claude-switch 的名字写法：小写字母、数字和连字符，字母或数字开头，至多 32 个字符（account.Valid）。 */
        private val NAME = Regex("^[a-z0-9][a-z0-9-]{0,31}$")

        /** claude-switch 固定给默认配置目录那个号的名字，中转不能叫它。 */
        private const val BUILTIN = "claude"

        private const val MAX_MODEL = 200

        /** 修改已有的 [relay]：名字只读，密钥留空表示不改。 */
        fun of(relay: ClaudeRelay): RelayDraft =
            RelayDraft(name = relay.name, baseUrl = relay.baseUrl, model = relay.model)

        /** http(s)、有主机、不带用户名密码（同核心的 validBaseURL）。 */
        fun validBaseUrl(raw: String): Boolean {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
            return (uri.scheme == "http" || uri.scheme == "https") &&
                !uri.rawAuthority.isNullOrEmpty() &&
                uri.rawUserInfo == null
        }
    }
}
