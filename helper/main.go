// zeta-helper：Zeta 在 Linux 一侧（手机的 Debian、远程主机）用的小程序，读写 Claude Code 自己保存的会话记录（~/.claude/projects），
// 也做文件浏览、共享文件夹、存储页的统计和清理。
//
// 用法 zeta-helper <命令> [参数]，输出一行一个 JSON：
//
//	list                 所有会话的概要，新的在前（每份只读头尾，见 summarize）
//	history <文件> [<到哪条为止> <核对用的 uuid|-> [all]]
//	                     一个会话里要显示的消息，形状与 stream-json 输出一致；用户消息带上它在记录里的 uuid。
//	                     只给文件时是最近的一段，带上位置时是那之前的一页（见 history.go）
//	delete <文件>...      删除会话记录，连同 Claude Code 存在同名目录里的东西（工具结果、子代理记录等）
//	rename <文件> <标题>   给会话改名（追加一行 custom-title，与 Claude Code 自己的 /rename 相同）
//	archive <id>... <0|1> 归档或取消归档会话（只是列表里不显示）
//	hosts                仍在后台运行的 Claude Code 进程（应用被杀后重新接上用）；顺手清掉已经结束的
//	end <id>             结束正占着这个会话的、Zeta 之外的 Claude Code 进程（终端、tmux 里开着的），见 end.go
//	image <文件> <位置>    取回会话记录里的一张图片（位置是 history 给出的“行号.块序号[.内层序号]”）
//	file-image <路径>     读取 Linux 环境里的一个图片文件
//	search <关键词>       在会话的对话内容里找关键词，每找到一个会话就输出一行（边找边出），最后一行是汇总
//	lookup <id>...       按 id 取会话概要（形状同 list），找不到的不输出；置顶的会话不在 list 的范围里时用
//	export <文件>...      把会话导出成 Markdown，一个会话一行
//	ls <目录>             文件浏览：列出一个目录（第一行概要，之后一行一项）
//	read <文件> <字节数>   文件浏览：读一个文本文件的开头，最多这么多字节
//	mounts <名字…>        共享文件夹：/mnt/shared 下各项有没有挂上、当前用户能不能读写
//	storage [<id>…]      存储页：各类占多少空间、能清掉多少（<id> 是应用认为正在进行、要保护的会话）
//	storage-sessions <项目目录> [<id>…]  存储页：一个项目里每个会话占的空间
//	clean <类别> [<id>…]  存储页：清掉一类里能安全删的（规矩见 storage.go）
//	phone-mcp <包名> [<标识>] 把标准输入输出接到这个包的 Zeta 手机工具（MCP），要 root，见 phonemcp.go
//	serve                常驻连接：一行一个 JSON 的请求和推送（会话列表的订阅等），见 serve.go
//	subagent <字节> <行> <tool_use id> <agent id|-> <会话 id>…
//	                     子代理查看页：发起它的 Agent 调用对应的记录，从这个位置起增量读（见 subagent.go）
//
// 参数不对时把用法写到标准错误、退出码 1；命令自己出错（文件不在等）也是退出码 1。
// 用到的环境变量：HOME，ZETA_NAME（Zeta 自己的目录名，正式版 zeta、测试版 zeta-dev，默认 zeta），
// ZETA_STORAGE_TEST_TMP（测试用的假 /tmp，见 storage.go）。
package main

import (
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"regexp"
	"strconv"
)

const usageText = "usage: list | history <file> [<end> <anchor|-> [all]]" +
	" | delete <file>... | rename <file> <title> | archive <id>... <0|1> | hosts | end <id>" +
	" | image <file> <ref> | file-image <path> | search <query> | lookup <id>... | export <file>..." +
	" | ls <dir> | read <file> <bytes> | mounts <name…> | storage [<id>…]" +
	" | storage-sessions <project> [<id>…] | clean <kind> [<id>…]" +
	" | subagent <offset> <line> <tool_use> <agent|-> <session>…" +
	" | occupant <id> | handoff <id> | session-start" +
	" | desk <id> <cwd> | desk-open <id> <cwd> | desk-close <id>…" +
	" | phone-mcp <package> [<client key>] | serve"

var (
	sessionIDPattern   = regexp.MustCompile(`^[0-9a-fA-F-]{8,64}$`)
	historyAnchor      = regexp.MustCompile(`^(?:-|[A-Za-z0-9_-]{1,128})$`)
	subagentArgPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)
	mountNamePattern   = regexp.MustCompile(`^[A-Za-z0-9._-]{1,48}$`)
	errUsage           = errors.New(usageText)
)

func main() {
	// phone-mcp 一直转发到连接断开，输出不能等到退出再写，不走 run 的缓冲
	if len(os.Args) > 1 && os.Args[1] == "phone-mcp" {
		os.Exit(runPhoneMCP(realPhoneBridge, os.Args[2:], os.Stdin, os.Stdout, os.Stderr))
	}
	// serve 一直跑到客户端走了，输出边推边写
	if len(os.Args) == 2 && os.Args[1] == "serve" {
		_ = cmdServe(newEnv(), defaultServeOptions, os.Stdin, os.Stdout)
		os.Exit(0)
	}
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

// run 跑一个命令，返回退出码。输出先进缓冲，退出前写出（search、storage 边做边写）。
func run(args []string, stdout, stderr io.Writer) int {
	e := newEnv()
	o := newEmitter(stdout)
	err := dispatch(e, o, args)
	o.flush()
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	return 0
}

func dispatch(e *env, o *emitter, args []string) error {
	if len(args) == 0 {
		return errUsage
	}
	rest := args[1:]
	switch args[0] {
	case "list":
		return cmdList(e, o)
	case "history":
		return dispatchHistory(e, o, rest)
	case "delete":
		if len(rest) >= 1 && allOf(rest, e.insideRoot) {
			return cmdDelete(e, o, rest)
		}
	case "rename":
		if len(rest) == 2 && e.insideRoot(rest[0]) {
			return cmdRename(o, rest[0], rest[1])
		}
	case "archive":
		if len(rest) >= 2 && (rest[len(rest)-1] == "0" || rest[len(rest)-1] == "1") && allOf(rest[:len(rest)-1], isSessionID) {
			return cmdArchive(e, o, rest[:len(rest)-1], rest[len(rest)-1] == "1")
		}
	case "hosts":
		return cmdHosts(e, o)
	case "end":
		if len(rest) == 1 && isSessionID(rest[0]) {
			return cmdEnd(e, o, rest[0])
		}
	case "session-start":
		if len(rest) == 0 {
			return cmdSessionStart(e, o, os.Stdin)
		}
	case "occupant", "handoff", "desk", "desk-open", "desk-close":
		return dispatchHandoff(e, o, args[0], rest)
	case "image":
		if len(rest) == 2 && e.insideRoot(rest[0]) {
			return cmdImage(o, rest[0], rest[1])
		}
	case "file-image":
		if len(rest) == 1 {
			return cmdFileImage(e, o, rest[0])
		}
	case "search":
		if len(rest) == 1 {
			return cmdSearch(e, o, rest[0])
		}
	case "lookup":
		if allOf(rest, isSessionID) {
			return cmdLookup(e, o, rest)
		}
	case "export":
		if len(rest) >= 1 && allOf(rest, e.insideRoot) {
			cmdExport(e, o, rest)
			return nil
		}
	default:
		return dispatchFilesAndStorage(e, o, args)
	}
	return errUsage
}

// 交接与电脑那边的 tmux 窗口（handoff.go、desk.go）。
func dispatchHandoff(e *env, o *emitter, command string, rest []string) error {
	if len(rest) == 0 || !allOf(rest[:1], isSessionID) {
		return errUsage
	}
	id := rest[0]
	switch {
	case command == "occupant" && len(rest) == 1:
		return cmdOccupant(e, o, id)
	case command == "handoff" && len(rest) == 1:
		return cmdHandoff(e, o, id)
	case command == "desk" && len(rest) == 2:
		return cmdDesk(e, os.Stdout, id, rest[1])
	case command == "desk-open" && len(rest) == 2:
		return cmdDeskOpen(e, o, id, rest[1])
	case command == "desk-close" && allOf(rest, isSessionID):
		return cmdDeskClose(o, rest)
	}
	return errUsage
}

// history <文件> 或 history <文件> <到哪条为止> <核对用的 uuid|-> [all]
func dispatchHistory(e *env, o *emitter, rest []string) error {
	if len(rest) == 1 && e.insideRoot(rest[0]) {
		return cmdHistory(o, rest[0], historyPos{})
	}
	if (len(rest) == 3 || len(rest) == 4) && e.insideRoot(rest[0]) && isDigits(rest[1]) &&
		historyAnchor.MatchString(rest[2]) && (len(rest) == 3 || rest[3] == "all") {
		pos := historyPos{given: true, end: parseCount(rest[1]), anchor: rest[2], rest: len(rest) == 4}
		return cmdHistory(o, rest[0], pos)
	}
	return errUsage
}

func dispatchFilesAndStorage(e *env, o *emitter, args []string) error {
	rest := args[1:]
	switch args[0] {
	case "ls":
		if len(rest) == 1 && len(rest[0]) > 0 && rest[0][0] == '/' {
			cmdLs(o, rest[0])
			return nil
		}
	case "read":
		if len(rest) == 2 && len(rest[0]) > 0 && rest[0][0] == '/' && isDigits(rest[1]) {
			cmdRead(o, rest[0], parseCount(rest[1]))
			return nil
		}
	case "mounts":
		var names []string
		for _, n := range rest {
			if mountNamePattern.MatchString(n) && n != "." && n != ".." {
				names = append(names, n)
			}
		}
		cmdMounts(o, names)
		return nil
	case "storage":
		if allOf(rest, isSessionID) {
			cmdStorage(e, o, rest)
			return nil
		}
	case "storage-sessions":
		if len(rest) >= 1 && allOf(rest[1:], isSessionID) {
			return cmdStorageSessions(e, o, rest[0], rest[1:])
		}
	case "clean":
		if len(rest) >= 1 && cleanable(rest[0]) && allOf(rest[1:], isSessionID) {
			cmdClean(e, o, rest[0], rest[1:])
			return nil
		}
	case "subagent":
		return dispatchSubagent(e, o, rest)
	}
	return errUsage
}

// subagent <字节> <行> <tool_use id> <agent id|-> <会话 id>…
func dispatchSubagent(e *env, o *emitter, rest []string) error {
	if len(rest) < 5 || !isDigits(rest[0]) || !isDigits(rest[1]) ||
		!subagentArgPattern.MatchString(rest[2]) || !subagentArgPattern.MatchString(rest[3]) || !allOf(rest[4:], isSessionID) {
		return errUsage
	}
	hint := rest[3]
	if hint == "-" {
		hint = ""
	}
	return cmdSubagent(e, o, subagentRequest{
		offset: parseCount(rest[0]), line: parseCount(rest[1]), toolUse: rest[2], hint: hint, sessions: rest[4:],
	}, subagentChunk)
}

func isSessionID(s string) bool { return sessionIDPattern.MatchString(s) }

func allOf(items []string, ok func(string) bool) bool {
	for _, s := range items {
		if !ok(s) {
			return false
		}
	}
	return true
}

// isDigits：非空、全是 ASCII 数字。
func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}

// parseCount 把一串数字转成整数；大到装不下时取 int64 的上限（这样的位置、字节数都只会被当成“超出范围”）。
func parseCount(s string) int64 {
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return math.MaxInt64
	}
	return n
}
