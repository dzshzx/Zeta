package main

import (
	"bufio"
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/dzshzx/Zeta/helper/accounts"
	"github.com/dzshzx/Zeta/helper/locales"
)

// 在这台主机上登录一个新的 Claude 账号（accounts.login_start、accounts.login_code、accounts.login_cancel，见 docs/core.md
// 「账号与额度」）。`claude auth login` 不要终端：它打印授权网址，再从标准输入读授权码。核心起它、把网址交给客户端，
// 客户端只管打开网址、收用户贴回来的授权码，手机版和桌面版共用这一份。
//
// 实测（Claude Code 2.1.330）：网址在标准输出「If the browser didn't open, visit:」的下一行，之后是不换行的提示
// 「Paste code here if prompted > 」；授权码少了 # 和后半段时它在错误输出抱怨一句、接着等输入；完整却不对的码让它
// 在错误输出说「Couldn't log in: That code wasn't accepted…」并以 1 退出；对的码以 0 退出，登录状态已经写好。
// 它还会用环境变量 BROWSER 指的程序去打开网页，所以起它时把 BROWSER 设成 true，主机上不弹浏览器。

const (
	loginURLWait  = 30 * time.Second // 起登录命令后最多等多久见到网址
	loginCodeWait = time.Minute      // 交了授权码后最多等多久见到结果
	loginLife     = 15 * time.Minute // 开始登录后这么久还没交成授权码就结束它
	// complaintSettle：错误输出说了一句以后等这么久看它退不退出
	complaintSettle = time.Second
)

// 交授权码时认得出的出错代号（客户端据此决定是留在输入框还是回到开始）。
const (
	codeLoginIncomplete = "login_incomplete" // 授权码不完整，登录命令还在等：改了再交
	codeNoLogin         = "no_login"         // 没有在等授权码的登录（没开始、已结束或超时）：重新开始
)

var loginURL = regexp.MustCompile(`https://\S+`)

// logins 是这个核心里正在进行的登录，同时只有一个：再开始一次时先结束上一个。
var logins loginFlow

type loginFlow struct {
	mu  sync.Mutex
	cur *loginRun
}

// loginRun 是一次登录命令。
type loginRun struct {
	cmd        *exec.Cmd
	stdin      io.WriteCloser
	url        chan string   // 读到网址时送一次
	complaints chan string   // 进程还活着时错误输出的每一行
	done       chan struct{} // 进程退出后关上
	life       *time.Timer

	mu      sync.Mutex
	lastErr string // 错误输出最后一句不空的
}

// active：有登录在等授权码（核心这时不算闲着，见 accountsHandler.Idle）。
func (f *loginFlow) active() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.cur != nil
}

// start 先把在用的号交给 claude-switch 存档并退出登录（同 accounts.prepare_login，存不下就不登录，免得丢了它的令牌），
// 再起登录命令、等到网址。返回网址和登录成功后新号会记成的名字。
func (f *loginFlow) start(ctx context.Context, e *env, h *accounts.Host) (map[string]string, error) {
	f.cancel()
	name, err := h.PrepareLogin(ctx)
	if err != nil {
		return nil, err
	}
	run, err := startLogin(e)
	if err != nil {
		return nil, err
	}
	select {
	case url := <-run.url:
		f.mu.Lock()
		f.cur = run
		f.mu.Unlock()
		run.life = time.AfterFunc(loginLife, func() { f.end(run) })
		return map[string]string{"url": url, "name": name}, nil
	case <-run.done:
		return nil, run.failure(locales.Err("errLoginNoURL"))
	case <-time.After(loginURLWait):
		run.kill()
		return nil, locales.Err("errLoginURLTimeout")
	}
}

// submit 把授权码交给在等的登录命令，等它的结果：成功时让 claude-switch 记下新号，返回它的名字。
func (f *loginFlow) submit(ctx context.Context, h *accounts.Host, code string) (map[string]string, error) {
	f.mu.Lock()
	run := f.cur
	f.mu.Unlock()
	if run == nil {
		return nil, codedError{codeNoLogin, locales.Err("errLoginEnded")}
	}
	code = strings.TrimSpace(code)
	if !strings.Contains(code, "#") {
		return nil, codedError{codeLoginIncomplete, errLoginIncomplete}
	}
	for len(run.complaints) > 0 { // 上一次交的码留下的抱怨不算这一次的
		<-run.complaints
	}
	if _, err := io.WriteString(run.stdin, code+"\n"); err != nil {
		f.end(run)
		return nil, codedError{codeNoLogin, locales.Err("errLoginExited")}
	}
	select {
	case <-run.done:
	case line := <-run.complaints:
		// 码不对时它也先在错误输出说一句、紧接着退出：等一会儿，还活着才是在等改过的码
		select {
		case <-run.done:
		case <-time.After(complaintSettle):
			return nil, codedError{codeLoginIncomplete, explainLogin(line)}
		}
	case <-time.After(loginCodeWait):
		f.end(run)
		return nil, locales.Err("errLoginCodeTimeout")
	}
	f.end(run)
	if run.cmd.ProcessState.ExitCode() != 0 {
		return nil, run.failure(locales.Err("errLoginFailed"))
	}
	name, err := h.Save(ctx)
	if err != nil {
		return nil, err
	}
	return map[string]string{"name": name}, nil
}

// cancel 结束正在进行的登录（没有时什么也不做）。在用的号已经存档，账号页里能换回去。
func (f *loginFlow) cancel() {
	f.mu.Lock()
	run := f.cur
	f.mu.Unlock()
	if run != nil {
		f.end(run)
	}
}

// end 结束 run，它还是当前的登录时清掉。
func (f *loginFlow) end(run *loginRun) {
	f.mu.Lock()
	if f.cur == run {
		f.cur = nil
	}
	f.mu.Unlock()
	if run.life != nil {
		run.life.Stop()
	}
	run.kill()
}

// startLogin 在登录 shell 里起 claude auth login（同 claudeCommand 的环境），读它的输出。
func startLogin(e *env) (*loginRun, error) {
	home := e.home
	if home == "" {
		home = "/"
	}
	cmd := exec.Command("bash", "-lc", `PATH="$PATH:$HOME/.local/bin"; exec claude auth login`)
	cmd.Dir = home
	// BROWSER：claude auth login 用它打开授权网页（实测 2.1.330 认它）。网页要在客户端那边打开，主机上有图形界面时也别弹
	cmd.Env = append(os.Environ(), "HOME="+home, "BROWSER=true")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, locales.Err("errLoginStart", "detail", err.Error())
	}
	run := &loginRun{
		cmd: cmd, stdin: stdin,
		url: make(chan string, 1), complaints: make(chan string, 16), done: make(chan struct{}),
	}
	var reading sync.WaitGroup
	reading.Add(2)
	go func() { defer reading.Done(); run.readURL(stdout) }()
	go func() { defer reading.Done(); run.readErrors(stderr) }()
	go func() {
		reading.Wait() // Wait 前要读完管道
		_ = cmd.Wait()
		close(run.done)
	}()
	return run, nil
}

// readURL 读标准输出到结束：提示符那一行没有换行，所以见到换行或「>」就看一次读到的里面有没有网址。
func (r *loginRun) readURL(out io.Reader) {
	br := bufio.NewReader(out)
	var seen strings.Builder
	sent := false
	for {
		c, err := br.ReadByte()
		if err != nil {
			return
		}
		seen.WriteByte(c)
		if !sent && (c == '\n' || c == '>') {
			if url := loginURL.FindString(seen.String()); url != "" {
				r.url <- url
				sent = true
			}
		}
	}
}

// readErrors 读错误输出：每一行记作最后一句，进程还活着时也送给等授权码的那边。
func (r *loginRun) readErrors(errOut io.Reader) {
	sc := bufio.NewScanner(errOut)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		r.mu.Lock()
		r.lastErr = line
		r.mu.Unlock()
		select {
		case r.complaints <- line:
		default:
		}
	}
}

// failure 是登录命令失败时给人看的原因：错误输出的最后一句（常见的换成中文），没有时用 fallback。
func (r *loginRun) failure(fallback error) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.lastErr == "" {
		return fallback
	}
	return explainLogin(r.lastErr)
}

func (r *loginRun) kill() {
	if r.cmd.Process != nil {
		_ = syscall.Kill(-r.cmd.Process.Pid, syscall.SIGKILL)
	}
}

var errLoginIncomplete = locales.Err("errLoginIncomplete")

// explainLogin 把登录命令几句常见的英文提示换成核心文字表里的话（按客户端的语言出字），别的原样给出。
func explainLogin(line string) error {
	switch {
	case strings.Contains(line, "full code"):
		return errLoginIncomplete
	case strings.Contains(line, "wasn't accepted"):
		return locales.Err("errLoginRejected")
	}
	return errors.New(line)
}
