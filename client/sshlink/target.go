package sshlink

import (
	"net"
	"regexp"
	"strconv"
	"strings"
)

// Target 是用户名、主机、端口都写明的地址：用户@主机，可以带端口（用户@主机:端口、用户@[IPv6]:端口），前面可以带 ssh://。
type Target struct {
	User string
	Host string
	Port int
}

var (
	userChars = regexp.MustCompile(`^[A-Za-z0-9._][A-Za-z0-9._-]*$`)
	hostChars = regexp.MustCompile(`^[A-Za-z0-9._%:-]+$`)
)

// ParseTarget 认 text 是不是写明用户的地址；不是时 ok 为 false。
func ParseTarget(text string) (t Target, ok bool) {
	s := strings.TrimSuffix(strings.TrimPrefix(strings.TrimSpace(text), "ssh://"), "/")
	at := strings.LastIndex(s, "@")
	if at <= 0 || !userChars.MatchString(s[:at]) {
		return Target{}, false
	}
	host, port, ok := splitHostPort(s[at+1:])
	if !ok || port < 1 || port > 65535 || host == "" || strings.HasPrefix(host, "-") || !hostChars.MatchString(host) {
		return Target{}, false
	}
	return Target{User: s[:at], Host: host, Port: port}, true
}

// splitHostPort 拆开 主机、主机:端口、[IPv6]、[IPv6]:端口；没写端口是 22。
func splitHostPort(rest string) (host string, port int, ok bool) {
	if strings.HasPrefix(rest, "[") {
		end := strings.Index(rest, "]")
		if end < 0 {
			return "", 0, false
		}
		host, after := rest[1:end], rest[end+1:]
		if after == "" {
			return host, 22, true
		}
		port, err := strconv.Atoi(strings.TrimPrefix(after, ":"))
		return host, port, err == nil
	}
	if strings.Count(rest, ":") == 1 {
		host, p, _ := strings.Cut(rest, ":")
		port, err := strconv.Atoi(p)
		return host, port, err == nil
	}
	return rest, 22, true
}

// String 是给人看、也是存进设置的写法：端口是 22 时不写，IPv6 加方括号。
func (t Target) String() string {
	host := t.Host
	if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	if t.Port == 22 {
		return t.User + "@" + host
	}
	return t.User + "@" + host + ":" + strconv.Itoa(t.Port)
}

// Addr 是拨号用的 主机:端口。
func (t Target) Addr() string { return net.JoinHostPort(t.Host, strconv.Itoa(t.Port)) }

// Alias 把 text 当成 ~/.ssh/config 里的别名或不带用户、端口的主机名（交给 ssh -G 解析）；不像时 ok 为 false。
func Alias(text string) (alias string, ok bool) {
	s := strings.TrimSpace(text)
	if !validOpenSSH(s) || strings.ContainsAny(s, "@:/") {
		return "", false
	}
	return s, true
}

// validOpenSSH：OpenSSH 认的目的地，不以 - 开头，没有空白、引号、反斜杠和控制字符。
func validOpenSSH(target string) bool {
	if target == "" || strings.HasPrefix(target, "-") {
		return false
	}
	for _, r := range target {
		if r <= ' ' || r == '\'' || r == '"' || r == '\\' || r == 0x7f || isSpace(r) {
			return false
		}
	}
	return true
}

func isSpace(r rune) bool {
	return strings.ContainsRune("\u0085      　", r) || (r >= 0x2000 && r <= 0x200a)
}

// TargetFor 把用户输入的地址 text 换成存进设置的写法：写明用户的按 Target.String，别名原样；都不像时 ok 为 false。
func TargetFor(text string) (target string, ok bool) {
	if t, ok := ParseTarget(text); ok {
		return t.String(), true
	}
	return Alias(text)
}

// DisplayTarget 是给人看的地址：写明用户的按直连的写法（不带 ssh://），别名原样。
func DisplayTarget(target string) string {
	if t, ok := ParseTarget(target); ok {
		return t.String()
	}
	return target
}
