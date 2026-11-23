// Package redact 给诊断包里的文字打码：token、密码这类值换成 Mask。纯函数，规则同手机版的 Redact.kt（两边的用例一样）。
//
// 按模式认：
//
//   - 私钥块（-----BEGIN … PRIVATE KEY----- 到对应的 END，没有 END 的打到末尾）；
//   - Authorization、Proxy-Authorization、Cookie、Set-Cookie 后面的值（保留 Bearer、Basic 这类方案名）；
//   - 网址里的密码（https://用户:密码@主机）；
//   - 名字像机密的键值对和命令行参数：名字（去掉大小写、-、_、.）以 token、secret、password、apikey、accesskey、cookie
//     等结尾的，=、: 后面的值打码。JSON、环境变量、YAML、网址查询串、转义过的 JSON 都认；input_tokens 这类计数（以 tokens
//     结尾）不算；值是 true、false、null 或空的不打；
//   - 认得出样子的令牌，不管出现在哪：sk-ant-…、sk-…、Bearer …、GitHub、Slack、Google、AWS 的密钥，JWT。
//
// 打码后的文字再打一遍不变。宁可多打，不漏。Go 的正则没有反向引用，Kotlin 里「两边同一种引号」的写法在这里展开成几种引号的分支。
package redact

import (
	"regexp"
	"strings"
)

// Mask 是换上去的字。
const Mask = "[已打码]"

// Text 给 input 打码。
func Text(input string) string {
	s := privateKeys(input)
	s = replaceSubmatch(header, s, func(m []string) string {
		scheme := ""
		if m[4] != "" {
			scheme = m[4] + " "
		}
		return m[1] + m[2] + m[3] + scheme + Mask
	})
	s = urlPassword.ReplaceAllString(s, "${1}"+Mask+"@")
	s = replaceSubmatch(flag, s, func(m []string) string {
		quote, value := "", m[5]
		switch {
		case m[3] != "":
			quote, value = `"`, m[3]
		case m[4] != "":
			quote, value = "'", m[4]
		}
		if !SecretName(m[1]) || literal(value) {
			return m[0]
		}
		return m[1] + m[2] + quote + Mask + quote
	})
	s = keyValues(s)
	for _, sh := range shapes {
		s = sh.re.ReplaceAllString(s, sh.with)
	}
	return s
}

// keyValues 给名字像机密的键值对打码。名字不像的，值里还可能嵌着别的键值对（url: https://x/?token=…、JSON 字符串里转义过的
// JSON），对值再走一遍；值总比整段短，不会没完。
func keyValues(text string) string {
	return replaceSubmatch(keyValue, text, func(m []string) string {
		key, name := m[1], ""
		for _, n := range m[2:7] {
			if n != "" {
				name = n
				break
			}
		}
		sep, value := m[7], m[8]
		prefix := key + sep
		switch {
		case headers[normalize(name)] || literal(value):
			return m[0]
		case SecretName(name):
			return prefix + masked(value)
		default:
			return prefix + keyValues(value)
		}
	})
}

// SecretName：名字像不像放机密的，去掉大小写和分隔符后，以这些词结尾。
func SecretName(name string) bool {
	n := normalize(name)
	if n == "" || strings.HasSuffix(n, "tokens") {
		return false
	}
	for _, s := range secretSuffixes {
		if strings.HasSuffix(n, s) {
			return true
		}
	}
	return false
}

func normalize(name string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(name) {
		if r >= 'a' && r <= 'z' || r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// literal：不用打码的值：开关、空值、空串，或者已经打过码的。
func literal(value string) bool {
	bare := strings.Trim(strings.TrimPrefix(value, `\`), `"'\`)
	switch strings.ToLower(bare) {
	case "", "true", "false", "null", "none", "undefined":
		return true
	}
	return bare == Mask
}

// masked 保留值两边的引号（含转义过的引号），里面换成 Mask。
func masked(value string) string {
	switch {
	case strings.HasPrefix(value, `\"`):
		return `\"` + Mask + `\"`
	case strings.HasPrefix(value, `"`):
		return `"` + Mask + `"`
	case strings.HasPrefix(value, "'"):
		return "'" + Mask + "'"
	}
	return Mask
}

// privateKeys 把私钥块的内容换成 Mask：BEGIN 到同一种的 END，没有 END 的打到末尾。
func privateKeys(s string) string {
	var b strings.Builder
	for {
		loc := keyBegin.FindStringSubmatchIndex(s)
		if loc == nil {
			b.WriteString(s)
			return b.String()
		}
		label := s[loc[2]:loc[3]]
		begin := "-----BEGIN " + label + "PRIVATE KEY-----"
		end := "-----END " + label + "PRIVATE KEY-----"
		b.WriteString(s[:loc[0]])
		b.WriteString(begin + "\n" + Mask + "\n" + end)
		rest := s[loc[1]:]
		i := strings.Index(rest, end)
		if i < 0 {
			return b.String()
		}
		s = rest[i+len(end):]
	}
}

// replaceSubmatch 同 ReplaceAllStringFunc，但 fn 拿到的是各分组（没匹配上的分组是空串）。
func replaceSubmatch(re *regexp.Regexp, s string, fn func(m []string) string) string {
	var b strings.Builder
	last := 0
	for _, loc := range re.FindAllStringSubmatchIndex(s, -1) {
		m := make([]string, len(loc)/2)
		for i := range m {
			if loc[2*i] >= 0 {
				m[i] = s[loc[2*i]:loc[2*i+1]]
			}
		}
		b.WriteString(s[last:loc[0]])
		b.WriteString(fn(m))
		last = loc[1]
	}
	b.WriteString(s[last:])
	return b.String()
}

var secretSuffixes = []string{
	"token", "secret", "password", "passwd", "passphrase", "apikey", "accesskey", "secretkey", "privatekey",
	"sessionkey", "signingkey", "masterkey", "credential", "credentials", "cookie", "authorization", "auth",
}

// headers 由 header 处理过了，键值对那一步不再动。
var headers = map[string]bool{"authorization": true, "proxyauthorization": true, "cookie": true, "setcookie": true}

// quoted 是名字 name 两边同一种引号（转义过的 \"、\'，或 "、'）或没有引号的几种写法，没有引号的放最后（同 Kotlin 先试有引号的）。
// 每种写法里的名字各是一组。
func quoted(name string) string {
	return `\\"(` + name + `)\\"|\\'(` + name + `)\\'|"(` + name + `)"|'(` + name + `)'|(` + name + `)`
}

var (
	keyBegin = regexp.MustCompile(`-----BEGIN ([A-Z0-9 ]*)PRIVATE KEY-----`)

	// 1 键（带两边的引号），2 分隔符，3 值前面的引号，4 方案名（Bearer 等）；值到行尾或引号为止
	header = regexp.MustCompile(`(?i)(\\"(?:` + headerNames + `)\\"|\\'(?:` + headerNames + `)\\'|"(?:` + headerNames + `)"|'(?:` +
		headerNames + `)'|\b(?:` + headerNames + `))(\s*[:=]\s*)(\\?["']?)(?:(bearer|basic|token|digest|negotiate)\s+)?[^"'\\\r\n]+`)

	urlPassword = regexp.MustCompile(`([a-zA-Z][a-zA-Z0-9+.-]*://[^/\s:@"']+:)[^/\s@"']+@`)

	// 命令行参数：--api-key xxx、--token=xxx；-p 'xxx' 不算（名字不像机密）。值不以 - 开头，免得把下一个参数当成值吞掉。
	// 1 参数名，2 分隔，3 双引号里的值，4 单引号里的值，5 没引号的值
	flag = regexp.MustCompile(`(--?[A-Za-z][A-Za-z0-9-]*)(\s+|=)(?:"([^\s"'-][^\s"']*)"|'([^\s"'-][^\s"']*)'|([^\s"'-][^\s"']*))`)

	// 1 键（带两边的引号），2～6 各种引号写法里的键名，7 分隔符，8 值
	keyValue = regexp.MustCompile(`(` + quoted(`[A-Za-z_][A-Za-z0-9_.-]{0,63}`) + `)(\s*[:=]\s*)(` +
		`\\"(?:[^\\]|\\[^"])*?\\"|"(?:[^"\\\r\n]|\\.)*"|'[^'\r\n]*'|[^\s,;&"'{}\[\]<>()\\]+)`)

	shapes = []struct {
		re   *regexp.Regexp
		with string
	}{
		{regexp.MustCompile(`\bsk-ant-[A-Za-z0-9_-]{8,}`), "sk-ant-" + Mask},
		{regexp.MustCompile(`\bsk-[A-Za-z0-9_-]{16,}`), "sk-" + Mask},
		{regexp.MustCompile(`(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]{8,}`), "${1} " + Mask},
		{regexp.MustCompile(`\b(?:gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,})`), Mask},
		{regexp.MustCompile(`\bxox[abprs]-[A-Za-z0-9-]{10,}`), Mask},
		{regexp.MustCompile(`\bAIza[0-9A-Za-z_-]{30,}`), Mask},
		{regexp.MustCompile(`\b(?:AKIA|ASIA)[0-9A-Z]{16}\b`), Mask},
		{regexp.MustCompile(`\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*`), Mask},
	}
)

const headerNames = `proxy-authorization|authorization|set-cookie|cookie`
