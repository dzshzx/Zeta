package claudecfg

import (
	"cmp"
	"context"
	"maps"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

// MCP 服务器的配置：~/.claude.json 的 mcpServers（user，所有项目）、它的 projects 里这个项目的 mcpServers（local）、
// cwd 往上各级的 .mcp.json（project）。增删经 claude mcp，不直接改 ~/.claude.json。

// McpServer 是给界面看的一个 MCP 服务器：密钥都只说有没有，不带值。
type McpServer struct {
	Name       string   `json:"name"`
	Scope      string   `json:"scope"`   // user、local、project
	Type       string   `json:"type"`    // http、sse、stdio、ws…
	URL        string   `json:"url"`     // 查询参数的值、用户名密码已经抹掉
	Command    string   `json:"command"` //
	Args       []string `json:"args"`    // 像是密钥的参数已经换成“…”
	EnvKeys    []string `json:"envKeys"`
	HeaderKeys []string `json:"headerKeys"` // Authorization 之外的请求头
	Bearer     bool     `json:"bearer"`
	AuthHeader bool     `json:"authHeader"`
	OAuth      bool     `json:"oauth"`
	ShadowedBy string   `json:"shadowedBy"` // 同名的服务器在优先级更高的范围里也有（local 高于 project 高于 user），这一个实际不会用到
}

// McpState 是 claude mcp list 报的一个服务器的状态。
type McpState struct {
	State string `json:"state"` // connected、partial、failed、auth、pending、rejected、disabled、unconfigured、unknown
	Issue string `json:"issue"` // 原因，网址换成了“…”
}

// McpConnector 是 Claude 账号的连接器（claude.ai 上配的）。
type McpConnector struct {
	Name  string   `json:"name"`
	State McpState `json:"state"`
}

// McpStatus 是健康检查的结果；Servers 的键是 McpStatusKey。
type McpStatus struct {
	Servers    map[string]McpState `json:"servers"`
	Connectors []McpConnector      `json:"connectors"`
}

// McpStatusKey 是一个服务器在 McpStatus.Servers 里的键。
func McpStatusKey(scope, name string) string { return scope + "/" + name }

// mcpScopes 按优先级排：同名时 local 生效，其次 project，最后 user。
var mcpScopes = []string{"local", "project", "user"}

// mcpEntry 是配置里的一个服务器，带原样的配置（可能有 Token），只在内存里用：连接、改 Token 时要原值，从不显示。
type mcpEntry struct {
	name, scope string
	config      *object
}

func (e mcpEntry) String() string { return "mcpEntry(" + e.scope + "/" + e.name + ")" }

func (e mcpEntry) typ() string {
	if t, ok := e.config.str("type"); ok {
		return t
	}
	if _, ok := e.config.get("command"); ok {
		return "stdio"
	}
	return "http"
}

var (
	serverNamePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
	secretArg         = regexp.MustCompile(`(?i)(key|token|secret|password|passwd|auth|bearer|credential)`)
	tokenLike         = regexp.MustCompile(`^(sk|ghp|gho|ghs|github_pat|xox[a-z]|AKIA)[-_A-Za-z0-9]{12,}$`)
	urlPattern        = regexp.MustCompile(`https?://\S+`)
	varRef            = regexp.MustCompile(`\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}`)
)

// ValidMcpName：字母、数字、下划线和连字符，最长 64。
func ValidMcpName(name string) bool { return serverNamePattern.MatchString(name) }

// mcpEntries 读配置里的服务器：cwd 是默认工作目录（空是家目录），本地配置按它或它所在的 git 仓库的根找。
func (s *Store) mcpEntries(cwd string) []mcpEntry {
	entries, _ := s.scanMcp(cwd)
	return entries
}

func (s *Store) scanMcp(cwd string) ([]mcpEntry, error) {
	cwd = cmp.Or(cwd, s.Home())
	claudeJSON, size, _ := readHead(s.Home()+"/.claude.json", 16<<20)
	if int64(len(claudeJSON)) < size {
		return nil, locales.Err("cfgClaudeJSONTooBig")
	}
	// 本地配置按项目路径存：cwd 本身，或者它所在的 git 仓库的根
	real, g := probeGit(cwd)
	keys := []string{cwd, real, g.root, canonicalRoot(g)}
	var files [][]byte
	for p := real; p != ""; p = parentDir(p) {
		if data, _, ok := readHead(strings.TrimSuffix(p, "/")+"/.mcp.json", 4<<20); ok {
			files = append(files, data)
		}
		if p == "/" {
			break
		}
	}
	return parseMcpEntries(claudeJSON, keys, files), nil
}

func parseMcpEntries(claudeJSON []byte, projectKeys []string, mcpJSON [][]byte) []mcpEntry {
	var found []mcpEntry
	has := func(scope, name string) bool {
		return slices.ContainsFunc(found, func(e mcpEntry) bool { return e.scope == scope && e.name == name })
	}
	add := func(servers *object, scope string) {
		if servers == nil {
			return
		}
		for _, name := range servers.keys {
			if cfg := servers.obj(name); cfg != nil && !has(scope, name) {
				found = append(found, mcpEntry{name, scope, cfg})
			}
		}
	}
	data, _ := parseObject(claudeJSON)
	if data != nil {
		add(data.obj("mcpServers"), "user")
		if projects := data.obj("projects"); projects != nil {
			for _, key := range projectKeys {
				if p := projects.obj(key); key != "" && p != nil {
					add(p.obj("mcpServers"), "local")
				}
			}
		}
	}
	for _, f := range mcpJSON {
		if o, _ := parseObject(f); o != nil {
			add(o.obj("mcpServers"), "project")
		}
	}
	return found
}

// McpServers 列出配置里的服务器。cwd 是默认工作目录（空是家目录）。
func (s *Store) McpServers(cwd string) ([]McpServer, error) {
	entries, err := s.scanMcp(cwd)
	if err != nil {
		return nil, err
	}
	out := make([]McpServer, len(entries))
	for i, e := range entries {
		out[i] = describeMcp(e, entries)
	}
	return out, nil
}

func describeMcp(e mcpEntry, all []mcpEntry) McpServer {
	cfg := e.config
	s := McpServer{Name: e.name, Scope: e.scope, Type: e.typ(), OAuth: cfg.obj("oauth") != nil, EnvKeys: []string{}, HeaderKeys: []string{}}
	u, _ := cfg.str("url")
	s.URL = maskedURL(u)
	s.Command, _ = cfg.str("command")
	s.Args = maskedArgs(argStrings(cfg))
	if env := cfg.obj("env"); env != nil {
		s.EnvKeys = slices.Sorted(slices.Values(env.keys))
	}
	if headers := cfg.obj("headers"); headers != nil {
		for _, k := range headers.keys {
			if !strings.EqualFold(k, "authorization") {
				s.HeaderKeys = append(s.HeaderKeys, k)
				continue
			}
			s.AuthHeader = true
			v, _ := headers.str(k)
			s.Bearer = len(strings.TrimSpace(v)) >= 7 && strings.EqualFold(strings.TrimSpace(v)[:7], "bearer ")
		}
		slices.Sort(s.HeaderKeys)
	}
	if w := mcpWinner(all, e.name); w != nil && w.scope != e.scope {
		s.ShadowedBy = w.scope
	}
	return s
}

// argStrings 是配置里的 args：字符串原样，别的值写成 JSON。
func argStrings(cfg *object) []string {
	list, _ := cfg.vals["args"].([]any)
	out := make([]string, 0, len(list))
	for _, a := range list {
		if s, ok := a.(string); ok {
			out = append(out, s)
		} else {
			out = append(out, compactJSON(a))
		}
	}
	return out
}

// mcpWinner 是同名时实际生效的那一个。
func mcpWinner(entries []mcpEntry, name string) *mcpEntry {
	for _, scope := range mcpScopes {
		for i := range entries {
			if entries[i].name == name && entries[i].scope == scope {
				return &entries[i]
			}
		}
	}
	return nil
}

// maskedURL：地址里的用户名密码和查询参数的值可能就是密钥，只留参数名。
func maskedURL(raw string) string {
	if raw == "" {
		return raw
	}
	before, _, _ := strings.Cut(raw, "?")
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return before
	}
	var b strings.Builder
	b.WriteString(u.Scheme + "://")
	if u.User != nil {
		b.WriteString("…@")
	}
	b.WriteString(u.Host + u.EscapedPath())
	if u.RawQuery != "" {
		parts := strings.Split(u.RawQuery, "&")
		for i, p := range parts {
			if k, _, ok := strings.Cut(p, "="); ok {
				parts[i] = k + "=…"
			}
		}
		b.WriteString("?" + strings.Join(parts, "&"))
	}
	return b.String()
}

// maskedArgs 把命令行参数里像是密钥的换成“…”：--token xxx 的下一个、--api-key=xxx 的值、长得像 Token 的整个参数。
func maskedArgs(args []string) []string {
	out := make([]string, 0, len(args))
	hideNext := false
	for _, a := range args {
		k, _, hasValue := strings.Cut(a, "=")
		switch {
		case hideNext:
			out = append(out, "…")
			hideNext = false
		case strings.HasPrefix(a, "-") && hasValue && secretArg.MatchString(k):
			out = append(out, k+"=…")
		case strings.HasPrefix(a, "-") && secretArg.MatchString(a):
			out = append(out, a)
			hideNext = true
		case tokenLike.MatchString(a):
			out = append(out, "…")
		default:
			out = append(out, a)
		}
	}
	return out
}

var statusPhrases = []struct{ phrase, state string }{
	{"Needs authentication", "auth"},
	{"tools fetch failed", "partial"},
	{"Connected", "connected"},
	{"Pending approval", "pending"},
	{"Rejected", "rejected"},
	{"Disabled", "disabled"},
	{"Not configured", "unconfigured"},
	{"Failed to connect", "failed"},
	{"Connection error", "failed"},
}

func phraseState(s string) (string, bool) {
	for _, p := range statusPhrases {
		if strings.Contains(s, p.phrase) {
			return p.state, true
		}
	}
	return "unknown", false
}

// splitStatus 切开 claude mcp list 一行里“名字: 目标 - 图标 状态 — 原因”的目标和状态部分。
func splitStatus(tail string) (target, status string, ok bool) {
	from := 0
	for {
		at := strings.Index(tail[from:], " - ")
		if at < 0 {
			return "", "", false
		}
		at += from
		rest := tail[at+3:]
		head, _, _ := strings.Cut(rest, " — ")
		if _, ok := phraseState(head); ok {
			return tail[:at], rest, true
		}
		from = at + 1
	}
}

// parseMcpStatus 解析 claude mcp list 的输出。它对同名的服务器只列生效的那一个：按显示的目标（地址或命令行）认出是哪个
// 范围的，认不出时归给优先级最高的那个。只取状态和原因，地址、命令行不往外带。
func parseMcpStatus(output string, entries []mcpEntry) McpStatus {
	var names []string
	for _, e := range entries {
		if !slices.Contains(names, e.name) {
			names = append(names, e.name)
		}
	}
	slices.SortStableFunc(names, func(a, b string) int { return cmp.Compare(len(b), len(a)) })
	st := McpStatus{Servers: map[string]McpState{}}
	for _, raw := range strings.Split(output, "\n") {
		line := strings.TrimRight(raw, " \t\r")
		if strings.HasPrefix(line, "MCP config diagnostics") {
			break
		}
		label := ""
		for _, n := range names {
			if strings.HasPrefix(line, n+": ") {
				label = n
				break
			}
		}
		connector := label == "" && strings.HasPrefix(line, "claude.ai ") && strings.Contains(line, ": ")
		if label == "" && !connector {
			continue
		}
		if connector {
			label, _, _ = strings.Cut(line, ": ")
		}
		target, status, ok := splitStatus(line[len(label)+2:])
		if !ok {
			continue
		}
		head, issue, hasIssue := strings.Cut(status, " — ")
		state := McpState{}
		state.State, _ = phraseState(head)
		if hasIssue {
			state.Issue = clip(urlPattern.ReplaceAllString(strings.TrimSpace(issue), "…"), 300)
		}
		if connector {
			st.Connectors = append(st.Connectors, McpConnector{label, state})
			continue
		}
		if owner := statusOwner(entries, label, target); owner != nil {
			st.Servers[McpStatusKey(owner.scope, owner.name)] = state
		}
	}
	return st
}

func statusOwner(entries []mcpEntry, name, target string) *mcpEntry {
	var candidates, matched []mcpEntry
	for _, e := range entries {
		if e.name != name {
			continue
		}
		candidates = append(candidates, e)
		u, _ := e.config.str("url")
		if mcpTarget(e) == target || (e.typ() != "stdio" && strings.HasPrefix(target, u)) {
			matched = append(matched, e)
		}
	}
	if len(matched) == 1 {
		return &matched[0]
	}
	return mcpWinner(candidates, name)
}

// mcpTarget 是 claude mcp list 显示一个服务器的样子。
func mcpTarget(e mcpEntry) string {
	if e.typ() != "stdio" {
		u, _ := e.config.str("url")
		return u + " (" + strings.ToUpper(e.typ()) + ")"
	}
	cmd, _ := e.config.str("command")
	return strings.Join(append([]string{cmd}, argStrings(e.config)...), " ")
}

// McpStatus 逐个连接做健康检查（claude mcp list），可能要十几秒；它会把配置里的 stdio 服务器都起一遍（和每次开对话时一样）。
func (s *Store) McpStatus(ctx context.Context, cwd string) (McpStatus, error) {
	cwd = cmp.Or(cwd, s.Home())
	entries, err := s.scanMcp(cwd)
	if err != nil {
		return McpStatus{}, err
	}
	text, err := s.claude(ctx, 2*time.Minute, cwd, []string{"mcp", "list"}, nil, nil)
	if err != nil {
		return McpStatus{}, err
	}
	return parseMcpStatus(text, entries), nil
}

// claudeScript 在 $1 里跑 claude，其余参数交给它；cwd 不在时在家目录里跑。
const claudeScript = `cd -- "$1" 2>/dev/null || cd; shift; exec claude "$@"`

// claude 经登录 shell 在 cwd 里跑 claude 的子命令，返回它的输出；secrets 里的值不会出现在报错里。
func (s *Store) claude(ctx context.Context, timeout time.Duration, cwd string, args []string, stdin []byte, secrets []string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	text, code, err := run(ctx, s.loginCommand(ctx, claudeScript, append([]string{cwd}, args...), nil), stdin)
	if err != nil {
		return "", err
	}
	if code != 0 {
		if line := lastLine(text); line != "" {
			return "", rawError(clip(mask(line, secrets), 300))
		}
		return "", locales.Err("cfgClaudeExit", "code", strconv.Itoa(code))
	}
	return text, nil
}

// mask 把文字里的密钥换成“…”（太短的不算，免得误伤）。
func mask(text string, secrets []string) string {
	s := slices.Clone(secrets)
	slices.SortFunc(s, func(a, b string) int { return cmp.Compare(len(b), len(a)) })
	for _, secret := range s {
		if len(secret) >= 4 {
			text = strings.ReplaceAll(text, secret, "…")
		}
	}
	return text
}

// secretsOf 是配置里可能是密钥的值：请求头、环境变量、长参数。
func secretsOf(cfg *object) []string {
	var out []string
	if h := cfg.obj("headers"); h != nil {
		for _, k := range h.keys {
			if v, ok := h.str(k); ok {
				out = append(out, v, strings.TrimPrefix(v, "Bearer "))
			}
		}
	}
	if env := cfg.obj("env"); env != nil {
		for _, k := range env.keys {
			if v, ok := env.str(k); ok {
				out = append(out, v)
			}
		}
	}
	for _, a := range stringItems(cfg.vals["args"]) {
		if len(a) >= 12 {
			out = append(out, a)
		}
	}
	return out
}

func (s *Store) addJSON(ctx context.Context, cwd, scope, name string, cfg *object) error {
	_, err := s.claude(ctx, defaultTimeout, cwd, []string{"mcp", "add-json", "-s", scope, name, "-"}, []byte(compactJSON(cfg)), secretsOf(cfg))
	return err
}

func (s *Store) addServer(ctx context.Context, name string, cfg *object) error {
	if !ValidMcpName(name) {
		return locales.Err("cfgMcpBadName")
	}
	entries, err := s.scanMcp(s.Home())
	if err != nil {
		return err
	}
	if slices.ContainsFunc(entries, func(e mcpEntry) bool { return e.name == name && e.scope == "user" }) {
		return locales.Err("cfgMcpExists", "name", name)
	}
	return s.addJSON(ctx, s.Home(), "user", name, cfg)
}

func bearerValue(token string) string {
	t := strings.TrimSpace(token)
	if len(t) >= 7 && strings.EqualFold(t[:7], "bearer ") {
		return t
	}
	return "Bearer " + t
}

// AddHTTPServer 加一个 HTTP 或 SSE（typ）服务器到用户配置（所有项目可用）。token 只经标准输入交给 claude mcp add-json。
func (s *Store) AddHTTPServer(ctx context.Context, name, typ, rawURL, token string) error {
	cfg := newObject()
	cfg.set("type", typ)
	cfg.set("url", rawURL)
	if strings.TrimSpace(token) != "" {
		h := newObject()
		h.set("Authorization", bearerValue(token))
		cfg.set("headers", h)
	}
	return s.addServer(ctx, name, cfg)
}

// AddStdioServer 加一个命令行（stdio）服务器到用户配置。env 的顺序按键排。
func (s *Store) AddStdioServer(ctx context.Context, name, command string, args []string, env map[string]string) error {
	cfg := newObject()
	cfg.set("type", "stdio")
	cfg.set("command", command)
	list := make([]any, len(args))
	for i, a := range args {
		list[i] = a
	}
	cfg.set("args", list)
	if len(env) > 0 {
		e := newObject()
		for _, k := range slices.Sorted(maps.Keys(env)) {
			e.set(k, env[k])
		}
		cfg.set("env", e)
	}
	return s.addServer(ctx, name, cfg)
}

// RemoveMcp 删掉一个服务器（claude mcp remove）。
func (s *Store) RemoveMcp(ctx context.Context, scope, name, cwd string) error {
	if !slices.Contains(mcpScopes, scope) {
		return locales.Err("cfgMcpBadScope")
	}
	_, err := s.claude(ctx, defaultTimeout, cmp.Or(cwd, s.Home()), []string{"mcp", "remove", "-s", scope, name}, nil, nil)
	return err
}

// entry 是配置里 scope/name 的原样（带 Token）。
func (s *Store) entry(scope, name, cwd string) (mcpEntry, error) {
	entries, err := s.scanMcp(cwd)
	if err != nil {
		return mcpEntry{}, err
	}
	for _, e := range entries {
		if e.name == name && e.scope == scope {
			return e, nil
		}
	}
	return mcpEntry{}, locales.Err("cfgMcpNotFound")
}

// SetMcpToken 换掉 Bearer Token；空串是去掉身份认证。经 claude mcp 删掉重加，重加失败时把原样放回去。
func (s *Store) SetMcpToken(ctx context.Context, scope, name, cwd, token string) error {
	if scope != "user" && scope != "local" {
		return locales.Err("cfgMcpNotEditable")
	}
	cwd = cmp.Or(cwd, s.Home())
	original, err := s.entry(scope, name, cwd)
	if err != nil {
		return err
	}
	if original.typ() == "stdio" {
		return locales.Err("cfgMcpStdioNoToken")
	}
	updated := withToken(original.config, strings.TrimSpace(token))
	if _, err := s.claude(ctx, defaultTimeout, cwd, []string{"mcp", "remove", "-s", scope, name}, nil, nil); err != nil {
		return err
	}
	if err := s.addJSON(ctx, cwd, scope, name, updated); err != nil {
		_ = s.addJSON(ctx, cwd, scope, name, original.config)
		return err
	}
	return nil
}

// withToken 换掉 Authorization 请求头（token 为空是去掉），别的请求头照旧；返回新的配置，原来的不动。
func withToken(cfg *object, token string) *object {
	out := cloneObject(cfg)
	headers := newObject()
	if h := cfg.obj("headers"); h != nil {
		headers = cloneObject(h)
	}
	for _, k := range slices.Clone(headers.keys) {
		if strings.EqualFold(k, "authorization") {
			headers.del(k)
		}
	}
	if strings.TrimSpace(token) != "" {
		headers.set("Authorization", bearerValue(token))
	}
	if headers.len() == 0 {
		out.del("headers")
	} else {
		out.set("headers", headers)
	}
	return out
}

// cloneObject 是一层的拷贝（下一层的值共用）。
func cloneObject(o *object) *object {
	out := newObject()
	for _, k := range o.keys {
		out.set(k, o.vals[k])
	}
	return out
}
