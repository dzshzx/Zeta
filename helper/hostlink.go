package main

import (
	"errors"

	"github.com/dzshzx/Zeta/helper/conv"
)

// 临时：helper/host 合进来后换成真的托管连接。
var errNoHost = errors.New("还不能起 Claude Code 进程")

func startHost(*env) func(conv.StartOptions, conv.Events) (conv.Link, error) {
	return func(conv.StartOptions, conv.Events) (conv.Link, error) { return nil, errNoHost }
}

func attachHost(*env) func(string, int, conv.Events) (conv.Link, error) {
	return func(string, int, conv.Events) (conv.Link, error) { return nil, errNoHost }
}
