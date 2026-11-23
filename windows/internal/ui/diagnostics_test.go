package ui

import (
	"archive/zip"
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/helper/redact"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

// openDiag 加一台 box，在真 serve 的临时家目录里摆好诊断页要读的日志，打开诊断页、等读到清单：一个在跑的对话（带标题，
// 错误输出里有个假 token）、一份结束的、一个调试日志。假值见仓根 .gitleaks-test-values.toml。在跑的那份等核心起来以后再摆：
// 核心收到第一个请求时才起来，起来时扫一遍托管目录，进程不在了的（这里本来就没有进程）当成结束的删掉，所以先问它一次。
func openDiag(t *testing.T, f *settingsFixture) {
	t.Helper()
	f.withHosts(t, "box")
	showDiag(t, f)
}

// showDiag 是 openDiag 加好设备以后的几步（预览图里设备已经加好）。
func showDiag(t *testing.T, f *settingsFixture) {
	t.Helper()
	f.click(t, "设置")
	if _, err := f.serve.Call(context.Background(), "diag.files", nil); err != nil {
		t.Fatal(err)
	}
	f.serve.Write(t, ".cache/zeta/hosts/k1/err.log", "MCP 连不上：Authorization: Bearer fake-token-1\nboom\n")
	f.serve.Write(t, ".cache/zeta/hosts/k1/title", "修登录")
	f.serve.Write(t, ".cache/zeta/stderr/old.log", "# zeta: claude 退出码 1\nError: spawn npx ENOENT\n")
	f.serve.Write(t, ".claude/debug/d1.txt", "[DEBUG] started\n")
	f.click(t, "诊断")
	f.until(t, "读到日志清单", func() bool { return f.shows("修登录") })
}

func TestDiagnosticsPage(t *testing.T) {
	f := newSettingsFixture(t)
	openDiag(t, f)
	f.see(t, "对话进程的错误输出", "Claude Code 调试日志", "old.log", "d1.txt", "导出诊断包")
	f.notSee(t, "box · 对话进程的错误输出") // 只有一台时组名不带设备名

	// 点开在跑的那个：看到的是原文（看日志不打码），Esc 先回到清单
	f.click(t, "修登录")
	raw := "MCP 连不上：Authorization: Bearer fake-token-1\nboom\n"
	f.until(t, "读到内容", func() bool { return f.shows(raw) })
	f.see(t, "复制", "刷新")
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	if f.app.page != pageDiag || !f.shows("old.log") {
		t.Fatalf("Esc 应回到清单，现在是 %q：%q", f.app.page, f.tt.Texts())
	}
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	if f.app.page != pageHome {
		t.Fatalf("再按 Esc 回到对话，现在是 %q", f.app.page)
	}
}

func TestDiagnosticsDisconnectedDevice(t *testing.T) {
	f := newSettingsFixture(t)
	hosts := f.withHosts(t, "box", "lab")
	if err := f.hub.SetConnected(hosts[1].ID, false); err != nil {
		t.Fatal(err)
	}
	f.click(t, "设置")
	f.click(t, "诊断")
	f.until(t, "读到 box 的清单", func() bool { return f.shows("box · 对话进程的错误输出") })
	f.see(t, "lab · Claude Code 日志", "这台设备的「连接」关着，打开连接后才能看。", "对话进程没有往标准错误写过东西", "没有调试日志")
}

func TestDiagnosticsExport(t *testing.T) {
	f := newSettingsFixture(t)
	// 这台电脑上的安装记录：msiexec 写的是带 BOM 的 UTF-16
	local := t.TempDir()
	if err := os.WriteFile(filepath.Join(local, "msi-install.log"), utf16le("安装开始 password=fake-pass-1\r\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	f.app.DiagDir = local
	var saved []byte
	f.app.saveFile = func(title, name string, filters []mygo.FileFilter, data []byte) (string, error) {
		if title != "导出诊断包" || !strings.HasPrefix(name, "zeta-diag-") || !strings.HasSuffix(name, ".zip") {
			t.Errorf("保存对话框：%q %q", title, name)
		}
		saved = data
		return `C:\Users\someone\Downloads\` + name, nil
	}
	openDiag(t, f)
	f.click(t, "导出诊断包")
	f.until(t, "存好", func() bool { return f.showsPrefix(`已保存到 C:\Users\someone\Downloads\zeta-diag-`) })

	files := unzip(t, saved)
	names := make([]string, 0, len(files))
	for name := range files {
		names = append(names, name)
	}
	slices.Sort(names)
	want := []string{"box/debug/d1.txt", "box/finished/old.log", "box/running/k1.log", "本机/msi-install.log", "环境.txt"}
	if !slices.Equal(names, want) {
		t.Fatalf("包里的文件：%q，要 %q", names, want)
	}
	if s := files["box/running/k1.log"]; strings.Contains(s, "fake-token-1") || !strings.Contains(s, redact.Mask) || !strings.Contains(s, "boom") {
		t.Errorf("在跑的错误输出要打码：%q", s)
	}
	if s := files["本机/msi-install.log"]; s != "安装开始 password="+redact.Mask+"\r\n" {
		t.Errorf("安装记录要转成 UTF-8 并打码：%q", s)
	}
	env := files["环境.txt"]
	for _, s := range []string{"Zeta 版本：0.8.0（80）", "设备 box：地址 box", "界面缩放："} {
		if !strings.Contains(env, s) {
			t.Errorf("环境里没有 %q：%s", s, env)
		}
	}
}

// showsPrefix：有一段字以 text 开头。
func (f *fixture) showsPrefix(text string) bool {
	for _, s := range f.tt.Texts() {
		if strings.HasPrefix(s, text) {
			return true
		}
	}
	return false
}

func utf16le(s string) []byte {
	out := []byte{0xFF, 0xFE}
	for _, r := range s {
		out = append(out, byte(r), byte(r>>8)) // 测试里的字都在基本平面
	}
	return out
}

func unzip(t *testing.T, data []byte) map[string]string {
	t.Helper()
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	out := map[string]string{}
	for _, zf := range zr.File {
		r, err := zf.Open()
		if err != nil {
			t.Fatal(err)
		}
		b, err := io.ReadAll(r)
		_ = r.Close()
		if err != nil {
			t.Fatal(err)
		}
		out[zf.Name] = string(b)
	}
	return out
}
