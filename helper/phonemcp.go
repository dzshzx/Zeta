package main

// phone-mcp <包名> [<客户端标识>]：把标准输入输出接到 Zeta 手机工具的外部入口（抽象 Unix 套接字 <包名>.phone，
// 见 app/src/main/kotlin/com/dzshzx/zeta/phone/PhoneBridge.kt）。先发一行 ZETA-BRIDGE <标识>（不给时是 default），
// 之后一行一条 MCP 消息原样转过去、转回来。同一个标识再连时 Zeta 接着用原来那份手机工具（后台屏幕、控件编号）。
// 工作站上的 Claude Code 把它当 stdio 的 MCP 服务器起：ssh 进手机的 Debian，用 sudo 运行（入口只认 root）。
// 连之前先以 root 指名发一条广播叫起 Zeta（PhoneBridgeReceiver）：应用没在跑就拉起，进程被冻结着就解冻；之后最多等 phoneWait 连上。

import (
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"regexp"
	"time"
)

var (
	packagePattern   = regexp.MustCompile(`^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$`)
	bridgeKeyPattern = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,64}$`)
)

const (
	phoneWait = 15 * time.Second
	phoneStep = 200 * time.Millisecond
	// 叫起 Zeta 的接收器：类名不随包名变（测试版只加了 applicationIdSuffix）
	phoneReceiver = "com.dzshzx.zeta.phone.PhoneBridgeReceiver"
)

// phoneBridge 是 phone-mcp 用到的外部动作，测试里换成假的。
type phoneBridge struct {
	dial func(name string) (net.Conn, error)
	wake func(pkg string) error
	wait time.Duration
	step time.Duration
}

var realPhoneBridge = phoneBridge{
	dial: func(name string) (net.Conn, error) { return net.Dial("unix", "@"+name) },
	wake: wakeZeta,
	wait: phoneWait,
	step: phoneStep,
}

// runPhoneMCP 跑 phone-mcp，返回退出码。标准输出只放转回来的 MCP 消息，出错写标准错误。
func runPhoneMCP(b phoneBridge, args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	key := "default"
	if len(args) == 2 {
		key = args[1]
	}
	if len(args) < 1 || len(args) > 2 || !packagePattern.MatchString(args[0]) || !bridgeKeyPattern.MatchString(key) {
		fmt.Fprintln(stderr, "usage: phone-mcp <package> [<client key>]")
		return 1
	}
	conn, err := b.connect(args[0])
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	defer conn.Close()
	if _, err := fmt.Fprintf(conn, "ZETA-BRIDGE %s\n", key); err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	go func() {
		_, _ = io.Copy(conn, stdin)
		// 客户端不再发了：只关写的一半，已经发出的调用照样等 Zeta 答完
		if uc, ok := conn.(*net.UnixConn); ok {
			_ = uc.CloseWrite()
		}
	}()
	if _, err := io.Copy(stdout, conn); err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	return 0
}

// connect 叫起 Zeta 再连它的入口，连不上就每隔 step 重试，到 wait 为止。
func (b phoneBridge) connect(pkg string) (net.Conn, error) {
	name := pkg + ".phone"
	wakeErr := b.wake(pkg)
	deadline := time.Now().Add(b.wait)
	for {
		conn, err := b.dial(name)
		if err == nil {
			return conn, nil
		}
		if time.Now().After(deadline) {
			if wakeErr != nil {
				err = errors.Join(err, fmt.Errorf("叫起 Zeta：%w", wakeErr))
			}
			return nil, fmt.Errorf("连不上 %s 的手机工具（Zeta 装了吗？要用 root 运行）：%w", pkg, err)
		}
		time.Sleep(b.step)
	}
}

// wakeZeta 以 root 发广播叫起 Zeta。在 Android 一侧直接用 /system/bin/cmd；在 Debian 里时先 nsenter 进 init 的挂载命名空间。
func wakeZeta(pkg string) error {
	args := []string{"activity", "broadcast", "-n", pkg + "/" + phoneReceiver}
	var cmd *exec.Cmd
	if _, err := os.Stat("/system/bin/cmd"); err == nil {
		cmd = exec.Command("/system/bin/cmd", args...)
	} else {
		cmd = exec.Command("nsenter", append([]string{"-t", "1", "-m", "--", "/system/bin/cmd"}, args...)...)
	}
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%w：%s", err, out)
	}
	return nil
}
