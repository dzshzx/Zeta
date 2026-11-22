package main

import (
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// 闲置超时（docs/core.md「主机」）：没有客户端接着、又闲着的 Claude Code 进程，托管脚本的看门狗数满这么多分钟就结束它
// （zeta_host.sh 的 idle_limit，每轮重读，改了马上生效）。每台主机一份，存在 ~/.config/<ZETA_NAME>/idle-minutes，
// 几个客户端连同一台主机看到的是同一个值。0 是不自动结束。

const (
	defaultIdleMinutes = 10   // 没设时（同 zeta_host.sh）
	maxIdleMinutes     = 1440 // 一天
)

// idleMinutes 读这台主机设的值；没设、读不了或不是 0～maxIdleMinutes 的整数时是默认值（看门狗那边同样按默认算）。
func (e *env) idleMinutes() int {
	data, err := os.ReadFile(e.idle)
	if err != nil {
		return defaultIdleMinutes
	}
	n, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || n < 0 || n > maxIdleMinutes {
		return defaultIdleMinutes
	}
	return n
}

// systemIdleMethod 做 system.idle：读现在的值。
func systemIdleMethod(e *env, _ oneShotParams) (any, error) {
	return obj{"minutes": e.idleMinutes()}, nil
}

// systemSetIdleMethod 做 system.set_idle {minutes}：整份换掉，看门狗读到的是旧值或新值。
func systemSetIdleMethod(e *env, p oneShotParams) (any, error) {
	if p.Minutes == nil || *p.Minutes < 0 || *p.Minutes > maxIdleMinutes {
		return nil, errors.New("minutes 要是 0～" + strconv.Itoa(maxIdleMinutes) + " 的整数")
	}
	if err := os.MkdirAll(filepath.Dir(e.idle), 0o777); err != nil {
		return nil, err
	}
	if err := writeFileAtomic(e.idle, []byte(strconv.Itoa(*p.Minutes)+"\n")); err != nil {
		return nil, err
	}
	return obj{"minutes": *p.Minutes}, nil
}
