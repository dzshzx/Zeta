package main

import (
	"net"
	"os"

	"github.com/dzshzx/Zeta/helper/core"
)

// takeover 是 core-takeover 输出的一行。
type takeover struct {
	Type     string `json:"type"`     // zeta_takeover
	Replaced bool   `json:"replaced"` // 原来有核心在跑、让它交了班
	Build    int64  `json:"build"`    // 新核心的构建序号
}

// cmdCoreTakeover 让在跑的核心交班（不管版本新旧），再由本进程拉起新核心。
//
// 手机上每次进 Debian 都在一个新的私有挂载环境里（zeta_enter.sh 的 unshare -m），共享文件夹在进入时才挂上；常驻的核心和它起的
// Claude Code 只看得到核心启动那一刻挂着的。共享文件夹变了以后，客户端经一次新的进入跑这个子命令：新核心是本进程拉起的，
// 看得到现在的挂载，之后起的 Claude Code 也就看得到。交班时进行中的对话照旧（见 docs/core.md「新的接手」），已经在跑、
// 看不到新文件夹的进程由客户端按 host_key 判断要不要重起；闲着的托管进程结束掉（endIdleHosted），免得以后被接回时看不到。
// 旧核心的 serve 随核心退出，客户端照常重连。
func cmdCoreTakeover(e *env, o *emitter) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	p, err := core.Locate(e.home, e.name)
	if err != nil {
		return err
	}
	replaced := false
	if conn, err := net.Dial("unix", p.Socket); err == nil {
		handOver(conn) // 连上时核心先发的 core_hello 随交班后读到头的一起丢掉
		replaced = true
	}
	conn, info, err := core.Connect(p, exe)
	if err != nil {
		return err
	}
	_ = conn.Close()
	endIdleHosted(e)
	o.out(takeover{Type: "zeta_takeover", Replaced: replaced, Build: info.Build})
	return nil
}
