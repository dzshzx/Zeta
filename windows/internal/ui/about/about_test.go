package about

import (
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/hub/appversion"
	"github.com/dzshzx/Zeta/windows/internal/update"
	"github.com/egoist/mygo/ui"
)

// updates 是测试里的更新功能：状态直接摆，记下点了什么。
type updates struct {
	enabled bool
	status  update.Status
	calls   []string
}

func (u *updates) Enabled() bool         { return u.enabled }
func (u *updates) Status() update.Status { return u.status }
func (u *updates) Check()                { u.calls = append(u.calls, "check") }
func (u *updates) Update()               { u.calls = append(u.calls, "update") }
func (u *updates) Cancel()               { u.calls = append(u.calls, "cancel") }

type prefs struct{ auto bool }

func (p *prefs) AutoUpdateCheck() bool        { return p.auto }
func (p *prefs) SetAutoUpdateCheck(on bool)   { p.auto = on }
func (p *prefs) UpdateCheckedAt() time.Time   { return time.Time{} }
func (p *prefs) SetUpdateCheckedAt(time.Time) {}
func (p *prefs) UpdateNotified() int          { return 0 }
func (p *prefs) SetUpdateNotified(int)        {}

var release = appversion.Version{Code: 800, Name: "0.7.5"}

func manifest() *update.Manifest {
	return &update.Manifest{
		VersionCode: 812, VersionName: "0.7.6", PublishedAt: "2026-11-21T08:00:00Z",
		Notes: "- feat: 桌面版自己检查更新\n- fix: 修好一处",
		MSI:   &update.Package{File: "Zeta-v0.7.6.msi", Size: 12 << 20, SHA256: strings.Repeat("a", 64)},
	}
}

func newPage(v appversion.Version, st update.Status) (*Page, *updates, *prefs, *ui.Tester) {
	u := &updates{enabled: v.Release(), status: st}
	pr := &prefs{auto: true}
	p := &Page{Version: v, Updates: u, Prefs: pr}
	tt := ui.NewTester(func(c *ui.Context) { ui.Scroll(c).Fill().Children(func() { p.View(c) }) }, 720, 2000)
	return p, u, pr, tt
}

func TestDevelopmentBuild(t *testing.T) {
	_, _, _, tt := newPage(appversion.Version{Code: 815, Name: "0.7.7-dev.3+gabc1234"}, update.Status{})
	for _, s := range []string{"版本", "0.7.7-dev.3+gabc1234", "开发版"} {
		if !tt.HasText(s) {
			t.Errorf("no %q in %q", s, tt.Texts())
		}
	}
	for _, s := range []string{"检查更新", "自动检查更新"} {
		if shows(tt, s) {
			t.Errorf("a development build shows %q", s)
		}
	}
}

func TestCheck(t *testing.T) {
	_, u, pr, tt := newPage(release, update.Status{})
	if tt.HasText("开发版") || !tt.HasText("0.7.5") {
		t.Errorf("texts %q", tt.Texts())
	}
	if err := tt.Click("检查更新"); err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(u.calls, []string{"check"}) {
		t.Errorf("calls %q", u.calls)
	}

	u.status = update.Status{Phase: update.Checking}
	tt.Frame()
	if !tt.HasText("正在检查…") {
		t.Errorf("checking: %q", tt.Texts())
	}
	tt.Click("检查更新") // 正在查时点不了
	if len(u.calls) != 1 {
		t.Errorf("checked again while checking: %q", u.calls)
	}
	u.status = update.Status{Phase: update.CheckFailed, Reason: "连 github.com 超时；连 gh-proxy.com 超时"}
	tt.Frame()
	if !tt.HasText("检查失败") || !tt.HasText("连 github.com 超时；连 gh-proxy.com 超时") {
		t.Errorf("failed: %q", tt.Texts())
	}
	u.status = update.Status{Phase: update.UpToDate}
	tt.Frame()
	if !tt.HasText("已是最新版本") {
		t.Errorf("up to date: %q", tt.Texts())
	}

	toggle, ok := tt.AccessByRole(ui.RoleSwitch, "自动检查更新")
	if !ok || !toggle.Checked {
		t.Fatalf("switch %+v, found %v", toggle, ok)
	}
	tt.ClickAt(toggle.Bounds.X+toggle.Bounds.W/2, toggle.Bounds.Y+toggle.Bounds.H/2)
	if pr.auto {
		t.Error("the switch did not turn automatic checks off")
	}
}

func TestAvailable(t *testing.T) {
	_, u, _, tt := newPage(release, update.Status{Phase: update.Available, Manifest: manifest()})
	for _, s := range []string{"有新版本 0.7.6", "Zeta 0.7.6", "12.0 MB · 2026-11-21 发布", "更新说明", "安装时 Zeta 会关闭，完成后自动重新打开。"} {
		if !tt.HasText(s) {
			t.Errorf("no %q in %q", s, tt.Texts())
		}
	}
	if !strings.Contains(strings.Join(tt.Texts(), "\n"), "桌面版自己检查更新") {
		t.Errorf("no notes in %q", tt.Texts())
	}
	tt.Click("检查更新") // 已有新版本时点不了
	if err := tt.Click("更新"); err != nil {
		t.Fatal(err)
	}

	u.status.Step, u.status.Done = update.Downloading, 3<<20
	tt.Frame()
	if !tt.HasText("正在下载 3.0 MB / 12.0 MB") {
		t.Errorf("downloading: %q", tt.Texts())
	}
	if bar, ok := tt.AccessByRole(ui.RoleProgress, "下载进度"); !ok || bar.Now != 0.25 {
		t.Errorf("progress %+v, found %v", bar, ok)
	}
	if err := tt.Click("取消"); err != nil {
		t.Fatal(err)
	}

	u.status.Step, u.status.Reason = update.Failed, "从 github.com 下的文件 sha256 和清单不一致，已删掉"
	tt.Frame()
	if !tt.HasText(u.status.Reason) {
		t.Errorf("failed: %q", tt.Texts())
	}
	if err := tt.Click("重试"); err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(u.calls, []string{"update", "cancel", "update"}) {
		t.Errorf("calls %q", u.calls)
	}

	for step, text := range map[update.Step]string{update.Verifying: "正在校验", update.Installing: "正在安装"} {
		u.status.Step = step
		tt.Frame()
		if !tt.HasText(text) || shows(tt, "更新") || shows(tt, "取消") || shows(tt, "重试") {
			t.Errorf("%s: %q", text, tt.Texts())
		}
	}
}

func TestLicenses(t *testing.T) {
	_, _, _, tt := newPage(release, update.Status{})
	for _, s := range []string{"开源许可", "Zeta", "Windows 程序链接的", "github.com/egoist/mygo v0.10.0", "带到主机上的 zeta-helper、claude-switch 链接的"} {
		if !tt.HasText(s) {
			t.Errorf("no %q in %q", s, tt.Texts())
		}
	}
	if err := tt.Click("github.com/egoist/mygo v0.10.0"); err != nil {
		t.Fatal(err)
	}
	tt.Advance(time.Second)
	if !strings.Contains(strings.Join(tt.Texts(), "\n"), "Copyright (c) 2026 MyGo contributors") {
		t.Errorf("the MIT text did not open: %q", tt.Texts())
	}
}

// 许可证清单覆盖 Windows 程序和主机上的程序链接的每个模块：assets.sh 按实际链接的生成，这里再按 go list 对一遍，
// 免得 gen/ 是旧的（加了依赖没重跑 assets.sh）。
func TestLicensesCoverLinkedModules(t *testing.T) {
	list, err := licenses()
	if err != nil {
		t.Fatal(err)
	}
	have := map[string]bool{}
	for _, l := range list {
		have[l.Where+" "+l.Module+" "+l.Version] = true
		if text := l.Text(); strings.HasPrefix(text, "读不出") || len(text) < 200 {
			t.Errorf("%s %s: license text %q", l.Module, l.Version, text)
		}
	}
	for _, m := range linked(t, "../../..", "windows", "./...") {
		if !have["app "+m] {
			t.Errorf("the Windows program links %s, missing from the licenses; run hub/scripts/assets.sh", m)
		}
	}
	for _, m := range linked(t, "../../../../helper", "linux", "./...", "tool") {
		if !have["host "+m] {
			t.Errorf("the host programs link %s, missing from the licenses; run hub/scripts/assets.sh", m)
		}
	}
	if !have["app Go "+goVersion(t)] || !have["host Go "+goVersion(t)] {
		t.Errorf("no Go licence for %s", goVersion(t))
	}
	if !strings.Contains(zetaLicense(), "GNU GENERAL PUBLIC LICENSE") {
		t.Error("Zeta's own licence is not the GPL")
	}
}

// 包所在目录里另有许可证的（像 mygo 的 internal/shader 是 Apache-2.0），那个包也列在关于页里。
func TestPackageLicenses(t *testing.T) {
	list, err := licenses()
	if err != nil {
		t.Fatal(err)
	}
	have := map[string]bool{}
	for _, l := range list {
		have[l.Where+" "+l.Module+" "+l.Version] = true
	}
	out := goList(t, "../../..", "windows", "-f",
		"{{if not .Standard}}{{with .Module}}{{if not .Main}}{{$.ImportPath}}\t{{.Version}}\t{{$.Dir}}\t{{.Dir}}{{end}}{{end}}{{end}}", "./...")
	for _, line := range strings.Split(strings.TrimSpace(out), "\n") {
		f := strings.Split(line, "\t")
		if len(f) != 4 || f[2] == f[3] {
			continue
		}
		for _, pattern := range []string{"LICENSE*", "LICENCE*", "COPYING*"} {
			if matches, _ := filepath.Glob(filepath.Join(f[2], pattern)); len(matches) > 0 && !have["app "+f[0]+" "+f[1]] {
				t.Errorf("%s has its own licence %q, missing from the licenses; run hub/scripts/assets.sh", f[0], matches)
			}
		}
	}
}

func linked(t *testing.T, dir, goos string, patterns ...string) []string {
	args := append([]string{"-f", "{{if not .Standard}}{{with .Module}}{{if not .Main}}{{.Path}} {{.Version}}{{end}}{{end}}{{end}}"}, patterns...)
	var out []string
	for _, line := range strings.Split(goList(t, dir, goos, args...), "\n") {
		// Zeta 自己的模块（经 replace 引用的 helper）算在仓根 LICENSE 里，同 assets.sh
		if line != "" && !strings.HasPrefix(line, "github.com/dzshzx/Zeta/") && !slices.Contains(out, line) {
			out = append(out, line)
		}
	}
	if len(out) == 0 {
		t.Fatalf("go list found no modules in %s", dir)
	}
	return out
}

func goList(t *testing.T, dir, goos string, args ...string) string {
	t.Helper()
	cmd := exec.Command(goBin(t), append([]string{"list", "-deps"}, args...)...)
	cmd.Dir = dir
	cmd.Env = append(cmd.Environ(), "GOOS="+goos, "GOARCH=amd64", "CGO_ENABLED=0")
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("go list in %s: %v", dir, err)
	}
	return string(out)
}

func goVersion(t *testing.T) string {
	out, err := exec.Command(goBin(t), "env", "GOVERSION").Output()
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSpace(string(out))
}

func goBin(t *testing.T) string {
	t.Helper()
	gobin, err := exec.LookPath("go")
	if err != nil {
		t.Fatalf("要 PATH 上的 go：%v", err)
	}
	return gobin
}

// shows 说界面上有没有正好是 s 的一段字（HasText 是包含，「自动检查更新」里也有「检查更新」）。
func shows(tt *ui.Tester, s string) bool { return slices.Contains(tt.Texts(), s) }
