package agyacct

import (
	"bufio"
	"cmp"
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

	"github.com/dzshzx/Zeta/helper/locales"
)

// 登录错误代号（与 coreapi.CodeLoginIncomplete / CodeNoLogin 一致）。
const (
	CodeLoginIncomplete = "login_incomplete"
	CodeNoLogin         = "no_login"
)

const (
	loginURLWait    = 30 * time.Second
	loginCodeWait   = time.Minute
	loginLife       = 15 * time.Minute
	complaintSettle = time.Second
)

var (
	googleAuthURL = regexp.MustCompile(`https://accounts\.google\.com/\S+`)
	anyHTTPSURL   = regexp.MustCompile(`https://\S+`)
)

// LoginCommandFunc 生成执行 `agy acp --login` 的子进程；单测可替换。
var LoginCommandFunc = defaultLoginCommand

func defaultLoginCommand(home string) *exec.Cmd {
	if home == "" {
		home = "/"
	}
	cmd := exec.Command("bash", "-lc", `export PATH="$PATH:$HOME/.local/bin"; BROWSER=true exec "${ZETA_AGY_BIN:-agy}" acp --login`)
	cmd.Dir = home
	env := make([]string, 0, len(os.Environ())+2)
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(kv, "HOME=") && !strings.HasPrefix(kv, "BROWSER=") {
			env = append(env, kv)
		}
	}
	cmd.Env = append(env, "HOME="+home, "BROWSER=true")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	return cmd
}

// LoginProblem 是带协议错误代号的登录错误。
type LoginProblem struct {
	ErrCode string
	Err     error
}

func (e LoginProblem) Error() string { return e.Err.Error() }
func (e LoginProblem) Code() string  { return e.ErrCode }
func (e LoginProblem) Unwrap() error { return e.Err }

// LoginFlow 管理单个正在进行的 Google OAuth 远程登录流程。
type LoginFlow struct {
	mu  sync.Mutex
	cur *loginRun
}

type loginRun struct {
	name       string
	host       *Host
	cmd        *exec.Cmd
	stdin      io.WriteCloser
	url        chan string
	urlOnce    sync.Once
	complaints chan string
	done       chan struct{}
	life       *time.Timer

	mu      sync.Mutex
	lastErr string
}

// Active 判断当前是否有正在等待授权码的登录子进程。
func (f *LoginFlow) Active() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.cur != nil
}

// Start 归档现有登录并启动 `agy acp --login`，返回 `{url, name}`。
func (f *LoginFlow) Start(ctx context.Context, h *Host) (map[string]string, error) {
	f.Cancel()
	name, err := h.PrepareLogin(ctx)
	if err != nil {
		return nil, err
	}
	run, err := startAgyLogin(h, name)
	if err != nil {
		h.restorePendingLogin()
		return nil, err
	}
	select {
	case u := <-run.url:
		f.mu.Lock()
		f.cur = run
		f.mu.Unlock()
		run.life = time.AfterFunc(loginLife, func() {
			if f.end(run) {
				h.restorePendingLogin()
			}
		})
		return map[string]string{"url": u, "name": name}, nil
	case <-run.done:
		h.restorePendingLogin()
		return nil, run.failure(locales.Err("errLoginNoURL"))
	case <-time.After(loginURLWait):
		run.kill()
		h.restorePendingLogin()
		return nil, locales.Err("errLoginURLTimeout")
	}
}

// Submit 向等待中的 `agy acp --login` 提交授权码或完整回调 URL，成功后归档为新账号并返回 `{name}`。
func (f *LoginFlow) Submit(_ context.Context, h *Host, code string) (map[string]string, error) {
	f.mu.Lock()
	run := f.cur
	f.mu.Unlock()
	if run == nil {
		return nil, LoginProblem{CodeNoLogin, locales.Err("errLoginEnded")}
	}
	code = strings.TrimSpace(code)
	if code == "" {
		return nil, LoginProblem{CodeLoginIncomplete, locales.Err("errAgyLoginCodeEmpty")}
	}
	for len(run.complaints) > 0 {
		<-run.complaints
	}
	if _, err := io.WriteString(run.stdin, code+"\n"); err != nil {
		f.end(run)
		h.restorePendingLogin()
		return nil, LoginProblem{CodeNoLogin, locales.Err("errLoginExited")}
	}
	if err := f.waitLoginResult(run); err != nil {
		var lp LoginProblem
		if !errors.As(err, &lp) || lp.ErrCode != CodeLoginIncomplete {
			h.restorePendingLogin()
		}
		return nil, err
	}
	saved, err := h.finishLogin(run.name)
	if err != nil {
		h.restorePendingLogin()
		return nil, err
	}
	return map[string]string{"name": saved}, nil
}

func (f *LoginFlow) waitLoginResult(run *loginRun) error {
	select {
	case <-run.done:
	case line := <-run.complaints:
		select {
		case <-run.done:
		case <-time.After(complaintSettle):
			return LoginProblem{CodeLoginIncomplete, errors.New(line)}
		}
	case <-time.After(loginCodeWait):
		f.end(run)
		return locales.Err("errLoginCodeTimeout")
	}
	f.end(run)
	if run.cmd.ProcessState == nil || run.cmd.ProcessState.ExitCode() != 0 {
		return run.failure(locales.Err("errLoginFailed"))
	}
	return nil
}

// Cancel 终止正在进行的登录流程，并恢复登录前活跃的账号或中转。
func (f *LoginFlow) Cancel() {
	f.mu.Lock()
	run := f.cur
	f.mu.Unlock()
	if run != nil && f.end(run) && run.host != nil {
		run.host.restorePendingLogin()
	}
}

func (f *LoginFlow) end(run *loginRun) bool {
	f.mu.Lock()
	wasCur := f.cur == run
	if wasCur {
		f.cur = nil
	}
	f.mu.Unlock()
	if run.life != nil {
		run.life.Stop()
	}
	run.kill()
	return wasCur
}

func startAgyLogin(h *Host, name string) (*loginRun, error) {
	cmd := LoginCommandFunc(h.Home)
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
		name:       name,
		host:       h,
		cmd:        cmd,
		stdin:      stdin,
		url:        make(chan string, 1),
		complaints: make(chan string, 16),
		done:       make(chan struct{}),
	}
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); run.scanStream(stdout, false) }()
	go func() { defer wg.Done(); run.scanStream(stderr, true) }()
	go func() {
		wg.Wait()
		_ = cmd.Wait()
		close(run.done)
	}()
	return run, nil
}

func (r *loginRun) scanStream(rd io.Reader, isErr bool) {
	br := bufio.NewReader(rd)
	var lineBuf strings.Builder
	var allBuf strings.Builder
	for {
		b, err := br.ReadByte()
		if err != nil {
			r.trySendURL(allBuf.String())
			if isErr {
				r.recordComplaint(lineBuf.String())
			}
			return
		}
		allBuf.WriteByte(b)
		if b == '\n' {
			r.trySendURL(allBuf.String())
			if isErr {
				r.recordComplaint(lineBuf.String())
			}
			lineBuf.Reset()
			continue
		}
		lineBuf.WriteByte(b)
		if b == '>' || b == ':' || b == ' ' {
			r.trySendURL(allBuf.String())
		}
	}
}

func (r *loginRun) trySendURL(text string) {
	u := cmp.Or(googleAuthURL.FindString(text), anyHTTPSURL.FindString(text))
	u = strings.TrimRight(u, ".,;)>'\"")
	if u == "" || (!strings.Contains(text, "\n") && !strings.HasSuffix(text, " ") && !strings.HasSuffix(text, ">") && !strings.HasSuffix(text, ":")) {
		return
	}
	r.urlOnce.Do(func() { r.url <- u })
}

func (r *loginRun) recordComplaint(raw string) {
	line := strings.TrimSpace(raw)
	if line == "" || anyHTTPSURL.MatchString(line) {
		return
	}
	r.mu.Lock()
	r.lastErr = line
	r.mu.Unlock()
	select {
	case r.complaints <- line:
	default:
	}
}

func (r *loginRun) failure(fallback error) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.lastErr == "" {
		return fallback
	}
	if strings.Contains(strings.ToLower(r.lastErr), "invalid") || strings.Contains(strings.ToLower(r.lastErr), "rejected") {
		return locales.Err("errLoginRejected")
	}
	return errors.New(r.lastErr)
}

func (r *loginRun) kill() {
	if r.cmd != nil && r.cmd.Process != nil {
		if r.cmd.SysProcAttr != nil && r.cmd.SysProcAttr.Setpgid {
			_ = syscall.Kill(-r.cmd.Process.Pid, syscall.SIGKILL)
		} else {
			_ = r.cmd.Process.Kill()
		}
	}
}
