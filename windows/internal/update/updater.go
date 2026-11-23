package update

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// Phase 是检查更新走到哪了。
type Phase int

const (
	Idle        Phase = iota // 这次启动以来还没查过
	Checking                 // 正在取清单
	UpToDate                 // 已是最新版本
	CheckFailed              // 没查成，原因在 Status.Reason
	Available                // 有新版本，在 Status.Manifest；下载、安装走到了 Status.Step
)

// Step 是有新版本以后，下载和安装走到了哪一步。
type Step int

const (
	Ready       Step = iota // 还没开始下载
	Downloading             // 下了 Status.Done 字节
	Verifying               // 核对大小和 sha256
	Installing              // 安装程序起来了，本程序随即退出，装好后自动重新打开
	Failed                  // 原因在 Status.Reason，可以重试
)

// Status 是界面要显示的更新状态，取的是一份拷贝。
type Status struct {
	Phase    Phase
	Step     Step
	Manifest *Manifest // Phase 为 Available 时有，它的 MSI 不是 nil
	Done     int64     // 已下载的字节数
	Reason   string    // CheckFailed、Failed 的原因，给人看的一句话
}

// Prefs 是更新用到的三项设置，键和手机版的一样：autoUpdateCheck（布尔，默认 true）、
// updateCheckedAt（上次检查的时间，Unix 毫秒）、updateNotified（已提示过的 versionCode）。
type Prefs interface {
	AutoUpdateCheck() bool
	SetAutoUpdateCheck(bool)
	UpdateCheckedAt() time.Time
	SetUpdateCheckedAt(time.Time)
	UpdateNotified() int
	SetUpdateNotified(int)
}

// Source 取清单、下载安装包；真的是 *Client，测试换成假的。
type Source interface {
	Manifest(ctx context.Context) (*Manifest, error)
	Download(ctx context.Context, p Package, dest string, progress func(done int64)) error
}

// Updater 是桌面版的应用内更新，和手机正式版一样读最新 Release 的 update.json：versionCode 比自己大、清单里有 MSI 才算新版本。
// 只有正式发版打的包查（Version.Release）。启动时（Start）每天最多自动查一次，跟着设置里的「自动检查更新」，查到新版本用 Notify
// 提示一句（同一个版本只提示一次）；设置「关于」里随时手动查（Check）。
//
// MSI 下载到 Dir，大小和 sha256 对不上清单就删掉、说原因。装由 Install 起，起好后调 Exit 退出本程序，让安装程序换掉文件、装好再打开 Zeta。
// 方法都可以在界面线程上调，活在后台 goroutine 里做；状态一变就调 Changed（在后台 goroutine 上），界面经窗口的 Update 重画。
// 字段在第一次调方法之前填好，之后不改。
type Updater struct {
	Version Version
	Dir     string                 // 下载目录，见 DownloadDir
	Source  Source                 // 取清单、下载
	Install func(msi string) error // 起安装程序，见 Installer
	Prefs   Prefs
	Notify  func(version string) // 自动查到新版本时提示一句（version 是新版本的版本名，界面显示时出字），在后台 goroutine 上调；nil 时不提示
	Exit    func()               // 安装程序起好后退出本程序，在后台 goroutine 上调
	Changed func()               // 状态变了；nil 时不通知
	Now     func() time.Time     // nil 时是 time.Now

	mu     sync.Mutex
	status Status
	run    int                // 每次开始下载、取消时加一：被取消的那次留下的进度不再改状态
	cancel context.CancelFunc // 正在下载时有
	last   chan struct{}      // 上一次下载安装的 goroutine 结束时关上：取消后马上再点「更新」，等它收拾完（删 .part）再开始
	wg     sync.WaitGroup
}

// DownloadDir 是下载安装包的目录：Windows 上放在 %LOCALAPPDATA%\<应用名>\update（不进漫游的 %APPDATA%；应用名见 Version.AppName，
// 测试版不查更新，分开只为不碰正式版的目录），别处在设置目录下。
func DownloadDir(settingsDir string) string {
	return downloadDir(runtime.GOOS, os.Getenv("LOCALAPPDATA"), settingsDir, Current())
}

func downloadDir(goos, localAppData, settingsDir string, v Version) string {
	if goos == "windows" && localAppData != "" {
		return filepath.Join(localAppData, v.AppName(), "update")
	}
	return filepath.Join(settingsDir, "update")
}

// Enabled 说这个包检查不检查更新：只有正式发版打的才查。
func (u *Updater) Enabled() bool { return u.Version.Release() }

// Status 是现在的状态。
func (u *Updater) Status() Status {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.status
}

// Wait 等后台的活做完，测试和退出前用。
func (u *Updater) Wait() { u.wg.Wait() }

// Start 在程序启动时调：删掉上次留下的安装包；打开了自动检查、今天还没查过就查一次，有新版本提示一句。
func (u *Updater) Start() {
	if !u.Enabled() {
		return
	}
	u.goDo(func() {
		u.clean()
		if !u.Prefs.AutoUpdateCheck() || u.Status().Phase != Idle || u.checkedToday() {
			return
		}
		u.Prefs.SetUpdateCheckedAt(u.now())
		u.set(Status{Phase: Checking})
		u.check(true)
	})
}

// Check 是「关于」里的检查更新。正在查、已经有新版本时不再查。
func (u *Updater) Check() {
	if !u.Enabled() {
		return
	}
	u.mu.Lock()
	if u.status.Phase == Checking || u.status.Phase == Available {
		u.mu.Unlock()
		return
	}
	u.status = Status{Phase: Checking}
	u.mu.Unlock()
	u.changed()
	u.Prefs.SetUpdateCheckedAt(u.now())
	u.goDo(func() { u.check(false) })
}

// Update 是「更新」按钮：下载、核对，起安装程序后退出。失败后的「重试」也是它。
func (u *Updater) Update() {
	u.mu.Lock()
	if u.status.Phase != Available || u.cancel != nil {
		u.mu.Unlock()
		return
	}
	u.run++
	run, m := u.run, u.status.Manifest
	ctx, cancel := context.WithCancel(context.Background())
	u.cancel = cancel
	prev, done := u.last, make(chan struct{})
	u.last = done
	u.mu.Unlock()
	u.goDo(func() {
		defer close(done)
		defer cancel()
		if prev != nil {
			<-prev
		}
		u.install(ctx, run, m)
		u.mu.Lock()
		if u.run == run {
			u.cancel = nil
		}
		u.mu.Unlock()
	})
}

// Cancel 在下载中取消，回到还没下载。
func (u *Updater) Cancel() {
	u.mu.Lock()
	if u.status.Phase != Available || u.status.Step != Downloading {
		u.mu.Unlock()
		return
	}
	u.run++
	if u.cancel != nil {
		u.cancel()
		u.cancel = nil
	}
	u.status.Step, u.status.Done = Ready, 0
	u.mu.Unlock()
	u.changed()
}

func (u *Updater) goDo(f func()) {
	u.wg.Add(1)
	go func() {
		defer u.wg.Done()
		f()
	}()
}

// check 取清单，状态已经是 Checking。
func (u *Updater) check(announce bool) {
	m, err := u.Source.Manifest(context.Background())
	switch {
	case err != nil:
		u.set(Status{Phase: CheckFailed, Reason: err.Error()})
	case m.MSI == nil || m.VersionCode <= u.Version.Code:
		u.set(Status{Phase: UpToDate})
	default:
		if announce && u.Notify != nil && u.Prefs.UpdateNotified() != m.VersionCode {
			u.Prefs.SetUpdateNotified(m.VersionCode)
			u.Notify(m.VersionName)
		}
		u.set(Status{Phase: Available, Manifest: m})
	}
}

// install 下载（上次已下好的同一个文件就不再下）、核对，起安装程序后退出。
func (u *Updater) install(ctx context.Context, run int, m *Manifest) {
	file := filepath.Join(u.Dir, m.MSI.File)
	u.step(run, Verifying, 0, "")
	if !u.downloaded(file, m.MSI) {
		u.clean()
		if err := os.MkdirAll(u.Dir, 0o755); err != nil {
			u.step(run, Failed, 0, locales.UpdateMkdirFailed(err.Error()))
			return
		}
		if err := u.fetch(ctx, run, m.MSI, file); err != nil {
			if !errors.Is(err, context.Canceled) {
				u.step(run, Failed, 0, err.Error())
			}
			return
		}
	}
	if !u.step(run, Installing, 0, "") {
		return
	}
	if err := u.Install(file); err != nil {
		u.step(run, Failed, 0, err.Error())
		return
	}
	u.Exit()
}

// fetch 下载 p，大约每 1% 报一次进度；下完到核对完显示「正在校验」。
func (u *Updater) fetch(ctx context.Context, run int, p *Package, file string) error {
	u.step(run, Downloading, 0, "")
	var shown int64
	return u.Source.Download(ctx, *p, file, func(done int64) {
		switch {
		case done == p.Size:
			u.step(run, Verifying, 0, "")
		case done-shown >= p.Size/100:
			shown = done
			u.step(run, Downloading, done, "")
		}
	})
}

// downloaded 说 file 是不是上次已经下好的同一个文件（大小、sha256 对得上）。
func (u *Updater) downloaded(file string, p *Package) bool {
	info, err := os.Stat(file)
	if err != nil || !info.Mode().IsRegular() || info.Size() != p.Size {
		return false
	}
	sum, err := fileSHA256(file)
	return err == nil && sum == p.SHA256
}

// clean 删掉下载目录里的旧文件：装好以后剩下的、下到一半的、安装脚本。
func (u *Updater) clean() {
	entries, _ := os.ReadDir(u.Dir)
	for _, e := range entries {
		os.RemoveAll(filepath.Join(u.Dir, e.Name()))
	}
}

func (u *Updater) checkedToday() bool {
	last, now := u.Prefs.UpdateCheckedAt().In(time.Local), u.now().In(time.Local)
	ly, lm, ld := last.Date()
	ny, nm, nd := now.Date()
	return ly == ny && lm == nm && ld == nd
}

func (u *Updater) now() time.Time {
	if u.Now != nil {
		return u.Now()
	}
	return time.Now()
}

func (u *Updater) set(s Status) {
	u.mu.Lock()
	u.status = s
	u.mu.Unlock()
	u.changed()
}

// step 改有新版本以后的一步；run 已经不是这一次（被取消了）或已经不是 Available 时不改，返回 false。
func (u *Updater) step(run int, s Step, done int64, reason string) bool {
	u.mu.Lock()
	if u.run != run || u.status.Phase != Available {
		u.mu.Unlock()
		return false
	}
	u.status.Step, u.status.Done, u.status.Reason = s, done, reason
	u.mu.Unlock()
	u.changed()
	return true
}

func (u *Updater) changed() {
	if u.Changed != nil {
		u.Changed()
	}
}
