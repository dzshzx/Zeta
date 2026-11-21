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

// uiNodes 是测试版里写控件清单的组件（app/src/debug 登记，见 core/diag/UiNodes.kt）。经 Zeta 自己的无障碍服务读，
// 含 Zeta 自己的悬浮层：那是无障碍服务画的窗口，系统不报给别的读取方，uiautomator 读不到。
const uiNodes = "com.dzshzx.zeta.core.diag.UiNodes"

// parseNodes 解析 UiNodes 写的清单：一行一个控件，中心 x、y、文字、说明，以制表符分隔；最上层窗口的在前。
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

// screenNodes 让测试版写出屏 display 上的控件清单再读回来。am broadcast 等接收方做完才返回。
func (v *vd) screenNodes(display int) []node {
	file := "/data/user/0/" + v.pkg + "/files/ui-nodes.tsv"
	r := v.run(fmt.Sprintf(`out=$(am broadcast -n %s/%s --ei display %d 2>&1); `+
		`case $out in *"result=0"*) cat %s ;; *) echo "$out" >&2; exit 1 ;; esac`, v.pkg, uiNodes, display, file), nil, 60)
	if r.code != 0 {
		die("读不到控件：%s", strings.TrimSpace(string(r.stderr)))
	}
	nodes, err := parseNodes(string(r.stdout))
	if err != nil {
		die("%s", err)
	}
	return nodes
}

func (v *vd) nodes(re *regexp.Regexp) {
	for _, n := range matching(v.screenNodes(v.needDisplay()), re) {
		fmt.Println(n)
	}
}

func (v *vd) tapText(re *regexp.Regexp) {
	display := v.needDisplay()
	found := matching(v.screenNodes(display), re)
	if len(found) == 0 {
		die("虚拟屏上没有文字或说明对得上 %s 的控件", re)
	}
	n := found[0]
	v.android(fmt.Sprintf("input -d %d tap %d %d", display, n.x, n.y))
	fmt.Printf("点了 %d %d：%s\n", n.x, n.y, strings.TrimSpace(n.text+" "+n.desc))
}
