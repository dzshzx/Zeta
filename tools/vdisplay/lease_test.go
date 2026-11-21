package main

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// lockEnv 是去掉占用者和排队号之后的环境，再加上 extra：测试自己可能就跑在设了这两个变量的会话里。
func lockEnv(extra ...string) []string {
	var env []string
	for _, e := range os.Environ() {
		if !strings.HasPrefix(e, "ZETA_PHONE_OWNER=") && !strings.HasPrefix(e, "ZETA_PHONE_TICKET=") {
			env = append(env, e)
		}
	}
	return append(env, extra...)
}

// 谁能碰哪个包由 tools/phone-lock 定。正式版只给写明了 ZETA_PHONE_OWNER=main 的调用：没设占用者的不算主会话，
// phone-run 里（有排队号）也不给。测试版没设占用者时照常算主会话，子代理只在 phone-run 里拿得到。
func TestPhoneLockCheck(t *testing.T) {
	const owner, ticket = "ZETA_PHONE_OWNER=", "ZETA_PHONE_TICKET=7"
	cases := []struct {
		kind string
		env  []string
		ok   bool
	}{
		{"main", nil, false},
		{"main", []string{owner}, false},
		{"main", []string{owner + "task"}, false},
		{"main", []string{owner + "task", ticket}, false},
		{"main", []string{owner + "main", ticket}, false},
		{"main", []string{ticket}, false},
		{"main", []string{owner + "main"}, true},
		{"emu", nil, true},
		{"dev", []string{owner + "main"}, true},
		{"emu", []string{owner + "task"}, false},
		{"emu", []string{owner + "task", ticket}, true},
	}
	for _, c := range cases {
		cmd := exec.Command("../phone-lock", "--check", c.kind)
		cmd.Env = lockEnv(c.env...)
		out, err := cmd.CombinedOutput()
		if (err == nil) != c.ok {
			t.Errorf("phone-lock --check %s with %q: allowed=%v, want %v\n%s", c.kind, c.env, err == nil, c.ok, out)
		}
	}
}

// 带 --release 时 connect 先问 phone-lock --check main，不拿租约的两处（手机 Debian 里、phone-run 里）也问，
// 被拒绝就带着它的退出码退出；测试版在 phone-run 里不问。子进程里跑 connect：它失败时直接退出。
func TestReleaseAsksPhoneLock(t *testing.T) {
	if dir := os.Getenv("VD_TEST_TOOLS"); dir != "" {
		v := &vd{tools: dir, dev: os.Getenv("VD_TEST_RELEASE") == "", onPhone: os.Getenv("VD_TEST_ON_PHONE") != ""}
		v.connect()
		return
	}
	const refuse = "#!/bin/sh\necho \"$*\" >>\"$0.log\"\n[ \"$1\" = --check ] && exit 2\nexit 0\n"
	cases := []struct {
		name   string
		env    []string
		asked  string
		status int
	}{
		{"release on the phone", []string{"VD_TEST_RELEASE=1", "VD_TEST_ON_PHONE=1"}, "--check main\n", 2},
		{"release in phone-run", []string{"VD_TEST_RELEASE=1", "ZETA_PHONE_TICKET=7"}, "--check main\n", 2},
		{"release on the workstation", []string{"VD_TEST_RELEASE=1"}, "--check main\n", 2},
		{"test build in phone-run", []string{"ZETA_PHONE_TICKET=7"}, "", 0},
	}
	for _, c := range cases {
		dir := t.TempDir()
		lock := filepath.Join(dir, "phone-lock")
		if err := os.WriteFile(lock, []byte(refuse), 0o755); err != nil {
			t.Fatal(err)
		}
		cmd := exec.Command(os.Args[0], "-test.run=^TestReleaseAsksPhoneLock$")
		cmd.Env = append(lockEnv(c.env...), "VD_TEST_TOOLS="+dir)
		out, err := cmd.CombinedOutput()
		status := 0
		var exit *exec.ExitError
		if errors.As(err, &exit) {
			status = exit.ExitCode()
		} else if err != nil {
			t.Fatal(err)
		}
		asked, _ := os.ReadFile(lock + ".log")
		if status != c.status || string(asked) != c.asked {
			t.Errorf("%s: exit %d after asking phone-lock %q, want exit %d after %q\n%s", c.name, status, asked, c.status, c.asked, out)
		}
	}
}

// 手机 Debian 里 install.sh 不拿租约，装正式版照样先过 phone-lock：没写明主会话就在下载之前停下（退出码 2），
// 写明了才走到下载。uname 和 gh 换成假的：前者报手机的内核，后者一被调用就以 9 退出。
func TestInstallReleaseOnPhoneAsksPhoneLock(t *testing.T) {
	bin := t.TempDir()
	for name, body := range map[string]string{"uname": "echo 6.6.0-android15-8", "gh": "exit 9"} {
		if err := os.WriteFile(filepath.Join(bin, name), []byte("#!/bin/sh\n"+body+"\n"), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	for owner, want := range map[string]int{"": 2, "task": 2, "main": 9} {
		cmd := exec.Command("../install.sh", "--release")
		cmd.Env = append(lockEnv("PATH="+bin+":"+os.Getenv("PATH")), "ZETA_PHONE_OWNER="+owner)
		if owner == "" {
			cmd.Env = cmd.Env[:len(cmd.Env)-1]
		}
		out, err := cmd.CombinedOutput()
		var exit *exec.ExitError
		if !errors.As(err, &exit) || exit.ExitCode() != want {
			t.Errorf("install.sh --release on the phone with owner %q: %v, want exit %d\n%s", owner, err, want, out)
		}
	}
}
