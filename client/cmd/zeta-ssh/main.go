// zeta-ssh：Android 版 Zeta 直连远程主机用的程序。APK 把它当原生库带着（lib/arm64-v8a/libzeta_ssh.so，装好后在应用的
// nativeLibraryDir 里，能执行），应用进程起一个、经标准输入输出说话，协议见 docs/core.md「Android 直连（zeta-ssh）」。
// 标准输入结束（应用关掉管道或进程没了）就断开所有连接退出。标准错误只写出错时的一行说明。
//
// -idle 是一台主机的最后一个通道关掉后过多久断开连接，默认一分钟（测试里改短）。
package main

import (
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/dzshzx/Zeta/client/sshbridge"
)

func main() {
	idle := flag.Duration("idle", time.Minute, "最后一个通道关掉后过多久断开连接")
	flag.Parse()
	if err := sshbridge.Run(os.Stdin, os.Stdout, sshbridge.Options{Idle: *idle}); err != nil {
		fmt.Fprintln(os.Stderr, "zeta-ssh:", err)
		os.Exit(1)
	}
}
