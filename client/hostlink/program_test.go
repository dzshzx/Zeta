package hostlink

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"sync"
	"testing"
)

// 几处同时装同一个程序（几个对话同时起来、同时调核心）时都装得上，装完只剩程序和不带版本的链接。
// 链接要先建在临时名字上再改名换过去：ln -sfn 是先删后建，同时装时会撞上别人刚建的链接而失败。
func TestConcurrentInstallsAllSucceed(t *testing.T) {
	p := &Program{Name: ZetaHelper, Version: "0123456789abcdef"}
	binary := []byte("#!/bin/sh\necho ok\n")
	command := p.InstallCommand("zeta-test", binary)
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
				c.Stdin = bytes.NewReader(binary)
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
	dir := filepath.Join(home, ".cache/zeta-test/bin")
	if link, err := os.Readlink(filepath.Join(dir, ZetaHelper)); err != nil || link != ZetaHelper+"-"+p.Version {
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
	if want := []string{ZetaHelper, ZetaHelper + "-" + p.Version}; !slices.Equal(names, want) {
		t.Errorf("装完留下 %q，要 %q（临时文件、临时链接都不留）", names, want)
	}
}
