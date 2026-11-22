package com.dzshzx.zeta.core.diag

/**
 * 导出诊断包前给机密打码。纯函数，按模式认：
 *
 * - 私钥块（`-----BEGIN … PRIVATE KEY-----` 到对应的 END，没有 END 的打到末尾）；
 * - `Authorization`、`Proxy-Authorization`、`Cookie`、`Set-Cookie` 后面的值（保留 Bearer、Basic 这类方案名）；
 * - 网址里的密码（`https://用户:密码@主机`）；
 * - 名字像机密的键值对和命令行参数：名字（去掉大小写、`-`、`_`、`.`）以 token、secret、password、apikey、 accesskey、cookie
 *   等结尾的，`=`、`:` 后面的值打码。JSON、环境变量、YAML、网址查询串、转义过的 JSON 都认； `input_tokens` 这类计数（以 tokens 结尾）不算；值是
 *   true、false、null 或空的不打。
 * - 认得出样子的令牌，不管出现在哪：`sk-ant-…`、`sk-…`、`Bearer …`、GitHub、Slack、Google、AWS 的密钥，JWT。
 *
 * 打码后的文字再打一遍不变。宁可多打，不漏。
 */
object Redact {
    const val MASK = "[已打码]"

    fun text(input: String): String {
        var s = input
        s =
            PRIVATE_KEY.replace(s) { m ->
                "-----BEGIN ${m.groupValues[1]}PRIVATE KEY-----\n$MASK\n-----END ${m.groupValues[1]}PRIVATE KEY-----"
            }
        s =
            HEADER.replace(s) { m ->
                val scheme = m.groupValues[5].takeIf { it.isNotEmpty() }?.let { "$it " }.orEmpty()
                m.groupValues[1] +
                    m.groupValues[2] +
                    m.groupValues[1] +
                    m.groupValues[3] +
                    m.groupValues[4] +
                    scheme +
                    MASK
            }
        s = URL_PASSWORD.replace(s) { m -> m.groupValues[1] + MASK + "@" }
        s =
            FLAG.replace(s) { m ->
                if (!secretName(m.groupValues[1]) || literal(m.groupValues[4])) m.value
                else
                    m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + MASK + m.groupValues[3]
            }
        s = keyValues(s)
        for ((pattern, replacement) in SHAPES) s = pattern.replace(s, replacement)
        return s
    }

    /**
     * 名字像机密的键值对打码。名字不像的，值里还可能嵌着别的键值对（`url: https://x/?token=…`、 JSON 字符串里转义过的
     * JSON），对值再走一遍；值总比整段短，不会没完。
     */
    private fun keyValues(text: String): String =
        KEY_VALUE.replace(text) { m ->
            val name = m.groupValues[2]
            val value = m.groupValues[4]
            val prefix = m.groupValues[1] + name + m.groupValues[1] + m.groupValues[3]
            when {
                normalize(name) in HEADERS || literal(value) -> m.value
                secretName(name) -> prefix + masked(value)
                else -> prefix + keyValues(value)
            }
        }

    /** 名字像不像放机密的：去掉大小写和分隔符后，以这些词结尾。 */
    fun secretName(name: String): Boolean {
        val n = normalize(name)
        if (n.isEmpty() || n.endsWith("tokens")) return false
        return SECRET_SUFFIXES.any { n.endsWith(it) }
    }

    private fun normalize(name: String): String =
        name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /** 不用打码的值：开关、空值、空串，或者已经打过码的。 */
    private fun literal(value: String): Boolean {
        val bare = value.removePrefix("\\").trim('"', '\'', '\\')
        return bare.isEmpty() ||
            bare == MASK ||
            bare.lowercase() in setOf("true", "false", "null", "none", "undefined")
    }

    /** 保留值两边的引号（含转义过的引号），里面换成 [MASK]。 */
    private fun masked(value: String): String =
        when {
            value.startsWith("\\\"") -> "\\\"$MASK\\\""
            value.startsWith("\"") -> "\"$MASK\""
            value.startsWith("'") -> "'$MASK'"
            else -> MASK
        }

    private val SECRET_SUFFIXES =
        listOf(
            "token",
            "secret",
            "password",
            "passwd",
            "passphrase",
            "apikey",
            "accesskey",
            "secretkey",
            "privatekey",
            "sessionkey",
            "signingkey",
            "masterkey",
            "credential",
            "credentials",
            "cookie",
            "authorization",
            "auth",
        )

    /** 这几个由 [HEADER] 处理过了，键值对那一步不再动。 */
    private val HEADERS = setOf("authorization", "proxyauthorization", "cookie", "setcookie")

    private val PRIVATE_KEY =
        Regex(
            "-----BEGIN ([A-Z0-9 ]*)PRIVATE KEY-----[\\s\\S]*?(?:-----END \\1PRIVATE KEY-----|\\z)",
        )

    // 1 键名两边的引号，2 键名，3 分隔符，4 值前面的引号，5 方案名（Bearer 等）；值到行尾或引号为止
    private val HEADER =
        Regex(
            "(?i)(\\\\?[\"']?)\\b(proxy-authorization|authorization|set-cookie|cookie)\\1(\\s*[:=]\\s*)(\\\\?[\"']?)" +
                "(?:(bearer|basic|token|digest|negotiate)\\s+)?[^\"'\\\\\\r\\n]+",
        )

    private val URL_PASSWORD = Regex("([a-zA-Z][a-zA-Z0-9+.-]*://[^/\\s:@\"']+:)[^/\\s@\"']+@")

    // 命令行参数：--api-key xxx、--token=xxx；-p 'xxx' 不算（名字不像机密）。值不以 - 开头，免得把下一个参数当成值吞掉
    private val FLAG = Regex("(--?[A-Za-z][A-Za-z0-9-]*)(\\s+|=)([\"']?)([^\\s\"'-][^\\s\"']*)\\3")

    // 1 键名两边的引号（可以是转义过的 \"），2 键名，3 分隔符，4 值
    private val KEY_VALUE =
        Regex(
            "(\\\\?[\"']|)([A-Za-z_][A-Za-z0-9_.-]{0,63})\\1(\\s*[:=]\\s*)" +
                "(\\\\\"(?:[^\\\\]|\\\\[^\"])*?\\\\\"|\"(?:[^\"\\\\\\r\\n]|\\\\.)*\"|'[^'\\r\\n]*'|[^\\s,;&\"'{}\\[\\]<>()\\\\]+)",
        )

    private val SHAPES: List<Pair<Regex, String>> =
        listOf(
            Regex("\\bsk-ant-[A-Za-z0-9_-]{8,}") to "sk-ant-$MASK",
            Regex("\\bsk-[A-Za-z0-9_-]{16,}") to "sk-$MASK",
            Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}") to "$1 $MASK",
            Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,})") to MASK,
            Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}") to MASK,
            Regex("\\bAIza[0-9A-Za-z_-]{30,}") to MASK,
            Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b") to MASK,
            Regex("\\beyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*") to MASK,
        )
}
