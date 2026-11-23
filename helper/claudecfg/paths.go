package claudecfg

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf16"

	"github.com/dzshzx/Zeta/helper/locales"
)

// Claude Code（2.1.327）给项目算目录的办法：会话记录在 ~/.claude/projects/<key>/，自动记忆在 …/<key>/memory/。

const maxKey = 200

// sanitize 把非字母数字一律换成 -：按 UTF-16 码元逐个换，和 JS 的 replace(/[^a-zA-Z0-9]/g, "-") 一样。
func sanitize(path string) string {
	units := utf16.Encode([]rune(path))
	b := make([]byte, len(units))
	for i, u := range units {
		switch {
		case u >= 'a' && u <= 'z', u >= 'A' && u <= 'Z', u >= '0' && u <= '9':
			b[i] = byte(u)
		default:
			b[i] = '-'
		}
	}
	return string(b)
}

// projectKey 是项目目录名：先 sanitize；超过 200 个字符时取前 200 个，再接 - 和整条路径的哈希（36 进制）。
// 哈希是逐个 UTF-16 码元的 (h<<5)-h+c，即 Java 的 String.hashCode；取绝对值时按 JS 的数值算，不溢出。
func projectKey(path string) string {
	s := sanitize(path)
	if len(s) <= maxKey {
		return s
	}
	var h int32
	for _, u := range utf16.Encode([]rune(path)) {
		h = 31*h + int32(u)
	}
	abs := int64(h)
	if abs < 0 {
		abs = -abs
	}
	return s[:maxKey] + "-" + strconv.FormatInt(abs, 36)
}

// resolve 词法归一化成绝对路径（.、..、重复的 /），不碰文件系统；相对路径接在 base 上。
func resolve(base, path string) string {
	full := path
	if !strings.HasPrefix(path, "/") {
		full = base + "/" + path
	}
	var parts []string
	for _, seg := range strings.Split(full, "/") {
		switch seg {
		case "", ".":
		case "..":
			if len(parts) > 0 {
				parts = parts[:len(parts)-1]
			}
		default:
			parts = append(parts, seg)
		}
	}
	return "/" + strings.Join(parts, "/")
}

func parentDir(path string) string {
	i := strings.LastIndexByte(path, '/')
	if i <= 0 {
		return "/"
	}
	return path[:i]
}

func baseName(path string) string { return path[strings.LastIndexByte(path, '/')+1:] }

// gitInfo 是 cwd 往上找到的第一个 .git，以及它是 worktree 时顺着读到的几个文件（脚本 gitProbe 的 G 记录原样的内容）。
type gitInfo struct {
	root         string // .git 所在的目录；不在 git 仓库里时为空
	dotGitFile   bool   // .git 是文件（worktree、submodule）
	gitFile      string // .git 文件的内容
	gitDir       string // gitdir 指向的目录（相对路径已接到 root 上，未归一化）
	commonDir    *string
	backLink     *string
	backLinkReal string
	commonHasGit bool // 共用的 git 目录下面还有 .git（判断裸仓库用）
}

// probeGit 从 cwd 往上找第一个 .git；是文件（worktree、submodule）时顺着读 gitdir、commondir，交给 canonicalRoot 判断。
// real 是 cwd 解析过符号链接的真实路径，cwd 不在时为空。
func probeGit(cwd string) (real string, g gitInfo) {
	real, err := filepath.EvalSymlinks(cwd)
	if err != nil || !filepath.IsAbs(real) {
		return "", gitInfo{}
	}
	for p := real; ; p = parentDir(p) {
		dotGit := strings.TrimSuffix(p, "/") + "/.git"
		if st, err := os.Stat(dotGit); err == nil && st.IsDir() {
			return real, gitInfo{root: p}
		} else if err == nil && st.Mode().IsRegular() {
			return real, gitFileInfo(p, dotGit)
		}
		if p == "/" {
			return real, gitInfo{}
		}
	}
}

func gitFileInfo(root, dotGit string) gitInfo {
	head := func(path string) (string, bool) {
		b, _, ok := readHead(path, 4096)
		return string(b), ok
	}
	content, _ := head(dotGit)
	g := gitInfo{root: root, dotGitFile: true, gitFile: content}
	d := strings.TrimSpace(strings.TrimPrefix(content, "gitdir:"))
	g.gitDir = d
	if !strings.HasPrefix(d, "/") {
		g.gitDir = root + "/" + d
	}
	common := ""
	if c, ok := head(g.gitDir + "/commondir"); ok {
		g.commonDir, common = &c, c
	}
	if b, ok := head(g.gitDir + "/gitdir"); ok {
		g.backLink = &b
		g.backLinkReal = realpathMissing(g.gitDir, strings.TrimSpace(b))
	}
	first, _, _ := strings.Cut(common, "\n")
	u := first
	if !strings.HasPrefix(first, "/") {
		u = g.gitDir + "/" + first
	}
	_, err := os.Stat(u + "/.git")
	g.commonHasGit = err == nil
	return g
}

// realpathMissing 和 realpath -m 一样：相对路径接在 base 上，存在的部分解析符号链接，不存在的按词法归一化。
func realpathMissing(base, path string) string {
	p := resolve(base, path)
	if real, err := filepath.EvalSymlinks(p); err == nil {
		return real
	}
	if real, err := filepath.EvalSymlinks(parentDir(p)); err == nil {
		return resolve(real, baseName(p))
	}
	return p
}

// canonicalRoot：同一个仓库的各个 worktree 共用一份自动记忆，.git 是文件时顺着 gitdir → commondir 找到主仓库。
// 照 Claude Code 的做法逐项核对，任何一项对不上就当普通目录，用 .git 所在的目录。不在仓库里时为空。
func canonicalRoot(g gitInfo) string {
	if g.root == "" || !g.dotGitFile {
		return g.root
	}
	content := strings.TrimSpace(g.gitFile)
	rest, ok := strings.CutPrefix(content, "gitdir:")
	if !ok || g.commonDir == nil || g.backLink == nil {
		return g.root
	}
	gitDir := resolve(g.root, strings.TrimSpace(rest))
	commonDir := resolve(gitDir, strings.TrimSpace(*g.commonDir))
	if parentDir(gitDir) != resolve(commonDir, "worktrees") || g.backLinkReal != resolve(g.root, ".git") {
		return g.root
	}
	if baseName(commonDir) != ".git" {
		if g.commonHasGit {
			return g.root
		}
		return commonDir
	}
	return parentDir(commonDir)
}

// labelFor 给项目目录找回原来的路径：目录名把 / 换成了 -，还原不了，只能拿会话记录里的 cwd 去对。cwd 或它的某一级上层
// 算出来正好是 key 就用它（会话常在仓库的子目录里开）；都对不上返回空。
func labelFor(key string, cwds []string) string {
	for _, cwd := range cwds {
		for path := cwd; ; path = parentDir(path) {
			if projectKey(path) == key {
				return path
			}
			if path == "/" || !strings.HasPrefix(path, "/") {
				break
			}
		}
	}
	return ""
}

var keyName = regexp.MustCompile(`^[A-Za-z0-9-]+$`)

// isMemoryFileName：不带路径、不是隐藏文件、以 .md 结尾。
func isMemoryFileName(name string) bool {
	return strings.HasSuffix(name, ".md") && len(name) > 3 && !strings.Contains(name, "/") &&
		!strings.HasPrefix(name, ".") && !hasControl(name)
}

func claudeDir(home string) string    { return resolve(home, ".claude") }
func instructions(home string) string { return claudeDir(home) + "/CLAUDE.md" }
func settingsPath(home string) string { return claudeDir(home) + "/settings.json" }
func projectsDir(home string) string  { return claudeDir(home) + "/projects" }
func agentsDir(home string) string    { return claudeDir(home) + "/agents" }

func memoryDir(home, key string) string { return projectsDir(home) + "/" + key + "/memory" }

// editableProblem：设置页能读写的文件只有 ~/.claude/CLAUDE.md、各项目 memory 目录里的 .md，以及设了
// autoMemoryDirectory 时那个目录里的 .md。可以时返回 nil，否则是原因。
func editableProblem(path, home, customDir string) error {
	if path != resolve("/", path) {
		return locales.Err("cfgBadPath")
	}
	if path == instructions(home) {
		return nil
	}
	if !isMemoryFileName(baseName(path)) {
		return locales.Err("cfgOnlyMemoryFiles")
	}
	dir := parentDir(path)
	if customDir != "" && dir == customDir {
		return nil
	}
	if rest, ok := strings.CutPrefix(dir, projectsDir(home)+"/"); ok {
		if key, ok := strings.CutSuffix(rest, "/memory"); ok && keyName.MatchString(key) {
			return nil
		}
	}
	return locales.Err("cfgNotEditable")
}

// customMemoryDir 是 settings.json 的 autoMemoryDirectory 展开后的路径：~/ 开头的接到家目录上，去掉末尾的 /；
// 不是绝对路径、太短或是根目录的，Claude Code 不认，这里也当没设（空串）。
func customMemoryDir(value, home string) string {
	raw := strings.TrimSpace(value)
	var dir string
	switch {
	case raw == "" || raw == "~":
		return ""
	case strings.HasPrefix(raw, "~/"):
		dir = resolve(home, raw[2:])
	case strings.HasPrefix(raw, "/"):
		dir = resolve("/", raw)
	default:
		return ""
	}
	if len(dir) < 3 || strings.ContainsRune(dir, 0) {
		return ""
	}
	return dir
}
