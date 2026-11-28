package main

import (
	"bufio"
	"encoding/json"
	"io"
	"os"
	"os/user"
	"strings"

	"github.com/dzshzx/Zeta/helper/agystore"
)

// env 是各命令要用的路径。Claude Code 的会话两个包共用；Zeta 自己的目录按包分开
// （正式版 zeta，测试版 zeta-dev，由应用经 ZETA_NAME 传进来）。
type env struct {
	home     string // 家目录，去掉末尾的斜杠（可能是空串，表示根目录）
	name     string // Zeta 自己的目录名
	claude   string // ~/.claude
	agy      string // ~/.gemini/antigravity-cli（或 ZETA_AGY_DIR）
	root     string // ~/.claude/projects：会话记录
	sessions string // ~/.claude/sessions：Claude Code 每个进程登记自己的会话
	hosts    string // ~/.cache/<name>/hosts：Zeta 托管的后台对话
	uploads  string // ~/.cache/<name>/files：应用送进来的附件
	archive  string // ~/.config/<name>/archived：归档的会话 id
	pinned   string // ~/.config/<name>/pinned：置顶的会话 id
	order    string // ~/.config/<name>/order：手动排序的位置（sessiontimes.go）
	idle     string // ~/.config/<name>/idle-minutes：闲置超时（idle.go）
	aptRoot  string // apt 的缓存，存储页只统计
}

func newEnv() *env {
	name := os.Getenv("ZETA_NAME")
	if name == "" {
		name = "zeta"
	}
	return newEnvAt(userHome(), name)
}

func newEnvAt(home, name string) *env {
	home = strings.TrimRight(home, "/")
	agy := strings.TrimRight(agystore.Dir(home), "/")
	return &env{
		home:     home,
		name:     name,
		claude:   home + "/.claude",
		agy:      agy,
		root:     home + "/.claude/projects",
		sessions: home + "/.claude/sessions",
		hosts:    home + "/.cache/" + name + "/hosts",
		uploads:  home + "/.cache/" + name + "/files",
		archive:  home + "/.config/" + name + "/archived",
		pinned:   home + "/.config/" + name + "/pinned",
		order:    home + "/.config/" + name + "/order",
		idle:     home + "/.config/" + name + "/idle-minutes",
		aptRoot:  "/var/cache/apt",
	}
}

// userHome：$HOME（设了就用，空串也算）；没设时查用户数据库，查不到就原样留着“~”。
func userHome() string {
	if home, ok := os.LookupEnv("HOME"); ok {
		return home
	}
	if u, err := user.Current(); err == nil {
		return u.HomeDir
	}
	return "~"
}

// tilde 是“~”展开后的家目录，家目录是根目录时为 "/"。
func (e *env) tilde() string {
	if e.home == "" {
		return "/"
	}
	return e.home
}

// expandUser 展开路径开头的 ~ 和 ~用户名；用户不存在时原样返回。
func (e *env) expandUser(p string) string {
	if !strings.HasPrefix(p, "~") {
		return p
	}
	i := strings.IndexByte(p[1:], '/') + 1
	if i == 0 {
		i = len(p)
	}
	home := e.home
	if i > 1 {
		u, err := user.Lookup(p[1:i])
		if err != nil {
			return p
		}
		home = strings.TrimRight(u.HomeDir, "/")
	}
	if home+p[i:] == "" {
		return "/"
	}
	return home + p[i:]
}

// obj 是解析出来的 JSON 对象（数字保留原样，见 parseJSON）。
type obj = map[string]any

// emitter 把结果一行一个 JSON 写到标准输出：不转义 HTML 字符，非 ASCII 原样输出。
type emitter struct {
	w   *bufio.Writer
	enc *json.Encoder
}

func newEmitter(w io.Writer) *emitter {
	b := bufio.NewWriterSize(w, 64*1024)
	enc := json.NewEncoder(b)
	enc.SetEscapeHTML(false)
	return &emitter{w: b, enc: enc}
}

func (o *emitter) out(v any) {
	// 值都是本程序拼出来的或从 JSON 解析来的，编码不会失败
	_ = o.enc.Encode(v)
}

func (o *emitter) flush() { _ = o.w.Flush() }
