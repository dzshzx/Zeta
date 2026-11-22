package host

import (
	"bytes"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
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

func TestImageReadsAPictureFromTheTranscript(t *testing.T) {
	b := newBox(t)
	program, err := LoadProgram(hostlink.ZetaHelper)
	if err != nil {
		t.Fatal(err)
	}
	// 第 0 行第 1 块是一张 PNG（数据是 "png!" 的 base64）
	file := b.file(t, ".claude/projects/-w/s1.jsonl",
		`{"type":"user","message":{"content":[{"type":"text","text":"看图"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"cG5nIQ=="}}]}}`+"\n", 0o644)
	devices := &Devices{Links: b.links, Helper: program}
	data, err := devices.Image(b.host, file, "0.1")
	if err != nil || !bytes.Equal(data, []byte("png!")) {
		t.Fatalf("取到 %q、%v", data, err)
	}
	if _, err := devices.Image(b.host, file, "0.0"); err == nil {
		t.Error("不是图片的块也取到了")
	}
}
