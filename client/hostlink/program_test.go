package hostlink

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
)

// installFixture 是两种语言的安装命令共同核对的那一份输入：两份空程序（版本是空内容的哈希）装到 testName 下，传的是 installBinary。
var (
	installFixture = NewProgram(ZetaHelper, map[string][]byte{"arm64": nil, "amd64": nil})
	installBinary  = []byte("#!/bin/sh\necho ok\n")
)

// 安装命令和手机版 HelperProgram.installCommand 拼出来一字不差（HelperProgramTest 读同一个文件核对）。
// 改了命令就照这里报的重写 testdata/install-command.txt，Kotlin 那份跟着改到也对得上。
func TestInstallCommandMatchesKotlin(t *testing.T) {
	want, err := os.ReadFile("testdata/install-command.txt")
	if err != nil {
		t.Fatal(err)
	}
	if got := installFixture.InstallCommand(testName, installBinary); got != strings.TrimSuffix(string(want), "\n") {
		t.Errorf("安装命令变了，照这个改 testdata/install-command.txt：\n%s", got)
	}
}

// 几处同时装同一个程序（几个对话同时起来、同时调核心）时都装得上，装完只剩程序和不带版本的链接（原因见 InstallCommand）。
func TestConcurrentInstallsAllSucceed(t *testing.T) {
	p := installFixture
	command := p.InstallCommand(testName, installBinary)
	home := t.TempDir()
	const rounds, together = 10, 16
	for range rounds {
		start := make(chan struct{})
		errs := make(chan error, together)
		var wg sync.WaitGroup
		for range together {
			wg.Go(func() {
				<-start
				c := exec.Command("bash", "-c", command)
				c.Env = append(os.Environ(), "HOME="+home)
				c.Stdin = bytes.NewReader(installBinary)
				if out, err := c.CombinedOutput(); err != nil {
					errs <- fmt.Errorf("%v: %s", err, out)
				}
			})
		}
		close(start)
		wg.Wait()
		close(errs)
		for err := range errs {
			t.Fatal(err)
		}
	}
	installed := filepath.Join(home, p.RelativePath(testName))
	dir := filepath.Dir(installed)
	if link, err := os.Readlink(filepath.Join(dir, ZetaHelper)); err != nil || link != filepath.Base(installed) {
		t.Errorf("链接：%q %v", link, err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	if want := []string{ZetaHelper, filepath.Base(installed)}; !slices.Equal(names, want) {
		t.Errorf("装完留下 %q，要 %q（临时文件、临时链接都不留）", names, want)
	}
}
