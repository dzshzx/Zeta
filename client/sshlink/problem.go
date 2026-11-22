package sshlink

import (
	"errors"
	"io"
	"net"
	"os"
	"regexp"
	"strings"
	"syscall"
)

var (
	unreachable = regexp.MustCompile(`(?i)ENETUNREACH|EHOSTUNREACH|unreachable`)
	timedOut    = regexp.MustCompile(`(?i)timeout|timed out|ETIMEDOUT`)
	refused     = regexp.MustCompile(`(?i)refused`)
)

// Describe 把连接时的出错翻成一句给人看的话。系统给的说明文字随系统和语言变，先看错误的类型，再看说明里的关键词。
func Describe(err error) string {
	var problem Problem
	var changed *HostKeyChangedError
	var dns *net.DNSError
	text := err.Error()
	switch {
	case errors.As(err, &problem):
		return string(problem)
	case errors.As(err, &changed):
		return changed.Error()
	case errors.As(err, &dns):
		return "找不到这台设备（地址解析失败）"
	case errors.Is(err, syscall.EHOSTUNREACH), errors.Is(err, syscall.ENETUNREACH), unreachable.MatchString(text):
		return "网络不通"
	case errors.Is(err, os.ErrDeadlineExceeded), timedOut.MatchString(text):
		return "连接超时"
	case errors.Is(err, syscall.ECONNREFUSED), refused.MatchString(text):
		return "对方拒绝连接（端口上没有 SSH 服务）"
	case strings.Contains(text, "no common algorithm"):
		return "和对方谈不拢加密算法：" + clip(text, 160)
	case errors.Is(err, io.EOF), strings.Contains(text, "connection reset"):
		return "对方断开了连接"
	}
	return clip(text, 200)
}
