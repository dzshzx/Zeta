package claudecfg

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// 测试在临时 HOME 里真读写文件，要用登录环境的（claude、环境变量、stdio 服务器）真跑 bash -lc。

var fakeClaude string // TestMain 编好的 testdata/fakeclaude

func TestMain(m *testing.M) {
	os.Exit(runTests(m))
}

func runTests(m *testing.M) int {
	for _, tool := range []string{"bash", "git"} {
		if _, err := exec.LookPath(tool); err != nil {
			fmt.Fprintf(os.Stderr, "测试要用 %s，本机没有\n", tool)
			return 1
		}
	}
	dir, err := os.MkdirTemp("", "claudecfg-test-")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer os.RemoveAll(dir)
	fakeClaude = filepath.Join(dir, "claude")
	build := exec.Command("go", "build", "-o", fakeClaude, "./testdata/fakeclaude")
	build.Stdout, build.Stderr = os.Stderr, os.Stderr
	if err := build.Run(); err != nil {
		fmt.Fprintln(os.Stderr, "编不出假的 claude：", err)
		return 1
	}
	return m.Run()
}

// newHost 是一台新的“主机”：临时 HOME，登录配置（先打一行字；把 ~/.local/bin 放进 PATH，那里是假的 claude）。
func newHost(t *testing.T) (string, *Store) {
	t.Helper()
	// 假的 claude 由 TestMain 编，那时 go test 的缓存还没开始记：这里打开它的源码目录，改了它测试才重跑
	if _, err := os.ReadDir("testdata/fakeclaude"); err != nil {
		t.Fatal(err)
	}
	home := t.TempDir()
	write(t, filepath.Join(home, ".profile"), "echo 欢迎登录\nexport PATH=\"$HOME/.local/bin:$PATH\"\nexport TOOL_PREFIX=fake_\n")
	bin := filepath.Join(home, ".local", "bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(fakeClaude, filepath.Join(bin, "claude")); err != nil {
		t.Fatal(err)
	}
	return home, New(home)
}

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func read(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func exists(path string) bool {
	_, err := os.Lstat(path)
	return err == nil
}
