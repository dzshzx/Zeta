package main

import (
	"github.com/dzshzx/Zeta/helper/conv"
	"github.com/dzshzx/Zeta/helper/host"
)

// 对话（helper/conv）经 helper/host 起和接托管进程：这里把两边接起来。核心就在主机上，环境（HOME、ZETA_NAME）是自己的。

type hostLink struct{ *host.Link }

func (l hostLink) Stop() { l.Link.Stop() }

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

func attachHost(*env) func(string, int, conv.Events) (conv.Link, error) {
	return func(key string, from int, ev conv.Events) (conv.Link, error) {
		l, err := host.Attach(key, from)
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

// stopOwnHosts 结束本包托管、正跑着会话 session 的进程（zeta_host.sh stop，同对话结束自己接着的进程：发 end_session，不退再发信号），
// 不等它们退出；给出有几个。核心里的对话没接着它们时用（conv.Deps.EndHosted）。
func stopOwnHosts(e *env, session string) int {
	keys := e.ownHostKeys(session)
	for _, key := range keys {
		host.Stop(key)
	}
	return len(keys)
}

// ownHostKeys 是本包正跑着会话 session 的托管目录（进程还在、没写 exit）的标识。
func (e *env) ownHostKeys(session string) []string {
	if session == "" {
		return nil
	}
	var keys []string
	for _, key := range globNames(e.hosts, true, nil) {
		dir := joinPath(e.hosts, key)
		pid, known := readPid(joinPath(dir, "pid"))
		if hostRunning(dir, pid, known) && hostSessionNow(dir) == session {
			keys = append(keys, key)
		}
	}
	return keys
}
