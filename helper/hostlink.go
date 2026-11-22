package main

import (
	"github.com/dzshzx/Zeta/helper/conv"
	"github.com/dzshzx/Zeta/helper/host"
)

// 对话（helper/conv）经 helper/host 起和接托管进程：这里把两边接起来。核心就在主机上，环境（HOME、ZETA_NAME）是自己的。

type hostLink struct{ *host.Link }

func (l hostLink) Stop()   { l.Link.Stop() }
func (l hostLink) Detach() { l.Link.Detach() }

func startHost(*env) func(conv.StartOptions, conv.Events) (conv.Link, error) {
	return func(o conv.StartOptions, ev conv.Events) (conv.Link, error) {
		l, err := host.Start(host.StartOptions{Key: o.Key, Cwd: o.Cwd, Title: o.Title, Args: o.Args, Env: o.Env, OnJoin: o.OnJoin})
		if err != nil {
			return nil, err
		}
		go pump(l, ev)
		return hostLink{l}, nil
	}
}

// pump 把托管连接的事件按到达顺序交给对话，直到连接结束。
func pump(l *host.Link, ev conv.Events) {
	for e := range l.Events() {
		switch x := e.(type) {
		case host.Line:
			ev.Line(x.Data)
		case host.Exit:
			ev.Exit(conv.Exit{Code: x.Code, Stderr: x.Stderr, Reason: x.Reason})
		}
	}
}
