package sshlink

import (
	"math"
	"strconv"
	"strings"
	"time"
)

// Quote 给 shell 加单引号。
func Quote(s string) string { return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'" }

// Limited 把 command 包成在那边限时 timeout 的一行：本机到点只关得掉通道，那边的 sshd 不会因此结束没有终端的命令，
// 所以那边用 coreutils 的 timeout 到点结束整组进程（秒数向上取整再多给 1 秒，让本机先到点、先报超时；再过 1 秒还在就 KILL）。
// 远程主机都是有 coreutils 的 Linux（加设备的检查核对这一样）；那边没有 timeout 时照原样跑，好让检查报出缺什么。
func Limited(command string, timeout time.Duration) string {
	secs := strconv.Itoa(int(math.Ceil(timeout.Seconds())) + 1)
	q := Quote(command)
	return "if command -v timeout >/dev/null 2>&1; then exec timeout -k 1 " + secs + " sh -c " + q + "; else exec sh -c " + q + "; fi"
}
