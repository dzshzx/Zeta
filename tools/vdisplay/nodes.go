package main

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

// node 是一个带文字或说明的控件，坐标是它在所在屏上的中心。
type node struct {
	x, y       int
	text, desc string
}

func (n node) String() string {
	return fmt.Sprintf("%d\t%d\t%s\t%s", n.x, n.y, n.text, n.desc)
}

// uiNodes 是测试版里列控件的组件（app/src/debug 登记，见 core/diag/UiNodes.kt）。经 Zeta 自己的无障碍服务读，
// 含 Zeta 自己的悬浮层：那是无障碍服务画的窗口，系统不报给别的读取方，uiautomator 读不到。清单在广播的结果里交回。
const uiNodes = "com.dzshzx.zeta.core.diag.UiNodes"

// completed 是 am broadcast 等接收方做完后打的那一行（AOSP ActivityManagerShellCommand.IntentReceiver）。
const completed = "Broadcast completed: result="

// parseBroadcast 从 am broadcast 的输出里取结果码和结果数据（data="…" 里原样的文字，可以跨行）。
func parseBroadcast(out string) (code int, data string, err error) {
	_, rest, ok := strings.Cut(out, completed)
	if !ok {
		return 0, "", fmt.Errorf("am broadcast 没有等到结果：%s", strings.TrimSpace(out))
	}
	num, rest, _ := strings.Cut(rest, ",")
	if code, err = strconv.Atoi(strings.TrimSpace(num)); err != nil {
		return 0, "", fmt.Errorf("am broadcast 的结果码不是整数：%q", num)
	}
	if _, quoted, ok := strings.Cut(rest, ` data="`); ok {
		data = strings.TrimSuffix(strings.TrimRight(quoted, "\r\n"), `"`)
	}
	return code, data, nil
}

// parseNodes 解析 UiNodes 交回的清单：一行一个控件，中心 x、y、文字、说明，以制表符分隔；最上层窗口的在前。
func parseNodes(data string) ([]node, error) {
	var out []node
	for line := range strings.Lines(data) {
		line = strings.TrimRight(line, "\r\n")
		if line == "" {
			continue
		}
		f := strings.Split(line, "\t")
		if len(f) != 4 {
			return nil, fmt.Errorf("控件清单的这一行不是四列：%q", line)
		}
		x, errX := strconv.Atoi(f[0])
		y, errY := strconv.Atoi(f[1])
		if errX != nil || errY != nil {
			return nil, fmt.Errorf("控件清单的这一行坐标不是整数：%q", line)
		}
		out = append(out, node{x, y, f[2], f[3]})
	}
	return out, nil
}

// matching 是文字或说明对得上 re 的控件。
func matching(nodes []node, re *regexp.Regexp) []node {
	var out []node
	for _, n := range nodes {
		if re.MatchString(n.text) || re.MatchString(n.desc) {
			out = append(out, n)
		}
	}
	return out
}

// screenNodes 在一次远端调用里找到虚拟屏（main 时是主屏，屏号 0）、让测试版列出屏上的控件（am broadcast 等接收方做完才返回），交回屏号和控件。
func (v *vd) screenNodes(main bool) (string, []node) {
	cmd := fmt.Sprintf(`echo "$d"; am broadcast -n %s/%s --ei display "$d"`, v.pkg, uiNodes)
	var out string
	if main {
		out = v.android("d=0; " + cmd)
	} else {
		out = v.onDisplay(cmd)
	}
	display, out, _ := strings.Cut(out, "\n")
	code, data, err := parseBroadcast(out)
	if err == nil && code != 0 {
		err = fmt.Errorf("测试版读不到控件：%s", data)
	}
	var nodes []node
	if err == nil {
		nodes, err = parseNodes(data)
	}
	if err != nil {
		die("%s", err)
	}
	return strings.TrimSpace(display), nodes
}

func (v *vd) nodes(re *regexp.Regexp, main bool) {
	_, nodes := v.screenNodes(main)
	for _, n := range matching(nodes, re) {
		fmt.Println(n)
	}
}

func (v *vd) tapText(re *regexp.Regexp, main bool) {
	display, nodes := v.screenNodes(main)
	found := matching(nodes, re)
	if len(found) == 0 {
		die("屏上没有文字或说明对得上 %s 的控件", re)
	}
	n := found[0]
	v.android(fmt.Sprintf("input -d %s tap %d %d", display, n.x, n.y))
	fmt.Printf("点了 %d %d：%s\n", n.x, n.y, strings.TrimSpace(n.text+" "+n.desc))
}
