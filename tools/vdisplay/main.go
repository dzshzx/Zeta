// vd：后台虚拟屏。在不占用主屏的情况下运行、截图、点按 Zeta，做法取自 Eta 的 VirtualDisplayCommandMain。
// 入口是 tools/vd，它按需把本目录编到 build/vd 再执行；用法见 usage。
package main

import (
	"fmt"
	"os"
	"regexp"
	"slices"
	"strconv"
)

const usage = `后台虚拟屏：在不占用主屏的情况下运行、截图、点按 Zeta。

设备：测试版默认在本机的模拟器上（经 adb，没开就由 tools/emu 开机），ZETA_DEVICE=phone 时在手机上；正式版只在手机上。
在工作站上经 ssh 连手机（手机 Debian 的别名取 ZETA_PHONE_SSH 或 git config zeta.phoneSsh）；在手机 Debian 里跑时就是这台手机。
模拟器上进 Debian 用的 Linux 用户取 ZETA_LINUX_USER 或 zeta.linuxUser，默认 zeta（Zeta 装环境时的默认用户名）。

用法：tools/vd [--release] <命令>
  默认操作测试版（com.dzshzx.zeta.dev）。设备上的测试写成脚本交给 tools/phone-run，脚本里的 vd 就是 tools/vd：
  phone-run 已经开好模拟器、拿着租约（ZETA_PHONE_TICKET），脚本里的 vd 不再重做这两步。
  ZETA_PHONE_OWNER 不是 main（子代理）时只能在 phone-run 里用，直接调会被拒绝。
  --release 操作手机上的正式版（com.dzshzx.zeta），只由主会话在上线后验证时用：命令前要写明 ZETA_PHONE_OWNER=main，没设或是别的名字都不做，phone-run 里也不做。
  测试版和正式版各有自己的虚拟屏。
  vd build                 编译 VdHold.java 为 dex
  vd start [宽 高 dpi]      建虚拟屏并在上面启动 Zeta（手机上默认取主屏尺寸，模拟器上默认和手机一样大）
  vd shot 文件.png          截图
  vd tap X Y | swipe X1 Y1 X2 Y2 [毫秒] | text 文字 | key 键码
  vd nodes [正则]           虚拟屏上看得见、带文字或说明的控件，含 Zeta 自己的悬浮层（胶囊、结果卡片），最上层窗口的在前；
                           一行一个：中心 x、y、文字、说明，以制表符分隔。给了正则时只列文字或说明对得上的。
                           经测试版自己的无障碍服务读（core/diag/UiNodes），只给测试版用
  vd tap-text 正则          点虚拟屏上第一个文字或说明对得上的控件的中心；没有就以退出码 1 失败
  vd launch                在虚拟屏上重新启动 Zeta（装了新包之后）
  vd prefs 键名…            读 Zeta 设置（shared_prefs/zeta.xml）里这几项，只按键名取，不整份输出
  vd prefs-set 键[:int]=值…
                           改测试版的设置（默认是字符串，:int 是整数）：force-stop 后原地改写；
                           每个键第一次改时记下原值，stop 时改回去（phoneDisplay 一律删掉）
  vd sh [--stdin] '命令'    在 Android 一侧以 root 跑一条 shell 命令，原样输出、带回退出码；--stdin 时把标准输入交给它
  vd debian [--stdin] '命令' 在 Debian 里以 Linux 用户跑一条命令（手机经 ssh 登录，模拟器经进入脚本），原样输出；失败时退出码非 0。
                          带 --stdin 时把本地的标准输入交给它（比如往 Debian 里写文件：--stdin 'cat > 路径' < 文件）
  vd fake 剧本.jsonl       测试版之后起的对话换用按剧本回话的假 Claude（tools/fake-claude）：把它和剧本放到 Debian 的
                           ~/.config/zeta-dev/fake-claude/，进度从第一轮算起；fake --off 或 stop 删掉，回到真的 claude。
                           fake --mark 标记名：放下剧本里 {"wait":"标记名"} 等的标记
  vd status | stop         stop 关掉虚拟屏（屏上的应用随屏销毁）；测试版另外 force-stop，撤销它的通知监听、改回 prefs-set 改过的设置，
                           并把它留在用户「最近任务」里的记录全部清掉，假 Claude 的剧本也删掉。
                           force-stop 会让系统把那个包的无障碍服务从启用列表里拿掉，所以不对正式版做`

// command 是认出来的一条命令。参数在碰设备之前就核对完：认不出的不拿租约、不开模拟器。
type command struct {
	name    string
	args    []string
	release bool
	stdin   bool           // sh、debian 带了 --stdin
	match   *regexp.Regexp // nodes、tap-text 的正则
}

var (
	keyName    = regexp.MustCompile(`^[A-Za-z0-9_]+$`)
	assignment = regexp.MustCompile(`(?s)^([A-Za-z0-9_]+)(?::(int))?=(.*)$`)
)

// arity 是各命令的参数个数：最少、最多（-1 不限）。
var arity = map[string][2]int{
	"build": {0, 0}, "start": {0, 3}, "shot": {1, 1}, "launch": {0, 0}, "prefs": {1, -1}, "prefs-set": {1, -1},
	"tap": {2, 2}, "swipe": {4, 5}, "text": {1, 1}, "key": {1, 1}, "nodes": {0, 1}, "tap-text": {1, 1},
	"fake": {1, 2}, "status": {0, 0}, "stop": {0, 0}, "sh": {1, 1}, "debian": {1, 1},
}

// parse 认出命令、核对参数，出错时返回给人看的说明。
func parse(argv []string) (command, error) {
	var c command
	var rest []string
	for _, a := range argv {
		if a == "--release" {
			c.release = true
		} else {
			rest = append(rest, a)
		}
	}
	if len(rest) == 0 {
		return c, fmt.Errorf("%s", usage)
	}
	c.name, c.args = rest[0], rest[1:]
	n, ok := arity[c.name]
	if !ok {
		return c, fmt.Errorf("%s", usage)
	}
	if (c.name == "sh" || c.name == "debian") && len(c.args) > 0 && c.args[0] == "--stdin" {
		c.stdin, c.args = true, c.args[1:]
	}
	if len(c.args) < n[0] || (n[1] >= 0 && len(c.args) > n[1]) {
		return c, fmt.Errorf("vd %s 的参数不对\n\n%s", c.name, usage)
	}
	return c, c.check()
}

// check 核对各命令自己的参数。
func (c *command) check() error {
	switch c.name {
	case "start", "tap", "swipe":
		for _, a := range c.args {
			if _, err := strconv.Atoi(a); err != nil {
				return fmt.Errorf("vd %s 的参数要是整数：%s", c.name, a)
			}
		}
		if c.name == "start" && len(c.args) != 0 && len(c.args) != 3 {
			return fmt.Errorf("vd start 要么不给尺寸，要么给全宽、高、dpi")
		}
	case "key":
		if !keyName.MatchString(c.args[0]) {
			return fmt.Errorf("vd key 的键码只含字母、数字、下划线：%s", c.args[0])
		}
	case "prefs":
		if !slices.ContainsFunc(c.args, func(k string) bool { return !keyName.MatchString(k) }) {
			return nil
		}
		return fmt.Errorf("用法：vd [--release] prefs 键名…（键名只含字母、数字、下划线）")
	case "prefs-set":
		return c.checkPrefsSet()
	case "fake":
		if c.release {
			return fmt.Errorf("假 Claude 只给测试版用：去掉 --release")
		}
		if (c.args[0] == "--mark") != (len(c.args) == 2) || (len(c.args) == 2 && !keyName.MatchString(c.args[1])) {
			return fmt.Errorf("用法：vd fake 剧本.jsonl | --off | --mark 标记名（标记名只含字母、数字、下划线）")
		}
	case "nodes", "tap-text":
		if c.release {
			return fmt.Errorf("vd %s 经测试版才有的组件读控件：去掉 --release", c.name)
		}
		return c.compileMatch()
	}
	return nil
}

func (c *command) checkPrefsSet() error {
	if c.release {
		return fmt.Errorf("prefs-set 只改测试版：去掉 --release")
	}
	for _, a := range c.args {
		m := assignment.FindStringSubmatch(a)
		if m == nil {
			return fmt.Errorf("用法：vd prefs-set 键[:int]=值…")
		}
		if _, err := strconv.Atoi(m[3]); m[2] == "int" && err != nil {
			return fmt.Errorf("%s 不是整数", a)
		}
	}
	return nil
}

func (c *command) compileMatch() error {
	pattern := ""
	if len(c.args) > 0 {
		pattern = c.args[0]
	}
	re, err := regexp.Compile(pattern)
	if err != nil {
		return fmt.Errorf("正则写错了：%v", err)
	}
	c.match = re
	return nil
}

// die 把说明写到标准错误，以退出码 1 退出。
func die(format string, a ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", a...)
	os.Exit(1)
}

func main() {
	c, err := parse(os.Args[1:])
	if err != nil {
		die("%s", err)
	}
	v := newVD(!c.release)
	if c.name != "build" {
		v.connect()
	}
	v.do(c)
}
