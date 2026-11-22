package sshlink

import (
	"bytes"
	"context"
	"errors"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"
)

// Route 是一个地址实际连到哪、用哪些私钥登录；Keys 是给人看的私钥名。
type Route struct {
	Target  Target
	Signers []ssh.Signer
	Keys    string
}

// Resolver 给出地址实际连到哪、用哪些私钥（Links 每次连都问，配置改了下次连接就照新的）。
type Resolver interface {
	// Resolve 算地址 target 连到哪；key 是用户给这台设备选的私钥，空是照默认的。用不了时返回 Problem。
	Resolve(target, key string) (Route, error)
	// Rejected 是对方不认 route 的私钥时给人看的一句话。
	Rejected(route Route) string
}

// Problem 是已经写成一句给人看的话的连接问题。
type Problem string

func (p Problem) Error() string { return string(p) }

// ConfigHint 是地址的写法。
const ConfigHint = "输入 SSH 配置中的主机名，或 用户@主机[:端口]。"

// OpenSSH 照这台电脑的 OpenSSH 配置直连：地址（别名或 用户@主机[:端口]）交给 ssh -G 算出实际的主机、用户、端口和
// IdentityFile（Include、Match、通配都照 OpenSSH 自己的规矩），再用这几个私钥文件登录；设备也可以另选 ~/.ssh 里的一把，
// 这时只用那一把。不问口令，所以设了口令的私钥不支持；ProxyJump、ProxyCommand 也不支持，配了就明说。不读 known_hosts：
// 主机指纹由 Links 照设备记下的核对。
type OpenSSH struct {
	Home   string // ~ 所在（Windows 上是 %USERPROFILE%）
	SSH    string // OpenSSH 客户端的命令
	Config string // 不是空时用 -F 换掉 ~/.ssh/config（测试用）
}

// NewOpenSSH 照这个用户的家目录和系统带的 ssh。
func NewOpenSSH() *OpenSSH {
	home, _ := os.UserHomeDir()
	return &OpenSSH{Home: home, SSH: sshCommand()}
}

// sshCommand 是 Windows 自带的 OpenSSH 客户端；不在那里（别的系统、自己装的）时交给 PATH 找。
func sshCommand() string {
	if root := os.Getenv("SystemRoot"); root != "" {
		path := filepath.Join(root, "System32", "OpenSSH", "ssh.exe")
		if isFile(path) {
			return path
		}
	}
	return "ssh"
}

// Resolve 见 Resolver。
func (a *OpenSSH) Resolve(target, key string) (Route, error) {
	var args []string
	if t, ok := ParseTarget(target); ok {
		args = []string{"-l", t.User, "-p", strconv.Itoa(t.Port), "--", t.Host}
	} else if alias, ok := Alias(target); ok {
		args = []string{"--", alias}
	} else {
		return Route{}, Problem(ConfigHint)
	}
	output, err := a.run(append([]string{"-G"}, args...))
	if err != nil {
		return Route{}, err
	}
	options := ParseOptions(output)
	first := func(name string) string {
		if v := options[name]; len(v) > 0 {
			return v[0]
		}
		return ""
	}
	for _, name := range []string{"proxyjump", "proxycommand"} {
		if v := first(name); v != "" && v != "none" {
			return Route{}, Problem("暂不支持通过跳板机连接（" + target + "）")
		}
	}
	host, user := first("hostname"), first("user")
	if host == "" {
		return Route{}, Problem("SSH 配置中缺少 " + target + " 的 HostName")
	}
	if user == "" {
		return Route{}, Problem("SSH 配置中缺少 " + target + " 的 User")
	}
	port, err := strconv.Atoi(first("port"))
	if err != nil {
		port = 22
	}
	files, err := a.keyFiles(target, key, options["identityfile"], host, user)
	if err != nil {
		return Route{}, err
	}
	signers, names := loadKeys(files)
	if len(signers) == 0 {
		return Route{}, Problem("无法读取 SSH 密钥 " + names + "（暂不支持有密码保护的密钥）")
	}
	return Route{Target: Target{User: user, Host: host, Port: port}, Signers: signers, Keys: names}, nil
}

// keyFiles 是要试的私钥文件：选了 key 就只有它，否则是配置里在的那几个 IdentityFile。
func (a *OpenSSH) keyFiles(target, key string, configured []string, host, user string) ([]string, error) {
	if key != "" {
		path := a.expand(key, host, user)
		if !isFile(path) {
			return nil, Problem("找不到 SSH 密钥 " + KeyName(key))
		}
		return []string{path}, nil
	}
	var files []string
	seen := map[string]bool{}
	for _, f := range configured {
		path := a.expand(f, host, user)
		if isFile(path) && !seen[path] {
			seen[path] = true
			files = append(files, path)
		}
	}
	if len(files) == 0 {
		return nil, Problem("没有可用于 " + target + " 的 SSH 密钥，请选择一个")
	}
	return files, nil
}

// loadKeys 读私钥文件；读不了、设了口令的跳过。names 是这几个文件的名字。
func loadKeys(files []string) (signers []ssh.Signer, names string) {
	var list []string
	for _, path := range files {
		list = append(list, filepath.Base(path))
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		if signer, err := ssh.ParsePrivateKey(data); err == nil {
			signers = append(signers, signer)
		}
	}
	return signers, strings.Join(list, ", ")
}

// Rejected 见 Resolver。
func (a *OpenSSH) Rejected(route Route) string { return "服务器拒绝了 SSH 密钥 " + route.Keys }

// ConfigHosts 是 ~/.ssh/config 和同目录的 config.local 里 Host 行列的具体主机（ParseAliases）；两个都不在时是空的。
func (a *OpenSSH) ConfigHosts() ([]string, error) {
	files := []string{filepath.Join(a.Home, ".ssh", "config"), filepath.Join(a.Home, ".ssh", "config.local")}
	if a.Config != "" {
		files = []string{a.Config}
	}
	var text bytes.Buffer
	for _, path := range files {
		data, err := os.ReadFile(path)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return nil, err
		}
		text.Write(data)
		text.WriteByte('\n')
	}
	return ParseAliases(text.String()), nil
}

// Keys 是 ~/.ssh 里旁边有同名 .pub 的私钥文件，写成 ~/.ssh/名字，按名字排。
func (a *OpenSSH) Keys() []string {
	dir := filepath.Join(a.Home, ".ssh")
	entries, _ := os.ReadDir(dir)
	var keys []string
	for _, e := range entries {
		if e.Type().IsRegular() && isFile(filepath.Join(dir, e.Name()+".pub")) {
			keys = append(keys, "~/.ssh/"+e.Name())
		}
	}
	sort.Strings(keys)
	return keys
}

// KeyName 是私钥（Keys 里的一项）给人看的名字。
func KeyName(key string) string {
	if i := strings.LastIndexAny(key, `/\`); i >= 0 {
		return key[i+1:]
	}
	return key
}

// run 跑 ssh [-F config] args，返回标准输出；跑不起来、超时、不是 0 时返回 Problem，带上它说的原因。
func (a *OpenSSH) run(args []string) (string, error) {
	if a.Config != "" {
		args = append([]string{"-F", a.Config}, args...)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, a.SSH, args...)
	hideWindow(cmd)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	err := cmd.Run()
	var exit *exec.ExitError
	switch {
	case ctx.Err() != nil:
		return "", Problem("读取 SSH 配置超时")
	case errors.As(err, &exit):
		return "", Problem("SSH 配置有误：" + clip(lastLine(stderr.String()), 200))
	case err != nil:
		return "", Problem("未找到 OpenSSH 客户端（" + a.SSH + "）：" + clip(err.Error(), 120))
	}
	return stdout.String(), nil
}

// expand 把 IdentityFile 里的 ~ 和 %d %h %r %u %% 换成实际的值；别的记号原样留着（那样的文件多半不在，跳过）。
func (a *OpenSSH) expand(path, host, user string) string {
	if path == "~" || strings.HasPrefix(path, "~/") || strings.HasPrefix(path, `~\`) {
		path = a.Home + path[1:]
	}
	var b strings.Builder
	for i := 0; i < len(path); i++ {
		if path[i] != '%' || i+1 == len(path) {
			b.WriteByte(path[i])
			continue
		}
		switch path[i+1] {
		case 'd':
			b.WriteString(a.Home)
		case 'h':
			b.WriteString(host)
		case 'r':
			b.WriteString(user)
		case 'u':
			b.WriteString(localUser())
		case '%':
			b.WriteByte('%')
		default:
			b.WriteString(path[i : i+2])
		}
		i++
	}
	return filepath.FromSlash(b.String())
}

// localUser 是这台电脑上的用户名（Windows 上去掉前面的域名）。
func localUser() string {
	u, err := user.Current()
	if err != nil {
		return ""
	}
	name := u.Username
	if i := strings.LastIndex(name, `\`); i >= 0 {
		name = name[i+1:]
	}
	return name
}

// ParseOptions 读 ssh -G 的输出：每行“名字 值”，名字小写；IdentityFile 这类能写好几个的按先后都留着。
func ParseOptions(output string) map[string][]string {
	options := map[string][]string{}
	for _, line := range strings.Split(output, "\n") {
		name, value, ok := strings.Cut(strings.TrimSpace(line), " ")
		if ok && name != "" {
			name = strings.ToLower(name)
			options[name] = append(options[name], strings.TrimSpace(value))
		}
	}
	return options
}

// ParseAliases 是 ssh 配置文本里 Host 行列的别名，按出现的先后、去重；带通配符的（*、?、!）不是一台具体的主机，跳过。
func ParseAliases(text string) []string {
	var aliases []string
	seen := map[string]bool{}
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if len(line) <= 4 || !strings.EqualFold(line[:4], "host") || (line[4] != ' ' && line[4] != '\t') {
			continue
		}
		for _, alias := range strings.Fields(line[5:]) {
			if strings.ContainsAny(alias, "*?!") || !validOpenSSH(alias) || seen[alias] {
				continue
			}
			seen[alias] = true
			aliases = append(aliases, alias)
		}
	}
	return aliases
}

func isFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

func lastLine(text string) string {
	lines := strings.Split(strings.TrimSpace(text), "\n")
	return strings.TrimSpace(lines[len(lines)-1])
}

// clip 截到最多 n 个字符。
func clip(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n])
	}
	return s
}

// KeyAuth 用一把给定的私钥登录，地址只认写明用户的（不读 ~/.ssh 的配置）。测试里用它代替 OpenSSH。
type KeyAuth struct{ Signer ssh.Signer }

// Resolve 见 Resolver。
func (a KeyAuth) Resolve(target, _ string) (Route, error) {
	t, ok := ParseTarget(target)
	if !ok {
		return Route{}, Problem("地址写成 用户@主机，端口不是 22 的写成 用户@主机:端口")
	}
	return Route{Target: t, Signers: []ssh.Signer{a.Signer}, Keys: "给定的密钥"}, nil
}

// Rejected 见 Resolver。
func (a KeyAuth) Rejected(Route) string { return "服务器拒绝了给定的密钥" }
