package mobile

import (
	"archive/zip"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/hub/hubtest"
	"github.com/dzshzx/Zeta/hub/runlog"
)

// 运行日志：新的在前，按类别、轻重筛，能清空。
func TestDiagLog(t *testing.T) {
	c, _ := newCore(t)
	if call[LogView](t, c, "diaglog", nil).Available {
		t.Fatal("没开运行日志时说有")
	}
	c.log = runlog.Open(t.TempDir(), nil)
	c.log.Add(runlog.Info, runlog.App, "启动")
	c.log.Add(runlog.Error, runlog.Chat, "断了")
	v := call[LogView](t, c, "diaglog", nil)
	if !v.Available || len(v.Entries) != 2 || v.Entries[0].Message != "断了" || len(v.Topics) != 3 || len(v.Levels) != 3 {
		t.Fatalf("全部 %+v", v)
	}
	if v := call[LogView](t, c, "diaglog", map[string]any{"topic": string(runlog.App)}); len(v.Entries) != 1 || v.Entries[0].Message != "启动" {
		t.Fatalf("按类别 %+v", v.Entries)
	}
	if v := call[LogView](t, c, "diaglog", map[string]any{"level": int(runlog.Warn)}); len(v.Entries) != 1 || v.Entries[0].Level != 2 {
		t.Fatalf("按轻重 %+v", v.Entries)
	}
	call[map[string]bool](t, c, "diaglog.clear", nil)
	if v := call[LogView](t, c, "diaglog", nil); len(v.Entries) != 0 {
		t.Fatalf("清空后 %+v", v.Entries)
	}
}

// 诊断包：环境.txt 开头是界面给的，带上各台设备的日志（核心打过码）和本机 diag/ 下的文件（这里打码）。
func TestDiagExport(t *testing.T) {
	c, d := newCore(t)
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(call hubtest.Call) (json.RawMessage, error) {
			switch call.Method {
			case "diag.files":
				return json.RawMessage(`{"files":[{"kind":"debug","path":"/h/.claude/debug/a.txt","size":3}]}`), nil
			case "diag.tail":
				return json.RawMessage(`{"text":"调试","size":3}`), nil
			}
			return json.RawMessage(`{}`), nil
		})
	}
	addBareDevice(t, c)
	c.dir = t.TempDir()
	c.log = runlog.Open(filepath.Join(c.dir, "diag"), nil)
	c.log.Add(runlog.Warn, runlog.Device, "password=fake-pass-1")

	got := call[map[string]string](t, c, "diag.export", map[string]any{"header": "Zeta iOS 版诊断包",
		"env": []map[string]string{{"Name": "版本", "Value": "0.7.7"}}})
	defer os.Remove(got["path"])
	zr, err := zip.OpenReader(got["path"])
	if err != nil {
		t.Fatal(err)
	}
	defer zr.Close()
	files := map[string]string{}
	for _, f := range zr.File {
		r, _ := f.Open()
		b, _ := io.ReadAll(r)
		r.Close()
		files[f.Name] = string(b)
	}
	if env := files["环境.txt"]; !strings.HasPrefix(env, "Zeta iOS 版诊断包\n") || !strings.Contains(env, "0.7.7") {
		t.Fatalf("环境.txt %q", env)
	}
	if files["me@box/debug/a.txt"] != "调试" {
		t.Fatalf("设备的日志 %v", files)
	}
	local := files["本机/"+runlog.FileName]
	if local == "" || strings.Contains(local, "fake-pass-1") {
		t.Fatalf("本机的运行日志 %q", local)
	}
}

// 关于：版本、源码链接、许可清单（iOS 和设备上两组）；许可全文只读清单里的。
func TestAbout(t *testing.T) {
	c, _ := newCore(t)
	a := call[About](t, c, "about", nil)
	if a.Problem != "" || a.URL == "" || len(a.Licenses) == 0 {
		t.Fatalf("关于 %+v", a)
	}
	for _, l := range a.Licenses {
		if l.Where != "ios" && l.Where != "host" {
			t.Fatalf("列了 %+v", l)
		}
	}
	if text := call[map[string]string](t, c, "license", map[string]string{"file": a.Licenses[0].File})["text"]; text == "" {
		t.Fatal("许可全文是空的")
	}
	if text := call[map[string]string](t, c, "license", map[string]string{"file": "Zeta"})["text"]; !strings.Contains(text, "GNU") {
		t.Fatal("Zeta 的许可不对")
	}
	failed(t, c, "license", map[string]string{"file": "../index.tsv"})
}
