package update

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// prefs 是测试里的设置。
type prefs struct {
	mu        sync.Mutex
	auto      bool
	checkedAt time.Time
	notified  int
}

func (p *prefs) AutoUpdateCheck() bool      { return p.auto }
func (p *prefs) SetAutoUpdateCheck(on bool) { p.auto = on }
func (p *prefs) UpdateCheckedAt() time.Time {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.checkedAt
}
func (p *prefs) SetUpdateCheckedAt(t time.Time) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.checkedAt = t
}
func (p *prefs) UpdateNotified() int     { return p.notified }
func (p *prefs) SetUpdateNotified(n int) { p.notified = n }

// source 是测试里的清单和下载：下载时依次报 progress 里的进度，再写下 body（或返回 err）。
type source struct {
	manifest  *Manifest
	err       error
	body      string
	progress  []int64
	downloads int
	// gate 不为 nil 时，报完进度后等它关上（或下载被取消）再写文件。
	gate chan struct{}
}

func (s *source) Manifest(context.Context) (*Manifest, error) { return s.manifest, s.err }

func (s *source) Download(ctx context.Context, p Package, dest string, progress func(int64)) error {
	s.downloads++
	for _, n := range s.progress {
		progress(n)
	}
	if s.gate != nil {
		select {
		case <-s.gate:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	if s.err != nil {
		return s.err
	}
	return os.WriteFile(dest, []byte(s.body), 0o644)
}

const msiBody = "a new msi"

func newManifest(code int) *Manifest {
	return &Manifest{VersionCode: code, VersionName: "0.7.6", MSI: &Package{
		File: "Zeta-v0.7.6.msi", URL: "https://example.com/Zeta-v0.7.6.msi", Size: int64(len(msiBody)), SHA256: digest(msiBody),
	}}
}

// fixture 是一个正式版 0.7.5（versionCode 800）的 Updater，记下它提示、安装、退出了什么。
type fixture struct {
	*Updater
	src       *source
	prefs     *prefs
	notes     []string
	installed []string
	exited    bool
	installer error
}

var noon = time.Date(2026, 11, 22, 12, 0, 0, 0, time.Local)

func newFixture(t *testing.T, m *Manifest) *fixture {
	f := &fixture{src: &source{manifest: m, body: msiBody}, prefs: &prefs{auto: true}}
	f.Updater = &Updater{
		Version: Version{Code: 800, Name: "0.7.5"},
		Dir:     filepath.Join(t.TempDir(), "update"),
		Source:  f.src,
		Prefs:   f.prefs,
		Notify:  func(s string) { f.notes = append(f.notes, s) },
		Install: func(msi string) error { f.installed = append(f.installed, msi); return f.installer },
		Exit:    func() { f.exited = true },
		Now:     func() time.Time { return noon },
	}
	return f
}

func (f *fixture) do(action func()) Status {
	action()
	f.Wait()
	return f.Status()
}

func TestStartChecksOncePerDay(t *testing.T) {
	f := newFixture(t, newManifest(812))
	os.MkdirAll(f.Dir, 0o755)
	os.WriteFile(filepath.Join(f.Dir, "Zeta-v0.7.5.msi"), []byte("left over"), 0o644)

	st := f.do(f.Start)
	if st.Phase != Available || st.Manifest.VersionCode != 812 || st.Step != Ready {
		t.Errorf("status %+v", st)
	}
	if len(f.notes) != 1 || f.notes[0] != "Zeta 0.7.6 已发布，可在设置中更新" || f.prefs.notified != 812 {
		t.Errorf("notes %q, notified %d", f.notes, f.prefs.notified)
	}
	if !f.prefs.checkedAt.Equal(noon) {
		t.Errorf("checked at %v", f.prefs.checkedAt)
	}
	assertOnly(t, f.Dir) // 上次留下的安装包删掉了

	// 同一天再启动不查；第二天查，同一个版本不再提示
	again := newFixture(t, newManifest(812))
	again.prefs.checkedAt, again.prefs.notified = noon.Add(-11*time.Hour), 812
	if st := again.do(again.Start); st.Phase != Idle {
		t.Errorf("checked twice a day: %+v", st)
	}
	again.prefs.checkedAt = noon.Add(-13 * time.Hour)
	if st := again.do(again.Start); st.Phase != Available || len(again.notes) != 0 {
		t.Errorf("next day: %+v, notes %q", st, again.notes)
	}
}

func TestStartRespectsSettingAndVersion(t *testing.T) {
	off := newFixture(t, newManifest(812))
	off.prefs.auto = false
	if st := off.do(off.Start); st.Phase != Idle {
		t.Errorf("auto check off: %+v", st)
	}

	dev := newFixture(t, newManifest(812))
	dev.Version = Version{Code: 815, Name: "0.7.7-dev.3+gabc1234"}
	dev.do(dev.Start)
	dev.do(dev.Check)
	if st := dev.Status(); st.Phase != Idle || dev.Enabled() || !dev.prefs.checkedAt.IsZero() {
		t.Errorf("a development build checked: %+v", st)
	}
}

func TestCheck(t *testing.T) {
	for _, tc := range []struct {
		name  string
		src   source
		phase Phase
	}{
		{"newer", source{manifest: newManifest(812)}, Available},
		{"same", source{manifest: newManifest(800)}, UpToDate},
		{"no msi", source{manifest: &Manifest{VersionCode: 900, VersionName: "0.8.0"}}, UpToDate},
		{"failed", source{err: errors.New("连不上 github.com")}, CheckFailed},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t, nil)
			*f.src = tc.src
			var changes int
			f.Changed = func() { changes++ }
			st := f.do(f.Check)
			if st.Phase != tc.phase || changes != 2 {
				t.Errorf("status %+v after %d changes", st, changes)
			}
			if tc.phase == CheckFailed && st.Reason != "连不上 github.com" {
				t.Errorf("reason %q", st.Reason)
			}
			if len(f.notes) != 0 {
				t.Errorf("a manual check notified: %q", f.notes)
			}
		})
	}
}

func TestUpdateInstallsAndExits(t *testing.T) {
	f := newFixture(t, newManifest(812))
	f.src.progress = []int64{1, 2, 9}
	var steps []Step
	f.Changed = func() { steps = append(steps, f.Status().Step) }
	f.do(f.Check)
	steps = nil

	f.do(f.Update)
	msi := filepath.Join(f.Dir, "Zeta-v0.7.6.msi")
	if len(f.installed) != 1 || f.installed[0] != msi || !f.exited {
		t.Errorf("installed %q, exited %v", f.installed, f.exited)
	}
	want := []Step{Verifying, Downloading, Downloading, Downloading, Verifying, Installing}
	if !equalSteps(steps, want) {
		t.Errorf("steps %v, want %v", steps, want)
	}

	// 上次下好的同一个文件不再下
	again := newFixture(t, newManifest(812))
	again.do(again.Check)
	os.MkdirAll(again.Dir, 0o755)
	os.WriteFile(filepath.Join(again.Dir, "Zeta-v0.7.6.msi"), []byte(msiBody), 0o644)
	again.do(again.Update)
	if again.src.downloads != 0 || len(again.installed) != 1 {
		t.Errorf("downloaded %d times, installed %q", again.src.downloads, again.installed)
	}
}

func equalSteps(a, b []Step) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestUpdateFails(t *testing.T) {
	f := newFixture(t, newManifest(812))
	f.do(f.Check)
	f.src.err = errors.New("从 github.com 下的文件 sha256 和清单不一致，已删掉")
	st := f.do(f.Update)
	if st.Step != Failed || st.Reason != f.src.err.Error() || len(f.installed) != 0 || f.exited {
		t.Errorf("status %+v, installed %q", st, f.installed)
	}

	// 重试；安装程序起不来时留在失败，不退出
	f.src.err = nil
	f.installer = errors.New("启动安装程序失败：拒绝访问")
	st = f.do(f.Update)
	if st.Step != Failed || st.Reason != "启动安装程序失败：拒绝访问" || f.exited || len(f.installed) != 1 {
		t.Errorf("status %+v, exited %v", st, f.exited)
	}
}

func TestCancel(t *testing.T) {
	f := newFixture(t, newManifest(812))
	f.do(f.Check)
	f.src.gate = make(chan struct{})
	f.src.progress = []int64{5}
	downloading := make(chan struct{}, 1)
	f.Changed = func() {
		if st := f.Status(); st.Step == Downloading && st.Done == 5 {
			downloading <- struct{}{}
		}
	}
	f.Update()
	<-downloading
	f.Update() // 正在下载时再点不另起一次
	f.Cancel()
	st := f.do(func() {})
	if st.Phase != Available || st.Step != Ready || st.Done != 0 || f.src.downloads != 1 || len(f.installed) != 0 {
		t.Errorf("status %+v, downloads %d", st, f.src.downloads)
	}

	f.src.gate, f.src.progress, f.Changed = nil, nil, nil
	if st := f.do(f.Update); !f.exited {
		t.Errorf("after cancel: %+v", st)
	}
}

func TestDownloadDir(t *testing.T) {
	if got := DownloadDir("/home/u/.config/Zeta"); got != filepath.Join("/home/u/.config/Zeta", "update") {
		t.Errorf("DownloadDir = %q", got)
	}
	// Windows 上在 %LOCALAPPDATA% 下：正式版的路径不变，测试版另一个目录
	local := `C:\Users\u\AppData\Local`
	release, dev := Version{812, "0.7.6"}, Version{815, "0.7.7-dev.3+gabc1234"}
	for _, tc := range []struct {
		v    Version
		want string
	}{
		{release, filepath.Join(local, "Zeta", "update")},
		{dev, filepath.Join(local, "Zeta Dev", "update")},
	} {
		if got := downloadDir("windows", local, `C:\x`, tc.v); got != tc.want {
			t.Errorf("downloadDir(%+v) = %q, want %q", tc.v, got, tc.want)
		}
	}
	if got := downloadDir("windows", "", "/s", release); got != filepath.Join("/s", "update") {
		t.Errorf("without LOCALAPPDATA: %q", got)
	}
}
