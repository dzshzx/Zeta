package core

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"syscall"
	"time"
)

// startWait 是拉起核心后最多等多久它听上。
var startWait = 5 * time.Second

// maxLog：日志超过这么大时，下次拉起核心前清空（核心起不来、反复拉起时不至于一直长）。
const maxLog = 1 << 20

// Connect 连上 p 那个核心，读掉它的 core_hello，返回连接和它报的 Info。没人在听时拉起一个（exe core：新会话 setsid，
// 标准输入输出接 /dev/null，标准错误追加到 p.Log），等它听上，最多等 startWait。几个 serve 同时拉起时由核心的锁保证只留一个，
// 其余的看到有人在听就退出，大家连上的是同一个。
func Connect(p Paths, exe string) (net.Conn, Info, error) {
	if c, err := net.Dial("unix", p.Socket); err == nil {
		return greeted(c)
	}
	exited, err := start(p, exe)
	if err != nil {
		return nil, Info{}, err
	}
	deadline := time.Now().Add(startWait)
	for {
		c, err := net.Dial("unix", p.Socket)
		if err == nil {
			return greeted(c)
		}
		select {
		case werr := <-exited:
			// 退出码 0 是别人的核心先听上了，接着连；不是 0 的是起不来
			if werr != nil {
				return nil, Info{}, fmt.Errorf("core exited (%v), see %s", werr, p.Log)
			}
			exited = nil
		default:
		}
		if time.Now().After(deadline) {
			return nil, Info{}, fmt.Errorf("core is not listening on %s after %v (see %s): %w", p.Socket, startWait, p.Log, err)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// greeted 读掉核心连上时发的 core_hello。一个字节一个字节地读到换行，不多读：之后的行由调用方自己读。
func greeted(c net.Conn) (net.Conn, Info, error) {
	_ = c.SetReadDeadline(time.Now().Add(startWait))
	var line []byte
	b := make([]byte, 1)
	for len(line) < 4096 {
		if _, err := c.Read(b); err != nil {
			_ = c.Close()
			return nil, Info{}, fmt.Errorf("core said nothing: %w", err)
		}
		if b[0] == '\n' {
			break
		}
		line = append(line, b[0])
	}
	_ = c.SetReadDeadline(time.Time{})
	var hello struct {
		Type string `json:"type"`
		Info
	}
	if err := json.Unmarshal(line, &hello); err != nil || hello.Type != "core_hello" {
		_ = c.Close()
		return nil, Info{}, fmt.Errorf("core did not greet: %q", line)
	}
	return c, hello.Info, nil
}

// start 拉起 exe core，返回它退出时送来结果的通道（顺带收掉退出的子进程）。
func start(p Paths, exe string) (<-chan error, error) {
	if err := p.makeDir(); err != nil {
		return nil, err
	}
	flags := os.O_WRONLY | os.O_CREATE | os.O_APPEND
	if st, err := os.Stat(p.Log); err == nil && st.Size() > maxLog {
		flags |= os.O_TRUNC
	}
	log, err := os.OpenFile(p.Log, flags, 0o600)
	if err != nil {
		return nil, err
	}
	defer log.Close()
	cmd := exec.Command(exe, "core")
	cmd.Dir = "/"
	cmd.Stderr = log
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start core: %w", err)
	}
	exited := make(chan error, 1)
	go func() { exited <- cmd.Wait() }()
	return exited, nil
}
