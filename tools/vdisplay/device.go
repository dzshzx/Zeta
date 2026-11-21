package main

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

const (
	release  = "com.dzshzx.zeta"
	activity = "com.dzshzx.zeta.ui.MainActivity" // 类名不随包名变：测试版只加了 applicationIdSuffix
	// 假 Claude：测试版的 zeta_host.sh 看到这里有 scenario.jsonl 就起它（Debian 里 Linux 用户的家目录下）
	fakeDir = "~/.config/zeta-dev/fake-claude"
	// 模拟器上进 Debian 用 Zeta 自己的进入脚本，推到这里
	enterPath = "/data/local/tmp/zeta-enter.sh"
)

// phoneScreen 是手机主屏：模拟器上的虚拟屏照它建，测试脚本里的坐标两边通用。
var phoneScreen = [3]int{1220, 2656, 520}

// vd 是一次调用的上下文：操作哪个包、在哪台设备上。
type vd struct {
	tools   string // 本仓的 tools/ 目录
	sdk     string
	dev     bool   // 测试版
	device  string // phone 或 emu
	onPhone bool   // 在手机 Debian 里跑（chroot，和 Android 共用内核）
	// 下面三个按测试版还是正式版定：包名、虚拟屏名字（VdHold 的第 5 个参数）、手机上的工作目录
	pkg, name, work string
	enterPushed     bool
}

func newVD(dev bool) *vd {
	v := &vd{tools: toolsDir(), dev: dev, device: "phone", onPhone: onPhone()}
	v.sdk = os.Getenv("ANDROID_HOME") // 同 tools/gradle
	if v.sdk == "" {
		v.sdk = filepath.Join(os.Getenv("HOME"), "Android", "Sdk")
	}
	if dev {
		v.pkg, v.name = release+".dev", "zeta-vd"
	} else {
		v.pkg, v.name = release, "zeta-screen-main"
	}
	v.work = "/data/local/tmp/" + v.name
	return v
}

// toolsDir 是 tools/：程序编在 tools/vdisplay/build/vd。
func toolsDir() string {
	exe, err := os.Executable()
	if err == nil {
		exe, err = filepath.EvalSymlinks(exe)
	}
	if err != nil {
		die("找不到 vd 自己的位置：%v", err)
	}
	dir := filepath.Dir(filepath.Dir(filepath.Dir(exe)))
	if _, err := os.Stat(filepath.Join(dir, "vdisplay", "VdHold.java")); err != nil {
		die("%s 不在 Zeta 仓的 tools/vdisplay/build 下：经 tools/vd 运行", exe)
	}
	return dir
}

func onPhone() bool {
	release, _ := os.ReadFile("/proc/sys/kernel/osrelease")
	return strings.Contains(string(release), "android")
}

// connect 选设备、拿租约、开模拟器。在 tools/phone-run 里（有 ZETA_PHONE_TICKET）时它已经开好模拟器、排队拿到了租约，这里不再重做。
// 碰正式版先问 phone-lock 让不让：phone-run 里和手机 Debian 里不拿租约，这一问照样要过。
func (v *vd) connect() {
	want := os.Getenv("ZETA_DEVICE")
	if want == "" {
		want = "emu"
	}
	if want != "emu" && want != "phone" {
		die("ZETA_DEVICE 只能是 emu 或 phone（现在是 %s）", want)
	}
	if v.dev && !v.onPhone {
		v.device = want // 测试版默认在模拟器上；正式版只在手机上，手机 Debian 里跑时就是这台手机
	}
	if !v.dev {
		v.tool("phone-lock", "--check", "main")
	}
	if os.Getenv("ZETA_PHONE_TICKET") != "" {
		return
	}
	v.lease()
	if v.device == "emu" {
		v.tool("emu", "start") // 拿到租约之后才开：被拒绝时不白开一次模拟器
	}
}

// lease 按设备和包拿租约（见 tools/phone-lock：模拟器上的测试版 emu，手机上的测试版 dev、正式版 main），拿不到就退出。
// 谁能碰哪个包由 phone-lock 定。在手机 Debian 里直接跑时不协调。
func (v *vd) lease() {
	if v.onPhone {
		return
	}
	kind := "main"
	if v.device == "emu" {
		kind = "emu"
	} else if v.dev {
		kind = "dev"
	}
	v.tool("phone-lock", kind)
}

// tool 跑 tools/ 下的一个脚本，输入输出直通；失败时带着它的退出码退出。
func (v *vd) tool(name string, args ...string) {
	cmd := exec.Command(filepath.Join(v.tools, name), args...)
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		var exit *exec.ExitError
		if errors.As(err, &exit) {
			os.Exit(exit.ExitCode())
		}
		die("%s：%v", name, err)
	}
}

// setting 是本机的开发配置（同 tools/config.sh）：环境变量优先，其次是本仓的 git config zeta.<key>，都没有就是 def。
func (v *vd) setting(env, key, def string) string {
	value := os.Getenv(env)
	if value == "" {
		out, _ := exec.Command("git", "-C", v.tools, "config", "--get", "zeta."+key).Output()
		value = strings.TrimSpace(string(out))
	}
	if value == "" {
		return def
	}
	return value
}

func (v *vd) phoneSSH() string {
	alias := v.setting("ZETA_PHONE_SSH", "phoneSsh", "")
	if alias == "" {
		die("连手机要知道它 Debian 的 ssh 别名：设 ZETA_PHONE_SSH，或 git config zeta.phoneSsh <别名>")
	}
	return alias
}

func (v *vd) adb() []string {
	return []string{filepath.Join(v.sdk, "platform-tools", "adb"), "-s", "emulator-5554"}
}

type result struct {
	stdout, stderr []byte
	code           int
}

// capture 跑 argv，data 不为 nil 时经标准输入传过去（为 nil 时标准输入是 /dev/null）。
func capture(argv []string, data []byte) result {
	cmd := exec.Command(argv[0], argv[1:]...)
	if data != nil {
		cmd.Stdin = bytes.NewReader(data)
	}
	var out, errOut bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errOut
	err := cmd.Run()
	r := result{out.Bytes(), errOut.Bytes(), 0}
	var exit *exec.ExitError
	switch {
	case errors.As(err, &exit):
		r.code = exit.ExitCode()
	case err != nil:
		die("跑不了 %s：%v", argv[0], err)
	}
	return r
}

// run 在 Android 一侧以 root 运行 cmd。远端加 timeout：ssh、adb 断开后远端进程不会自己停。
// 手机上经 nsenter 进去的 shell 没有 BOOTCLASSPATH 这些 Android 运行环境：am、cmd、input、settings、dumpsys 走 binder，照常能用；
// 经 app_process 起的（uiautomator、svc 这类）起不来却返回 0。vd 里只有 VdHold 走 app_process，start 时从 system_server 抄环境
// （bootEnv）；要用别的 app_process 命令时同样带上，或者在手机上改走 adb（adb shell 带着这套环境，但是 shell 用户、没有 root）。
func (v *vd) run(cmd string, data []byte, seconds int) result {
	t := strconv.Itoa(seconds)
	sh := []string{"/system/bin/sh", "-c", "export PATH=/system/bin:$PATH; " + cmd}
	var argv []string
	if v.device == "emu" {
		// 模拟器（tools/emu）：adbd 是 root，adb shell 就在 Android 的全局挂载命名空间里
		argv = append(v.adb(), "shell", "-T", shJoin(append([]string{"timeout", t}, sh...)))
	} else {
		argv = append([]string{"sudo", "-n", "timeout", t, "nsenter", "-t", "1", "-m", "--"}, sh...)
		if !v.onPhone {
			argv = []string{"ssh", v.phoneSSH(), shJoin(argv)}
		}
	}
	return capture(argv, data)
}

// android 在 Android 一侧以 root 跑 cmd，返回标准输出；失败时退出。
func (v *vd) android(cmd string, data ...[]byte) string {
	return string(v.androidBytes(cmd, data...))
}

func (v *vd) androidBytes(cmd string, data ...[]byte) []byte {
	var in []byte
	if len(data) > 0 {
		in = data[0]
	}
	r := v.run(cmd, in, 120)
	if r.code != 0 {
		die("android 命令失败（%d）：%s\n%s", r.code, cmd, strings.TrimSpace(string(r.stderr)))
	}
	return r.stdout
}

// try 和 android 一样，失败也只返回标准输出。
func (v *vd) try(cmd string) string {
	return string(v.run(cmd, nil, 120).stdout)
}

// debian 在 Debian 里以 Linux 用户（Zeta 起 Claude Code 用的那个）跑 cmd：手机经 ssh 登录，模拟器经 Zeta 的进入脚本。远端加 timeout。
func (v *vd) debian(cmd string, data []byte) string {
	var r result
	if v.device == "emu" {
		if !v.enterPushed {
			enter, err := os.ReadFile(filepath.Join(v.tools, "..", "app", "src", "main", "assets", "zeta_enter.sh"))
			if err != nil {
				die("读不到进入脚本：%v", err)
			}
			v.android("cat > "+enterPath, enter)
			v.enterPushed = true
		}
		user := v.setting("ZETA_LINUX_USER", "linuxUser", "zeta")
		r = v.run(shJoin([]string{"sh", enterPath, "/usr/sbin/runuser", "-l", user, "-c", cmd}), data, 60)
	} else {
		argv := []string{"timeout", "60", "sh", "-c", cmd}
		if !v.onPhone {
			argv = []string{"ssh", v.phoneSSH(), shJoin(argv)}
		}
		r = capture(argv, data)
	}
	if r.code != 0 {
		die("Debian 里的命令失败（%d）：%s\n%s", r.code, cmd, strings.TrimSpace(string(r.stderr)))
	}
	return string(r.stdout)
}

// shellUnsafe：只含这些字符的参数不加引号（规则同 Python 的 shlex.quote）。
var shellUnsafe = regexp.MustCompile(`[^\w@%+=:,./-]`)

func shQuote(s string) string {
	if s == "" {
		return "''"
	}
	if !shellUnsafe.MatchString(s) {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'"'"'`) + "'"
}

func shJoin(args []string) string {
	quoted := make([]string, len(args))
	for i, a := range args {
		quoted[i] = shQuote(a)
	}
	return strings.Join(quoted, " ")
}
