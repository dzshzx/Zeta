// Package cfgtest 是测试用的 claudecfg.Caller：在本机的临时 HOME 里起一个真的 zeta-helper serve（从仓里的 helper/
// 源码编），经它的标准输入输出收发 config.* 请求。PATH 最前面放 helper 测试里冒充 claude 的那个程序，不碰本机真的
// claude。只给测试用。
package cfgtest

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"testing"
)

var (
	buildOnce sync.Once
	helperBin string
	binDir    string // 里面的 claude 是 helper/claudecfg/testdata/fakeclaude
	buildErr  error
)

// helper 编一次 zeta-helper，放在临时目录里（测试进程结束前一直用这一份）。
func helper() (string, error) {
	buildOnce.Do(func() {
		src, err := helperSource()
		if err != nil {
			buildErr = err
			return
		}
		dir, err := os.MkdirTemp("", "cfgtest-")
		if err != nil {
			buildErr = err
			return
		}
		helperBin, binDir = filepath.Join(dir, "zeta-helper"), filepath.Join(dir, "bin")
		out, err := exec.Command("go", "build", "-C", src, "-o", helperBin, ".").CombinedOutput()
		if err != nil {
			buildErr = fmt.Errorf("编不出 zeta-helper：%v\n%s", err, out)
			return
		}
		out, err = exec.Command("go", "build", "-C", src, "-o", filepath.Join(binDir, "claude"), "./claudecfg/testdata/fakeclaude").CombinedOutput()
		if err != nil {
			buildErr = fmt.Errorf("编不出冒充 claude 的程序：%v\n%s", err, out)
		}
	})
	return helperBin, buildErr
}

// helperSource 从测试的工作目录（包所在的目录）往上找仓里的 helper/。
func helperSource() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "helper", "go.mod")); err == nil {
			return filepath.Join(dir, "helper"), nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", errors.New("往上找不到仓里的 helper/")
		}
		dir = parent
	}
}

// Serve 是一个跑在临时 HOME 里的 zeta-helper serve。
type Serve struct {
	Home string

	mu      sync.Mutex
	in      io.WriteCloser
	next    int
	waiting map[int]chan reply
	calls   []string // 发过的方法
}

type reply struct {
	OK     bool            `json:"ok"`
	Error  string          `json:"error"`
	Result json.RawMessage `json:"result"`
}

// Start 起一个 serve；测试结束时关掉。缺 go、编不出 helper 时测试失败。
func Start(t testing.TB) *Serve {
	t.Helper()
	bin, err := helper()
	if err != nil {
		t.Fatal(err)
	}
	s := &Serve{Home: t.TempDir(), waiting: map[int]chan reply{}}
	cmd := exec.Command(bin, "serve")
	cmd.Env = []string{"HOME=" + s.Home, "PATH=" + binDir + string(os.PathListSeparator) + os.Getenv("PATH"), "LANG=C.UTF-8"}
	cmd.Dir = s.Home
	in, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	s.in = in
	t.Cleanup(func() {
		_ = in.Close()
		_ = cmd.Wait()
	})
	lines := bufio.NewScanner(out)
	lines.Buffer(make([]byte, 64<<10), 64<<20)
	if !lines.Scan() {
		t.Fatal("serve 没打招呼")
	}
	go s.read(lines)
	return s
}

func (s *Serve) read(lines *bufio.Scanner) {
	for lines.Scan() {
		var m struct {
			Type string `json:"type"`
			ID   int    `json:"id"`
			reply
		}
		if json.Unmarshal(lines.Bytes(), &m) != nil || m.Type != "zeta_reply" {
			continue
		}
		s.mu.Lock()
		ch := s.waiting[m.ID]
		delete(s.waiting, m.ID)
		s.mu.Unlock()
		if ch != nil {
			ch <- m.reply
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, ch := range s.waiting {
		close(ch)
		delete(s.waiting, id)
	}
}

// Call 见 claudecfg.Caller。
func (s *Serve) Call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	req := map[string]any{}
	if params != nil {
		raw, err := json.Marshal(params)
		if err != nil {
			return nil, err
		}
		if err := json.Unmarshal(raw, &req); err != nil {
			return nil, errors.New("参数不是 JSON 对象")
		}
	}
	ch := make(chan reply, 1)
	s.mu.Lock()
	s.next++
	id := s.next
	s.waiting[id] = ch
	s.calls = append(s.calls, method)
	s.mu.Unlock()
	maps.Copy(req, map[string]any{"id": id, "method": method})
	line, _ := json.Marshal(req)
	s.mu.Lock()
	_, err := s.in.Write(append(line, '\n'))
	s.mu.Unlock()
	if err != nil {
		return nil, errors.New("serve 已经退出")
	}
	select {
	case r, ok := <-ch:
		if !ok {
			return nil, errors.New("serve 已经退出")
		}
		if !r.OK {
			return nil, errors.New(r.Error)
		}
		return r.Result, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// Calls 是到现在发过的方法，按顺序。
func (s *Serve) Calls() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.calls...)
}

// Write 在临时 HOME 下写一个文件（path 相对 HOME）。
func (s *Serve) Write(t testing.TB, path, content string) {
	t.Helper()
	full := filepath.Join(s.Home, path)
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// Read 读临时 HOME 下的一个文件；不在时为空。
func (s *Serve) Read(path string) string {
	b, _ := os.ReadFile(filepath.Join(s.Home, path))
	return string(b)
}
