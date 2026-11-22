package host

import (
	"os"
	"testing"
)

func TestCheckDirExplainsWhyItCannotEnter(t *testing.T) {
	b := newBox(t)
	b.file(t, "proj/a.txt", "x", 0o644)
	devices := &Devices{Links: b.links}
	for path, want := range map[string]string{
		b.server.Home + "/proj":       "",
		b.server.Home + "/proj/a.txt": b.server.Home + "/proj/a.txt 是文件，不是目录",
		b.server.Home + "/没有 '这个'":    "「" + b.host.Name() + "」上没有 " + b.server.Home + "/没有 '这个' 这个目录",
	} {
		got, err := devices.CheckDir(b.host, path)
		if err != nil || got != want {
			t.Errorf("%s：%q、%v，要 %q", path, got, err, want)
		}
	}
	if got := DirProblem("Welcome!\nzeta_dir denied\n", "box", "/srv"); got != "「box」上的 /srv 没有权限进去" {
		t.Errorf("没有权限：%q", got)
	}
}

func TestHostnameAsksTheDevice(t *testing.T) {
	b := newBox(t)
	want, err := os.Hostname() // 测试的 SSH 服务器在本机上跑命令
	if err != nil {
		t.Fatal(err)
	}
	got, err := (&Devices{Links: b.links}).Hostname(b.host)
	if err != nil || got != want {
		t.Errorf("主机名 %q、%v", got, err)
	}
}
