package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

// 电脑那边的「Zeta」tmux 会话：Zeta 在远程主机上起的对话，每个在 tmux 会话 zeta 里有一个窗口（窗口名是标题），
// 回到电脑前 `tmux attach -t zeta` 就找得到，回车就在终端里接着。
//
//   desk-open <id> <工作目录>   没有这个会话的窗口就开一个（窗口选项 @zeta_session 记着会话 id），有就不动
//   desk-close <id>…           关掉这些会话的窗口（Zeta 里删除、归档时）
//   desk <id> <工作目录>       窗口里跑的：平时只显示一行提示、不起 claude，按会话记录里的标题（不管在哪边改的）改窗口名；
//                              回车先让 Zeta 那边放手（releaseSession：闲着就让它退出，正忙就等），再在这个窗口里
//                              `claude --resume <id>`；它退出（/exit，或 Zeta 用 handoff 接走）后回到提示
//
// 窗口里跑的是装好的程序旁边那个不带版本的链接（HelperProgram 装程序时建），Zeta 升级换了程序也照样能用。

// deskSession 是那个 tmux 会话的名字。
const deskSession = "zeta"

// deskStale：会话记录这么久没动过、窗口又闲着，就关掉窗口（窗口里的提示自己退出）。
const deskStale = 7 * 24 * time.Hour

// deskTick 是窗口闲着时多久看一次会话还在不在、旧没旧、标题变没变。
var deskTick = 30 * time.Second

// deskFirstRecord：窗口开了这么久会话记录还没出现，就当会话没了。
const deskFirstRecord = 10 * time.Minute

// deskReleaseWait 是回车后等 Zeta 那边这一轮跑完的上限。
const deskReleaseWait = 30 * time.Minute

// tmuxPresent：这台机器上有 tmux；测试里换掉。
var tmuxPresent = func() bool {
	_, err := exec.LookPath("tmux")
	return err == nil
}

// tmuxRun 跑一条 tmux 命令，给出标准输出（去掉末尾换行）；测试里换掉。
var tmuxRun = func(args ...string) (string, error) {
	out, err := exec.Command("tmux", args...).Output()
	var exit *exec.ExitError
	if errors.As(err, &exit) && len(exit.Stderr) > 0 {
		err = fmt.Errorf("%w: %s", err, strings.TrimSpace(string(exit.Stderr)))
	}
	return strings.TrimRight(string(out), "\n"), err
}

// deskWindows 是 tmux 里记着 @zeta_session 的窗口：会话 id → 窗口 id。tmux 没在跑时是空的。
func deskWindows() map[string]string {
	out, err := tmuxRun("list-windows", "-a", "-F", "#{window_id} #{@zeta_session}")
	windows := map[string]string{}
	if err != nil {
		return windows
	}
	for _, line := range strings.Split(out, "\n") {
		if window, session, ok := strings.Cut(line, " "); ok && session != "" {
			windows[session] = window
		}
	}
	return windows
}

// deskTitle 是窗口名：标题压成一行，最多 24 个字；没有标题时用会话 id 的前 8 位。
func deskTitle(title, id string) string {
	if t := runePrefix(squash(title), 24); t != "" {
		return t
	}
	return id[:8]
}

// shellQuote 给 sh 用的单引号写法。
func shellQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'" }

// cmdDeskOpen 输出一行 {"window": 窗口 id}；这台机器没有 tmux 时是 null（见 deskOpen）。
func cmdDeskOpen(e *env, o *emitter, id, cwd string) error {
	window, err := deskOpen(e, id, cwd)
	if err != nil {
		return err
	}
	o.out(obj{"window": window})
	return nil
}

// deskOpen 给会话 id 在 tmux 会话 zeta 里开窗口，已经有就不动；给出窗口 id，这台机器没有 tmux 时是 nil。
// 窗口名先是会话 id 的前 8 位，desk 读到标题后改。zeta-helper desk-open 和核心的 sessions.desk_open 都用它。
func deskOpen(e *env, id, cwd string) (*string, error) {
	if !tmuxPresent() {
		return nil, nil
	}
	if window, ok := deskWindows()[id]; ok {
		return &window, nil
	}
	command := shellQuote(e.home+"/.cache/"+e.name+"/bin/zeta-helper") + " desk " + shellQuote(id) + " " + shellQuote(cwd)
	args := []string{"new-session", "-d", "-s", deskSession}
	if _, err := tmuxRun("has-session", "-t", "="+deskSession); err == nil {
		args = []string{"new-window", "-d", "-t", "=" + deskSession + ":"}
	}
	args = append(args, "-P", "-F", "#{window_id}", "-n", deskTitle("", id), "-c", cwd, command)
	window, err := tmuxRun(args...)
	if err != nil {
		return nil, fmt.Errorf("tmux 开不了窗口：%w", err)
	}
	// 窗口名不跟着里面跑的程序变（claude 起来后不会改成“claude”）
	_, _ = tmuxRun("set-option", "-w", "-t", window, "@zeta_session", id)
	_, _ = tmuxRun("set-option", "-w", "-t", window, "automatic-rename", "off")
	return &window, nil
}

// cmdDeskClose 输出一行 {"closed": 关了几个}。
func cmdDeskClose(o *emitter, ids []string) error {
	o.out(obj{"closed": deskClose(ids)})
	return nil
}

// deskClose 关掉这些会话在 tmux 会话 zeta 里的窗口，给出关了几个；没有 tmux、没有窗口时是 0。
// zeta-helper desk-close 和核心的 sessions.delete、sessions.archive、sessions.desk_close 都用它。
func deskClose(ids []string) int {
	windows := deskWindows()
	closed := 0
	for _, id := range ids {
		if window, ok := windows[id]; ok {
			if _, err := tmuxRun("kill-window", "-t", window); err == nil {
				closed++
			}
		}
	}
	return closed
}

// deskGone：会话记录没了，或很久没动过（deskStale），窗口就不用留了。记录要在第一条消息之后才写出来，
// 窗口可能开得比它早，所以 seen（见过记录）之前没有记录不算没了，只在过了 deskFirstRecord 还没有时才算。
// 还在时 file 是会话记录的路径（还没写出来时是空串）。
func (e *env) deskGone(id string, seen *bool, opened time.Time) (file string, gone bool) {
	files := e.sessionFilesNamed(id)
	if len(files) == 0 {
		return "", *seen || time.Since(opened) > deskFirstRecord
	}
	*seen = true
	fi, err := os.Stat(files[0])
	return files[0], err == nil && time.Since(fi.ModTime()) > deskStale
}

// waitEnter 等终端里的一次回车，最多等 limit；回车了为真，到时或标准输入结束（窗口关了）为假，eof 区分这两种。
// 用 select 等，不留着一个读标准输入的 goroutine：claude 起来以后终端的输入全归它。
func waitEnter(limit time.Duration) (entered, eof bool) {
	var fds syscall.FdSet
	fds.Bits[0] = 1 // fd 0
	tv := syscall.NsecToTimeval(limit.Nanoseconds())
	n, err := syscall.Select(1, &fds, nil, nil, &tv)
	if err != nil || n == 0 {
		return false, false
	}
	buf := make([]byte, 4096)
	k, err := os.Stdin.Read(buf)
	return k > 0, k == 0 || err == io.EOF
}

// cmdDesk 是窗口里的循环，窗口关了、会话没了或旧了才返回。
func cmdDesk(e *env, w io.Writer, id, cwd string) error {
	// 终端里按 Ctrl+C 只结束这个窗口；claude 跑着的时候 SIGINT 归它（交接也靠它），这里不跟着退出
	interrupts := make(chan os.Signal, 1)
	seen, opened := false, time.Now()
	var titles titleFollower
	shown, prompt := "", true
	for {
		file, gone := e.deskGone(id, &seen, opened)
		if gone {
			fmt.Fprintln(w, "这个会话已经删除或很久没用，窗口关掉。")
			return nil
		}
		if title := titles.read(file); title != shown || prompt {
			if title != shown {
				renameOwnWindow(deskTitle(title, id))
			}
			shown, prompt = title, false
			fmt.Fprintf(w, "\n%s\n按回车在终端里接管会话（claude --resume %s）；Ctrl+C 关掉这个窗口。\n", deskHeader(title, id), id)
		}
		entered, eof := waitEnter(deskTick)
		if eof {
			return nil
		}
		if !entered || !e.deskRelease(w, id) {
			continue
		}
		signal.Notify(interrupts, syscall.SIGINT)
		runResume(w, id, cwd)
		signal.Stop(interrupts)
		prompt = true
	}
}

// renameOwnWindow 把这个程序所在的 tmux 窗口改名（TMUX_PANE 是 tmux 给窗口里的程序设的）。
func renameOwnWindow(name string) {
	if pane := os.Getenv("TMUX_PANE"); pane != "" {
		_, _ = tmuxRun("rename-window", "-t", pane, name)
	}
}

// deskHeader 是提示的第一行：会话的标题，没有时是会话 id 的前 8 位。
func deskHeader(title, id string) string {
	if title != "" {
		return "「" + title + "」"
	}
	return "会话 " + id[:8]
}

// deskRelease 让 Zeta 那边放手：正忙时说一声再等，等太久就回到提示。
func (e *env) deskRelease(w io.Writer, id string) bool {
	if _, busy := e.releaseSession(id, 0); !busy {
		return true
	}
	fmt.Fprintln(w, "Zeta 一侧的本轮回答尚未结束，结束后自动接管……")
	if _, busy := e.releaseSession(id, deskReleaseWait); busy {
		fmt.Fprintln(w, "等待超时，Zeta 一侧仍在回答。请稍后再按回车。")
		return false
	}
	return true
}

// runResume 在这个窗口里接着会话：经用户的交互 shell 跑 claude，shell 里给 claude 定义的函数（比如选账号）照常生效。
func runResume(w io.Writer, id, cwd string) {
	shell := os.Getenv("SHELL")
	if shell == "" {
		shell = "/bin/bash"
	}
	cmd := exec.Command(shell, "-ic", `claude --resume "$1"`, "zeta-desk", id)
	cmd.Dir = cwd
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		if _, exited := err.(*exec.ExitError); !exited {
			fmt.Fprintf(w, "起不了 claude：%v\n", err)
		}
	}
}
