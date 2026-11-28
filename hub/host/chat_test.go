package host

import "testing"

func TestCheckDirExplainsWhyItCannotEnter(t *testing.T) {
	b := newBox(t)
	b.file(t, "proj/a.txt", "x", 0o644)
	devices := &Devices{Links: b.links}
	for path, want := range map[string]string{
		b.server.Home + "/proj":       "",
		b.server.Home + "/proj/a.txt": b.server.Home + "/proj/a.txt 是文件，不是目录",
		b.server.Home + "/没有 '这个'":    "「" + b.host.Name() + "」上不存在目录 " + b.server.Home + "/没有 '这个'",
	} {
		got, err := devices.CheckDir(b.host, path)
		if err != nil || got != want {
			t.Errorf("%s：%q、%v，要 %q", path, got, err, want)
		}
	}
	if got := DirProblem("Welcome!\nzeta_dir denied\n", "box", "/srv"); got != "无权限访问「box」上的 /srv" {
		t.Errorf("没有权限：%q", got)
	}
}
