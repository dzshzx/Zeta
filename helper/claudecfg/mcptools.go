package claudecfg

import (
	"bufio"
	"bytes"
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 直接按 MCP 协议连上服务器要工具清单（initialize → tools/list）：Claude Code 本身不提供这个查询。HTTP、SSE 从这台主机连
// （和 Claude Code 连的是同一个地址），stdio 经登录 shell 起进程。用 OAuth 登录的取不到（Token 在 Claude Code 自己的凭据里）。

// McpTool 是 MCP 服务器的一个工具。
type McpTool struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}

const mcpProtocol = "2025-06-18"

var initializeParams = map[string]any{
	"protocolVersion": mcpProtocol,
	"capabilities":    map[string]any{},
	"clientInfo":      map[string]any{"name": "zeta", "version": "1"},
}

func rpcRequest(id int, method string, params any) []byte {
	if params == nil {
		params = map[string]any{}
	}
	b, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params})
	return b
}

func rpcNotification(method string) []byte {
	b, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "method": method})
	return b
}

type rpcMessage struct {
	ID     *json.RawMessage `json:"id"`
	Method string           `json:"method"`
	Result json.RawMessage  `json:"result"`
	Error  *struct {
		Message string `json:"message"`
	} `json:"error"`
}

// rpcReply 在一条消息（或一批）里找 id 的回应：找到返回 result，是错误时报错，找不到 ok 为 false。服务器发来的请求、通知不算。
func rpcReply(data []byte, id int) (json.RawMessage, bool, error) {
	var batch []rpcMessage
	data = bytes.TrimSpace(data)
	if len(data) > 0 && data[0] == '[' {
		if json.Unmarshal(data, &batch) != nil {
			return nil, false, nil
		}
	} else {
		var one rpcMessage
		if json.Unmarshal(data, &one) != nil {
			return nil, false, nil
		}
		batch = []rpcMessage{one}
	}
	want := fmt.Sprint(id)
	for _, m := range batch {
		if m.Method != "" || m.ID == nil || strings.TrimSpace(string(*m.ID)) != want {
			continue
		}
		if m.Error != nil {
			return nil, true, fail("%s", clip(m.Error.Message, 300))
		}
		if len(m.Result) == 0 {
			return json.RawMessage("{}"), true, nil
		}
		return m.Result, true, nil
	}
	return nil, false, nil
}

// listTools 分页取完 tools/list（最多 20 页、2000 个）。call 发一个请求、等它的回应。
func listTools(call func(method string, params any) (json.RawMessage, error)) ([]McpTool, error) {
	var tools []McpTool
	var cursor string
	for range 20 {
		var params any
		if cursor != "" {
			params = map[string]any{"cursor": cursor}
		}
		raw, err := call("tools/list", params)
		if err != nil {
			return nil, err
		}
		var page struct {
			Tools []struct {
				Name        string `json:"name"`
				Description string `json:"description"`
			} `json:"tools"`
			NextCursor string `json:"nextCursor"`
		}
		_ = json.Unmarshal(raw, &page)
		for _, t := range page.Tools {
			tools = append(tools, McpTool{t.Name, clip(t.Description, 500)})
		}
		cursor = page.NextCursor
		if cursor == "" || len(tools) > 2000 {
			break
		}
	}
	return tools, nil
}

// McpTools 连上服务器 scope/name 取工具清单。cwd 是默认工作目录（空是家目录）。
func (s *Store) McpTools(ctx context.Context, scope, name, cwd string) ([]McpTool, error) {
	cwd = cmp.Or(cwd, s.Home())
	e, err := s.entry(scope, name, cwd)
	if err != nil {
		return nil, err
	}
	if e.config.obj("oauth") != nil {
		return nil, fail("这个服务器用 OAuth 登录，Zeta 取不到它的工具")
	}
	values, err := s.envValues(ctx, referencedVars(e.config))
	if err != nil {
		return nil, err
	}
	cfg := expandAll(e.config, values).(*object)
	secrets := secretsOf(cfg)
	for _, v := range values {
		secrets = append(secrets, v)
	}
	var tools []McpTool
	switch e.typ() {
	case "http", "sse":
		u, _ := cfg.str("url")
		if u == "" {
			return nil, fail("配置里没有地址")
		}
		headers := map[string]string{}
		if h := cfg.obj("headers"); h != nil {
			for _, k := range h.keys {
				if v, ok := h.str(k); ok {
					headers[k] = v
				}
			}
		}
		hc := &mcpHTTP{ctx: ctx, url: u, headers: headers}
		if e.typ() == "sse" {
			tools, err = hc.toolsSSE()
		} else {
			tools, err = hc.tools()
		}
	case "stdio":
		tools, err = s.stdioTools(ctx, cfg, cwd, secrets)
	default:
		return nil, fail("这种类型（%s）的服务器看不了工具", e.typ())
	}
	if err != nil {
		var cfgErr *Error
		if errors.As(err, &cfgErr) {
			return nil, fail("%s", mask(cfgErr.msg, secrets))
		}
		return nil, err
	}
	if tools == nil {
		tools = []McpTool{}
	}
	return tools, nil
}

// referencedVars 是配置里 ${VAR}、${VAR:-默认值} 引用到的变量名。
func referencedVars(cfg *object) []string {
	seen := map[string]bool{}
	var out []string
	var scan func(v any)
	scan = func(v any) {
		switch v := v.(type) {
		case string:
			for _, m := range varRef.FindAllStringSubmatch(v, -1) {
				if !seen[m[1]] {
					seen[m[1]] = true
					out = append(out, m[1])
				}
			}
		case []any:
			for _, x := range v {
				scan(x)
			}
		case *object:
			for _, k := range v.keys {
				scan(v.vals[k])
			}
		}
	}
	for _, k := range []string{"url", "headers", "command", "args", "env"} {
		scan(cfg.vals[k])
	}
	return out
}

// expandAll 把配置里的字符串都展开：变量没设时用默认值，没有默认值就是空串（和 Claude Code 一样）。values 只放设了的变量。
func expandAll(v any, values map[string]string) any {
	switch v := v.(type) {
	case string:
		return varRef.ReplaceAllStringFunc(v, func(m string) string {
			sub := varRef.FindStringSubmatch(m)
			if val, ok := values[sub[1]]; ok {
				return val
			}
			return sub[2]
		})
	case []any:
		out := make([]any, len(v))
		for i, x := range v {
			out[i] = expandAll(x, values)
		}
		return out
	case *object:
		out := newObject()
		for _, k := range v.keys {
			out.set(k, expandAll(v.vals[k], values))
		}
		return out
	}
	return v
}

// envValuesScript 输出登录环境里设了的变量：先是记号 @zeta（登录配置往标准输出打的字在它前面），再是名字和值，各以 NUL 结尾。
// 参数是变量名。
const envValuesScript = `printf '\n@zeta\0'; for v; do if [ -n "${!v+x}" ]; then printf '%s\0%s\0' "$v" "${!v}"; fi; done`

// envValues 取登录环境里这几个变量的值（MCP 配置里写了 ${VAR} 时要用），没设的不放。
func (s *Store) envValues(ctx context.Context, names []string) (map[string]string, error) {
	values := map[string]string{}
	if len(names) == 0 {
		return values, nil
	}
	ctx, cancel := context.WithTimeout(ctx, defaultTimeout)
	defer cancel()
	cmd := s.loginCommand(ctx, envValuesScript, names, nil)
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err != nil {
		return nil, fail("读不了登录环境里的变量")
	}
	_, body, ok := strings.Cut(out.String(), "\n@zeta\x00")
	if !ok {
		return nil, fail("读不了登录环境里的变量")
	}
	fields := strings.Split(body, "\x00")
	for i := 0; i+1 < len(fields); i += 2 {
		values[fields[i]] = fields[i+1]
	}
	return values, nil
}

var envName = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

// stdioScript 在 $1 里起 stdio 的 MCP 服务器，其余参数是命令和它的参数；cwd 不在时在家目录里起。配置里的环境变量经
// ZETA_MCP_ENV_<序号>=名字=值 带进来，读完登录配置以后再设上（和 Claude Code 一样压过登录环境里的），不放命令行。
const stdioScript = `for e in "${!ZETA_MCP_ENV_@}"; do export "${!e}"; unset "$e"; done
cd -- "$1" 2>/dev/null || cd; shift; exec "$@"`

// stdioTools 经登录 shell 起 stdio 服务器（配置里的环境变量另外加上），按行收发 JSON-RPC。
func (s *Store) stdioTools(ctx context.Context, cfg *object, cwd string, secrets []string) ([]McpTool, error) {
	command, _ := cfg.str("command")
	if strings.TrimSpace(command) == "" {
		return nil, fail("配置里没有命令")
	}
	var env []string
	if e := cfg.obj("env"); e != nil {
		for _, k := range e.keys {
			if v, ok := e.str(k); ok && envName.MatchString(k) {
				env = append(env, "ZETA_MCP_ENV_"+strconv.Itoa(len(env))+"="+k+"="+v)
			}
		}
	}
	// npx 之类第一次运行要先下载，给足时间
	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	cmd := s.loginCommand(ctx, stdioScript, append([]string{cwd, command}, argStrings(cfg)...), env)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, fail("启动不了")
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fail("启动不了")
	}
	stderr := &tailBuffer{keep: 4096}
	cmd.Stderr = stderr
	if err := cmd.Start(); err != nil {
		return nil, fail("启动不了")
	}
	defer func() {
		stdin.Close()
		done := make(chan struct{})
		go func() { _ = cmd.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			cancel()
			<-done
		}
	}()
	sess := &stdioSession{in: stdin, lines: bufio.NewScanner(stdout), ctx: ctx}
	sess.lines.Buffer(make([]byte, 64<<10), 16<<20)
	tools, err := sess.tools()
	if err != nil {
		if detail := lastLine(stderr.String()); detail != "" {
			return nil, fail("%v：%s", err, clip(mask(detail, secrets), 200))
		}
		return nil, err
	}
	return tools, nil
}

type stdioSession struct {
	in    io.Writer
	lines *bufio.Scanner
	ctx   context.Context
	next  int
}

func (s *stdioSession) send(b []byte) error {
	if _, err := s.in.Write(append(b, '\n')); err != nil {
		return fail("服务器进程退出了")
	}
	return nil
}

func (s *stdioSession) call(method string, params any) (json.RawMessage, error) {
	s.next++
	if err := s.send(rpcRequest(s.next, method, params)); err != nil {
		return nil, err
	}
	for s.lines.Scan() {
		line := bytes.TrimSpace(s.lines.Bytes())
		// 标准输出里不是 JSON 的行（登录配置打的字之类）跳过
		if len(line) == 0 || (line[0] != '{' && line[0] != '[') {
			continue
		}
		if result, ok, err := rpcReply(line, s.next); ok || err != nil {
			return result, err
		}
	}
	if s.ctx.Err() != nil {
		return nil, fail("服务器没有在 60 秒内回应")
	}
	return nil, fail("服务器进程退出了")
}

func (s *stdioSession) tools() ([]McpTool, error) {
	if _, err := s.call("initialize", initializeParams); err != nil {
		return nil, err
	}
	if err := s.send(rpcNotification("notifications/initialized")); err != nil {
		return nil, err
	}
	return listTools(s.call)
}

// tailBuffer 只留最后 keep 个字节，免得服务器的标准错误把内存写满。
type tailBuffer struct {
	mu   sync.Mutex
	keep int
	b    []byte
}

func (t *tailBuffer) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.b = append(t.b, p...)
	if len(t.b) > t.keep {
		t.b = t.b[len(t.b)-t.keep:]
	}
	return len(p), nil
}

func (t *tailBuffer) String() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return string(t.b)
}

// mcpHTTP 从这台电脑连 HTTP 和 SSE 的 MCP 服务器。
type mcpHTTP struct {
	ctx     context.Context
	url     string
	headers map[string]string
	session string
	next    int
}

var mcpClient = &http.Client{Timeout: 30 * time.Second}

func (h *mcpHTTP) do(method, target string, body []byte, extra map[string]string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(h.ctx, method, target, bytes.NewReader(body))
	if err != nil {
		return nil, fail("地址不对")
	}
	for k, v := range h.headers {
		req.Header.Set(k, v)
	}
	for k, v := range extra {
		req.Header.Set(k, v)
	}
	resp, err := mcpClient.Do(req)
	if err != nil {
		return nil, networkError(err)
	}
	switch {
	case resp.StatusCode >= 200 && resp.StatusCode < 300:
		return resp, nil
	case resp.StatusCode == 401 || resp.StatusCode == 403:
		resp.Body.Close()
		return nil, fail("服务器要求登录或 Token 不对（HTTP %d）", resp.StatusCode)
	}
	resp.Body.Close()
	return nil, fail("服务器返回 HTTP %d", resp.StatusCode)
}

// networkError 换成给人看的说明，不带地址（地址里可能有密钥）。
func networkError(err error) error {
	var ue *url.Error
	if errors.As(err, &ue) {
		if ue.Timeout() {
			return fail("连接超时")
		}
		err = ue.Err
	}
	return fail("连不上：%s", urlPattern.ReplaceAllString(err.Error(), "…"))
}

// post 发一条 Streamable HTTP 的消息；是请求时读它的回应（JSON 或一段事件流）。
func (h *mcpHTTP) post(body []byte, id int) (json.RawMessage, error) {
	extra := map[string]string{"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
	if h.session != "" {
		extra["Mcp-Session-Id"] = h.session
	}
	if h.next > 1 || id == 0 {
		extra["MCP-Protocol-Version"] = mcpProtocol
	}
	resp, err := h.do(http.MethodPost, h.url, body, extra)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if s := resp.Header.Get("Mcp-Session-Id"); s != "" {
		h.session = s
	}
	if id == 0 {
		return nil, nil
	}
	if strings.Contains(resp.Header.Get("Content-Type"), "text/event-stream") {
		return awaitSSE(newSSE(resp.Body), id)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return nil, networkError(err)
	}
	result, ok, err := rpcReply(data, id)
	if !ok && err == nil {
		return nil, fail("服务器的回应里没有这次请求的结果")
	}
	return result, err
}

// tools 走 Streamable HTTP：每个请求一次 POST。
func (h *mcpHTTP) tools() ([]McpTool, error) {
	call := func(method string, params any) (json.RawMessage, error) {
		h.next++
		return h.post(rpcRequest(h.next, method, params), h.next)
	}
	defer func() {
		if h.session != "" {
			if resp, err := h.do(http.MethodDelete, h.url, nil, map[string]string{"Mcp-Session-Id": h.session}); err == nil {
				resp.Body.Close()
			}
		}
	}()
	if _, err := call("initialize", initializeParams); err != nil {
		return nil, err
	}
	_, _ = h.post(rpcNotification("notifications/initialized"), 0)
	return listTools(call)
}

// toolsSSE 走旧的 SSE 传输：先 GET 一条事件流，从 endpoint 事件拿到发消息的地址，回应都从这条流里回来。
func (h *mcpHTTP) toolsSSE() ([]McpTool, error) {
	resp, err := h.do(http.MethodGet, h.url, nil, map[string]string{"Accept": "text/event-stream"})
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	events := newSSE(resp.Body)
	var endpoint string
	for endpoint == "" {
		ev, ok := events.next()
		if !ok {
			return nil, fail("服务器没有给出消息地址")
		}
		if ev.event == "endpoint" {
			base, _ := url.Parse(h.url)
			ref, err := url.Parse(strings.TrimSpace(ev.data))
			if err != nil {
				return nil, fail("服务器给的消息地址不对")
			}
			endpoint = base.ResolveReference(ref).String()
		}
	}
	send := func(body []byte) error {
		r, err := h.do(http.MethodPost, endpoint, body, map[string]string{"Content-Type": "application/json"})
		if err == nil {
			r.Body.Close()
		}
		return err
	}
	call := func(method string, params any) (json.RawMessage, error) {
		h.next++
		if err := send(rpcRequest(h.next, method, params)); err != nil {
			return nil, err
		}
		return awaitSSE(events, h.next)
	}
	if _, err := call("initialize", initializeParams); err != nil {
		return nil, err
	}
	if err := send(rpcNotification("notifications/initialized")); err != nil {
		return nil, err
	}
	return listTools(call)
}

type sseEvent struct{ event, data string }

// sseReader 读 text/event-stream：一行行读，空行结束一个事件。
type sseReader struct{ lines *bufio.Scanner }

func newSSE(r io.Reader) *sseReader {
	s := bufio.NewScanner(r)
	s.Buffer(make([]byte, 64<<10), 16<<20)
	return &sseReader{s}
}

func (r *sseReader) next() (sseEvent, bool) {
	ev := sseEvent{event: "message"}
	var data []string
	for r.lines.Scan() {
		line := strings.TrimSuffix(r.lines.Text(), "\r")
		switch {
		case line == "":
			if len(data) > 0 {
				ev.data = strings.Join(data, "\n")
				return ev, true
			}
			ev.event = "message"
		case strings.HasPrefix(line, ":"):
		case strings.HasPrefix(line, "event:"):
			ev.event = strings.TrimSpace(line[len("event:"):])
		case strings.HasPrefix(line, "data:"):
			data = append(data, strings.TrimPrefix(line[len("data:"):], " "))
		}
	}
	if len(data) > 0 {
		ev.data = strings.Join(data, "\n")
		return ev, true
	}
	return sseEvent{}, false
}

// awaitSSE 读到 id 的回应为止。
func awaitSSE(r *sseReader, id int) (json.RawMessage, error) {
	for {
		ev, ok := r.next()
		if !ok {
			return nil, fail("服务器没有回应就断开了")
		}
		if ev.event != "message" {
			continue
		}
		if result, ok, err := rpcReply([]byte(ev.data), id); ok || err != nil {
			return result, err
		}
	}
}
