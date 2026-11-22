package hostlink

import (
	"errors"
	"strings"
	"time"
)

// 随消息附带的主机上的路径（conv.send 的 files），写法同 shared 的 AttachedPaths.kt：
//   - 以 / 开头：传到主机上的文件（~/.cache/<ZETA_NAME>/files/…，见 Upload），核心写成 [Attached file: 路径]；
//   - 以 @ 开头：引用主机上本来就有的路径，结尾带 / 的是文件夹，核心写成 [Attached file: @"路径"] 或 [Attached folder: @"路径"]。

// Reference 是引用主机上 path（已归一化的绝对路径）的附件说明。
func Reference(path string, folder bool) string {
	if folder {
		return "@" + strings.TrimRight(path, "/") + "/"
	}
	return "@" + path
}

// NormalizePath 是用户输入的主机上的路径：去掉首尾空白，~ 换成家目录 home，按字面消掉 . 和 ..，去掉结尾的 /。
// 不是绝对路径、带控制字符或双引号（写不进 @"…"）的返回 false。同 AttachedPaths.normalize。
func NormalizePath(raw, home string) (string, bool) {
	text := strings.TrimSpace(raw)
	if home != "" && (text == "~" || strings.HasPrefix(text, "~/")) {
		text = strings.TrimRight(home, "/") + text[1:]
	}
	if !strings.HasPrefix(text, "/") || strings.ContainsFunc(text, func(r rune) bool { return r < ' ' || r == 0x7f || r == '"' }) {
		return "", false
	}
	var segments []string
	for _, s := range strings.Split(text, "/") {
		switch s {
		case "", ".":
		case "..":
			if len(segments) == 0 {
				return "", false
			}
			segments = segments[:len(segments)-1]
		default:
			segments = append(segments, s)
		}
	}
	return "/" + strings.Join(segments, "/"), true
}

// PathKind 是主机上一个路径是什么。
type PathKind int

const (
	PathUnknown PathKind = iota // 查不成
	PathFile
	PathFolder
	PathMissing
	PathDenied // 那边的用户读不了
)

// pathScript 同 shared 的 PathCheck.Script：$1 是对话的托管标识（远程主机上不看进程的挂载，给空的），之后每个路径输出一行
// zeta_path <种类>。手机本机那台要比对进程看不看得到（hidden-*），远程主机上不会出现。
const pathScript = `r=
if [ -n "$1" ]; then
  p=$(cat "$HOME/.cache/${ZETA_NAME:-zeta}/hosts/$1/pid" 2>/dev/null)
  [ -n "$p" ] && [ -d "/proc/$p/root/" ] && r=/proc/$p/root
fi
shift
for a; do
  t=$r$a
  if [ -n "$r" ] && [ -e "$a" ] && { [ ! -e "$t" ] || [ "$(stat -L -c %d:%i "$a" 2>/dev/null)" != "$(stat -L -c %d:%i "$t" 2>/dev/null)" ]; }; then
    if [ -d "$a" ]; then k=hidden-folder; else k=hidden-file; fi
  elif [ -d "$t" ]; then k=folder; [ -r "$t" ] && [ -x "$t" ] || k=denied
  elif [ -e "$t" ]; then k=file; [ -r "$t" ] || k=denied
  else k=missing
  fi
  echo "zeta_path $k"
done`

// PathCheckCommand 是查 paths 的那一行。
func PathCheckCommand(paths ...string) string {
	cmd := "sh -c " + Quote(pathScript) + " zeta-path ''"
	for _, p := range paths {
		cmd += " " + Quote(p)
	}
	return cmd
}

// ParsePathKinds 读 PathCheckCommand 的输出，顺序同参数；别的行（登录提示之类）跳过。
func ParsePathKinds(stdout string) []PathKind {
	var kinds []PathKind
	for _, line := range strings.Split(stdout, "\n") {
		kind, ok := strings.CutPrefix(strings.TrimSpace(line), "zeta_path ")
		if !ok {
			continue
		}
		switch kind {
		case "folder":
			kinds = append(kinds, PathFolder)
		case "file":
			kinds = append(kinds, PathFile)
		case "missing":
			kinds = append(kinds, PathMissing)
		case "denied":
			kinds = append(kinds, PathDenied)
		default:
			kinds = append(kinds, PathUnknown)
		}
	}
	return kinds
}

// pathCheckTimeout 是查一个路径最多等多久。
const pathCheckTimeout = 20 * time.Second

// CheckPath 以那边用户的身份查 path（绝对路径）。连不上、超时时返回错误。阻塞。
func (s Shell) CheckPath(path string) (PathKind, error) {
	r, err := s.Run(PathCheckCommand(path), nil, pathCheckTimeout, true)
	if err != nil {
		return PathUnknown, err
	}
	if problem := s.LinkProblem(r); problem != "" {
		return PathUnknown, errors.New(problem)
	}
	if r.TimedOut {
		return PathUnknown, errors.New("「" + s.Host.Name() + "」没有响应（超时）")
	}
	kinds := ParsePathKinds(r.Stdout)
	if len(kinds) != 1 {
		return PathUnknown, errors.New("查不了：" + tail(r.Stderr, 200))
	}
	return kinds[0], nil
}
