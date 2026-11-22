package main

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// devCleanup 是测试版停屏时的收尾。force-stop 会让系统把它的无障碍服务从启用列表里拿掉（AOSP onPackagesForceStoppedLocked），
// 通知监听不会，要自己撤。prefs-set 改过的设置另由 prefsRestore 改回去
const devCleanup = `
for c in $(settings get secure enabled_notification_listeners | tr ':' ' '); do
  case $c in @PKG@/*) cmd notification disallow_listener $c && echo "撤销了通知监听 $c" ;; esac
done
# 屏随着关掉、包也 force-stop 了，任务记录却还留在用户的「最近任务」里（HyperOS 实测），一条条移除
n=0
for id in $(dumpsys activity recents | grep -oE 'Task\{[0-9a-f]+ #[0-9]+ [^}]* A=[0-9]+:@PKG@\}' | grep -oE ' #[0-9]+ ' | tr -d ' #'); do
  am stack remove $id && n=$((n+1))
done
[ $n -gt 0 ] && echo "从最近任务里清掉了 $n 条记录"
true`

// system_server 的这些环境变量原样交给 app_process
var bootEnv = map[string]bool{
	"ANDROID_ROOT": true, "ANDROID_DATA": true, "ANDROID_ART_ROOT": true, "ANDROID_I18N_ROOT": true, "ANDROID_TZDATA_ROOT": true,
	"ANDROID_STORAGE": true, "ANDROID_ASSETS": true, "BOOTCLASSPATH": true, "DEX2OATBOOTCLASSPATH": true,
	"SYSTEMSERVERCLASSPATH": true, "STANDALONE_SYSTEMSERVER_JARS": true,
}

var (
	readyLine     = regexp.MustCompile(`(?m)^VD_READY:(\d+)$`)
	sizeLine      = regexp.MustCompile(`(\d+)x(\d+)`)
	number        = regexp.MustCompile(`\d+`)
	buildToolsVer = regexp.MustCompile(`(?m)^\s*buildToolsVersion = "([0-9.]+)"`) // 同 tools/release 的 buildToolsLine
)

// noDisplay 是 onDisplay 里找不到虚拟屏时远端的退出码。
const noDisplay = 97

// input 在虚拟屏上注入一次输入：args 是 input 命令屏号之后的部分。
func (v *vd) input(args string) {
	v.onDisplay(`input -d "$d" ` + args)
}

func (v *vd) status() {
	if id, ok := v.displayID(); ok {
		fmt.Printf("虚拟屏 %d\n", id)
	} else {
		fmt.Println("虚拟屏不在")
	}
}

func stdinIf(want bool) []byte {
	if !want {
		return nil
	}
	data, err := io.ReadAll(os.Stdin)
	if err != nil {
		die("读标准输入失败：%v", err)
	}
	return data
}

func (v *vd) sh(c *command) {
	r := v.run(c.args[0], stdinIf(c.stdin), 120)
	os.Stdout.Write(r.stdout)
	os.Stderr.Write(r.stderr)
	os.Exit(r.code)
}

// findDisplay 是一段远端脚本：把这块虚拟屏的屏号放进 $d，不在时 $d 为空。按 {"名字" 认：带引号比整个名字，
// zeta-vd 不能认成别的屏。屏号和要做的事放在同一次远端调用里，不为查屏号多走一趟 ssh。
func (v *vd) findDisplay() string {
	return fmt.Sprintf(`d=$(cmd display get-displays 2>/dev/null | grep -F '{"%s"' | `+
		`grep -oE 'Display id [0-9]+|displayId[ =][0-9]+' | head -n 1 | grep -oE '[0-9]+$'); `, v.name)
}

// onDisplay 找到虚拟屏（$d）再跑 cmd，返回标准输出；屏不在、命令失败时退出。
func (v *vd) onDisplay(cmd string) string {
	r := v.run(v.findDisplay()+fmt.Sprintf(`[ -n "$d" ] || exit %d; `, noDisplay)+cmd, nil, 120)
	switch r.code {
	case 0:
		return string(r.stdout)
	case noDisplay:
		die("虚拟屏不在：先运行 start")
	}
	die("android 命令失败（%d）：%s\n%s", r.code, cmd, strings.TrimSpace(string(r.stderr)))
	return ""
}

func (v *vd) displayID() (int, bool) {
	id, err := strconv.Atoi(strings.TrimSpace(v.try(v.findDisplay() + `echo "$d"`)))
	return id, err == nil
}

func (v *vd) dexPaths() (src, out, dex string) {
	src = filepath.Join(v.tools, "vdisplay", "VdHold.java")
	out = filepath.Join(v.tools, "vdisplay", "build")
	return src, out, filepath.Join(out, "classes.dex")
}

func (v *vd) buildDex() {
	src, out, dex := v.dexPaths()
	if err := os.MkdirAll(out, 0o755); err != nil {
		die("%v", err)
	}
	home, err := exec.Command(filepath.Join(v.tools, "java-home")).Output()
	if err != nil {
		die("tools/java-home 失败：%v", err)
	}
	jdk := strings.TrimSpace(string(home))
	passthrough(jdk+"/bin/javac", "--release", "11", "-Xlint:-options", "-d", out, src)
	classes, _ := filepath.Glob(filepath.Join(out, "*.class"))
	gradle, err := os.ReadFile(filepath.Join(v.tools, "..", "app", "build.gradle.kts"))
	m := buildToolsVer.FindSubmatch(gradle)
	if err != nil || m == nil {
		die("app/build.gradle.kts 里读不到 buildToolsVersion：%v", err)
	}
	d8 := filepath.Join(v.sdk, "build-tools", string(m[1]), "lib", "d8.jar")
	passthrough(jdk+"/bin/java", append([]string{"-cp", d8, "com.android.tools.r8.D8", "--min-api", "33", "--output", out}, classes...)...)
	fmt.Println("built", dex)
}

// passthrough 跑一条命令，输出直通，失败就退出。
func passthrough(name string, args ...string) {
	cmd := exec.Command(name, args...)
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		die("%s 失败：%v", filepath.Base(name), err)
	}
}

// launchCmd 是在屏 display（屏号或 "$d"）上冷启动 Zeta 的远端命令。
func (v *vd) launchCmd(display string) string {
	// NEW_TASK | MULTIPLE_TASK：在虚拟屏上另起一个任务，不把主屏上的那份挪过来；
	// EXCLUDE_FROM_RECENTS：每次冷启动都是新任务，不加的话屏关掉后它们一条条留在用户的「最近任务」里
	return fmt.Sprintf("am start --user 0 --display %s -n '%s/%s' -f 0x18800000 >/dev/null 2>&1", display, v.pkg, activity)
}

// screenSize 是要建的虚拟屏尺寸：给了就用给的，模拟器上和手机一样大，手机上取主屏的。
func (v *vd) screenSize(args []string) [3]int {
	var size [3]int
	switch {
	case len(args) == 3:
		for i, a := range args {
			size[i], _ = strconv.Atoi(a)
		}
	case v.device == "emu":
		size = phoneScreen
	default:
		m := sizeLine.FindStringSubmatch(v.android("wm size"))
		dpi := number.FindAllString(v.android("wm density"), -1)
		if m == nil || dpi == nil {
			die("读不到主屏的尺寸或密度")
		}
		size[0], _ = strconv.Atoi(m[1])
		size[1], _ = strconv.Atoi(m[2])
		size[2], _ = strconv.Atoi(dpi[len(dpi)-1])
	}
	return size
}

// bootExports 是 system_server 的启动类路径等环境变量，写成 export 语句。
func (v *vd) bootExports() string {
	pid := strings.Fields(v.android("pidof system_server"))
	if len(pid) == 0 {
		die("找不到 system_server")
	}
	var exports []string
	found := false
	for e := range strings.SplitSeq(v.android("cat /proc/"+pid[0]+"/environ"), "\x00") {
		k, val, _ := strings.Cut(e, "=")
		if bootEnv[k] && !strings.Contains(val, "'") {
			exports = append(exports, fmt.Sprintf("export %s='%s'", k, val))
			found = found || k == "BOOTCLASSPATH"
		}
	}
	if !found {
		die("读不到 BOOTCLASSPATH")
	}
	return strings.Join(exports, "; ")
}

func (v *vd) start(args []string) {
	if _, ok := v.displayID(); ok {
		die("虚拟屏已经在了（stop 关闭）")
	}
	src, _, dexPath := v.dexPaths()
	if stale(dexPath, src) {
		v.buildDex()
	}
	size := v.screenSize(args)
	exports := v.bootExports()
	dex, err := os.ReadFile(dexPath)
	if err != nil {
		die("%v", err)
	}
	w := v.work
	v.androidIn(fmt.Sprintf("mkdir -p %[1]s/out && chmod 755 %[1]s && chown 1000:1000 %[1]s/out && chmod 700 %[1]s/out && "+
		"cat > %[1]s/vdhold.dex && chmod 644 %[1]s/vdhold.dex && cd %[1]s && rm -f ctl log out/*.png && "+
		"mkfifo ctl && chmod 600 ctl && : > log", w), dex)
	runner := fmt.Sprintf("cd %[1]s && exec 3<>ctl && %[2]s && export CLASSPATH=%[1]s/vdhold.dex && "+
		"exec app_process / VdHold %[3]d %[4]d %[5]d 3600 %[6]s <&3 >>log 2>&1", w, exports, size[0], size[1], size[2], v.name)
	v.android("nohup sh -c " + shQuote(runner) + " >/dev/null 2>&1 &")
	for range 40 {
		if m := readyLine.FindStringSubmatch(v.android("cat " + w + "/log")); m != nil {
			id, _ := strconv.Atoi(m[1])
			v.android(v.launchCmd(m[1]))
			fmt.Printf("虚拟屏 %d（%dx%d @%d）\n", id, size[0], size[1], size[2])
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	die("虚拟屏没有就绪：\n%s", v.android("cat "+w+"/log"))
}

// stale：target 不在，或比 src 旧。
func stale(target, src string) bool {
	t, err := os.Stat(target)
	if err != nil {
		return true
	}
	s, err := os.Stat(src)
	return err == nil && t.ModTime().Before(s.ModTime())
}

// shot 在一次远端调用里做完：记下日志长度，叫 VdHold 截图，在设备上等日志里出现 VD_SHOT 再把 PNG 吐出来。
func (v *vd) shot(path string) {
	png := v.onDisplay(fmt.Sprintf(`n=$(wc -c < %[1]s/log); echo 'shot %[1]s/out/shot.png' > %[1]s/ctl; i=0; `+
		`while [ $i -lt 40 ]; do t=$(tail -c +$((n + 1)) %[1]s/log); `+
		`case $t in *VD_SHOT:*) exec cat %[1]s/out/shot.png ;; *VD_ERR:*) echo "$t" >&2; exit 1 ;; esac; `+
		`sleep 0.25; i=$((i + 1)); done; echo 截图超时 >&2; exit 1`, v.work))
	if err := os.WriteFile(path, []byte(png), 0o644); err != nil {
		die("%v", err)
	}
	fmt.Println(path)
}

func (v *vd) stop() {
	closeScreen := v.findDisplay() + `[ -z "$d" ] || echo release > ` + v.work + "/ctl"
	if !v.dev {
		// 用户的正式版只关屏，不碰（force-stop 会让系统拿掉它的无障碍服务）
		fmt.Print(v.try(closeScreen))
		fmt.Println("stopped")
		return
	}
	// 测试版另外收拾干净。force-stop 放在关屏之后：先 force-stop 再关屏，测试版会被重新拉起来
	fmt.Print(v.try(strings.Join([]string{closeScreen, "am force-stop " + v.pkg, strings.ReplaceAll(devCleanup, "@PKG@", v.pkg)}, "\n")))
	v.prefsRestore()
	// 假 Claude 撤下：之后测试版起的都是真的 claude，闲着的假 Claude 看到剧本没了自己退出
	fmt.Print(v.debian(fmt.Sprintf("if [ -d %[1]s ]; then rm -rf %[1]s && echo 删掉了假 Claude 的剧本; fi", fakeDir), nil))
	fmt.Println("stopped")
}
